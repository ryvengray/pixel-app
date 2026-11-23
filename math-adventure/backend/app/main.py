from __future__ import annotations

import ast
import hashlib
import json
import math
import re
import unicodedata
import os
import secrets
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from uuid import uuid4
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse

from .access import Access
from .models import Answer, DeviceInput, Note, Operation, PlayerInput, QuestionInput, QuestionBatch, Review, TaskSelection
from .samples import seed
from .store import Store, encode, now


def token_hash(value):
    return hashlib.sha256(value.encode()).hexdigest()


# Units are display text: compare the number in the expression's own unit,
# without guessing conversions or accepting ambiguous multi-number answers.
ANSWER_UNITS = frozenset(('个 颗 只 本 支 张 块 根 条 朵 辆 棵 杯 盒 袋 枚 件 人 位 天 小时 分钟 秒 米 厘米 毫米 千米 克 千克 公斤 元 角 分 平方米 平方厘米 立方米 升 毫升 倍 岁 页 次 步 层 排 组 对 套 份 把 瓶 盆 盏 圈 周 月 年').split())

def numeric_option(value):
    match = re.fullmatch(r'([+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+))\s*([^\s0-9]*)', unicodedata.normalize('NFKC', value).strip())
    if not match or (match[2] and match[2] not in ANSWER_UNITS):
        raise ValueError('正确选项须为数字或数字加常见单位（例如20颗）；非数值题请将 expression 设为 null')
    return float(match[1])


def check_question(data):
    q = QuestionInput.model_validate(data)
    errors = []
    if q.expression:
        # A deliberately small numeric grammar: no names, functions or Python evaluation.
        def calculate(node):
            if isinstance(node, ast.Constant) and type(node.value) in (int, float) and abs(node.value) <= 10000:
                return node.value
            if isinstance(node, ast.BinOp) and type(node.op) in (ast.Add, ast.Sub, ast.Mult, ast.Div):
                left, right = calculate(node.left), calculate(node.right)
                result = {ast.Add: lambda: left+right, ast.Sub: lambda: left-right,
                          ast.Mult: lambda: left*right, ast.Div: lambda: left/right}[type(node.op)]()
                if abs(result) > 1000000:
                    raise ValueError('计算结果超出范围')
                return result
            raise ValueError('仅支持非负数字和加减乘除表达式')
        try:
            expected = calculate(ast.parse(q.expression, mode='eval').body)
            try:
                actual = q.answer if q.question_type == "number" else numeric_option(q.options[q.answer])
            except ValueError as error:
                errors.append(str(error))
            else:
                if not math.isclose(actual, expected, rel_tol=1e-12, abs_tol=1e-12):
                    errors.append('答案数值与计算表达式不一致，请按同一单位填写表达式')
        except (SyntaxError, ValueError, TypeError, ZeroDivisionError, OverflowError, RecursionError):
            errors.append('计算表达式或数字答案无效')
    return {'passed': not errors, 'errors': errors,
            'scope': '结构及可选数值核验（不自动换算单位）；语言、逻辑、单位、唯一性和小屏适配仍需人工审核'}


def create_app(db_path=None, admin_token=None):
    store = Store(db_path or os.getenv('DATABASE_PATH', '/data/math-adventure.db'))
    expected_admin = admin_token if admin_token is not None else os.getenv('MATH_ADMIN_TOKEN', '')
    access = Access(store, expected_admin)
    tz = ZoneInfo(os.getenv('MATH_TIMEZONE', 'Asia/Shanghai'))

    @asynccontextmanager
    async def lifespan(app):
        if len(expected_admin) < 24:
            raise RuntimeError('MATH_ADMIN_TOKEN must contain at least 24 characters')
        store.setup()
        store.upgrade_owners()
        access.setup()
        with store.connect(write=True) as db:
            db.execute("INSERT OR IGNORE INTO players(id,nickname,mode,owner) VALUES('live','小勇士','live','administrator')")
            db.execute("INSERT OR IGNORE INTO players(id,nickname,mode,owner) VALUES('practice','试玩小勇士','practice','administrator')")
            seed(db)
            # Retire drafts rejected before deletion became the default workflow.
            db.execute("UPDATE questions SET status='deleted',active=0 WHERE status='draft' AND COALESCE(review_comment,'')<>''")
        yield

    app = FastAPI(title='小语数学冒险岛', version='0.1.0', lifespan=lifespan, root_path=os.getenv('MATH_ROOT_PATH', ''))
    app.state.store = store

    @app.middleware('http')
    async def headers(request, call_next):
        response = await call_next(request)
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['Referrer-Policy'] = 'no-referrer'
        response.headers['X-Frame-Options'] = 'DENY'
        return response

    def bearer(authorization):
        if not authorization or not authorization.startswith('Bearer '):
            raise HTTPException(401, '请提供有效的访问凭证')
        return authorization[7:]

    def parent(request: Request, authorization: str | None = Header(default=None)):
        return access.parent(request, authorization)

    def ensure_player(db, owner, mode):
        pid=mode if owner=='administrator' else token_hash(owner)[:24]+'-'+mode
        db.execute('INSERT OR IGNORE INTO players(id,nickname,mode,owner) VALUES(?,?,?,?)',
                   (pid,'小勇士' if mode=='live' else '试玩小勇士',mode,owner))
        seed(db,owner)
        return pid

    def owner_player(owner, mode):
        with store.connect(write=True) as db:
            return ensure_player(db,owner,mode)

    access.install(app, parent, ensure_player)

    def device(authorization: str | None = Header(default=None)):
        digest = token_hash(bearer(authorization))
        with store.connect(write=True) as db:
            row = db.execute('SELECT d.*,p.mode FROM devices d JOIN players p ON p.id=d.player_id '
                             'WHERE token_hash=? AND revoked=0', (digest,)).fetchone()
            if row is None:
                raise HTTPException(401, '设备凭证无效或已撤销')
            db.execute('UPDATE devices SET last_seen_at=? WHERE id=?', (now(), row['id']))
            return dict(row)

    def day():
        return datetime.now(tz).date().isoformat()

    def player(db, player_id):
        row = dict(db.execute('SELECT * FROM players WHERE id=?', (player_id,)).fetchone())
        row.pop('owner',None)
        row['level'] = row['xp'] // 100 + 1
        return row

    def audit(db, action, subject, details, owner="administrator"):
        db.execute('INSERT INTO audit(action,subject,details,created_at,owner) VALUES(?,?,?,?,?)',
                   (action, subject, encode(details), now(),owner))

    def question_row(db, question_id, owner=None):
        row = db.execute('SELECT * FROM questions WHERE id=?', (question_id,)).fetchone()
        if row is None or (owner is not None and (row['owner']!=owner or row['status']=='deleted')):
            raise HTTPException(404, '题目不存在')
        return row

    def admin_question(row):
        result = dict(row)
        result['data'] = json.loads(result['data'])
        result['checks'] = json.loads(result['checks']) if result['checks'] else None
        return result

    def usable(row, mode):
        return row['status'] == 'published' and row['active'] or (
            mode == 'practice' and row['status'] in ('draft', 'pending') and
            check_question(json.loads(row['data']))['passed'])

    def history(db, player_id, key):
        return dict(db.execute('SELECT COUNT(*) AS attempt_count,COALESCE(SUM(a.correct=0),0) AS wrong_count,COALESCE(SUM(a.correct=1),0) AS correct_count FROM attempts a JOIN tasks t ON t.id=a.task_id JOIN questions q ON q.id=t.question_id WHERE t.player_id=? AND q.question_key=?', (player_id, key)).fetchone())

    def ensure_tasks(db, player_id, mode):
        existing = db.execute('SELECT COUNT(*) FROM tasks WHERE player_id=? AND day=? AND archived=0', (player_id, day())).fetchone()[0]
        if existing:
            return
        # A new draft must not hide the last published version from live tasks.
        # A disabled published revision must not silently fall back to an older revision.
        statuses = "('draft','pending','published','disabled')" if mode == 'practice' else "('published','disabled')"
        candidates = db.execute(
            f'SELECT * FROM questions q WHERE owner=? AND status IN {statuses} AND version='
            f'(SELECT MAX(version) FROM questions WHERE question_key=q.question_key AND status IN {statuses}) '
            'ORDER BY question_key', (db.execute('SELECT owner FROM players WHERE id=?',(player_id,)).fetchone()[0],)).fetchall()
        eligible = [q for q in candidates if usable(q, mode) and not history(db, player_id, q["question_key"])["attempt_count"]]
        # Stable within the day; rotates available versions across days.
        if eligible:
            offset = datetime.now(tz).date().toordinal() % len(eligible)
            eligible = eligible[offset:] + eligible[:offset]
        for i, q in enumerate(eligible[:3]):
            db.execute('INSERT INTO tasks(id,player_id,day,question_id,ordinal,created_at) VALUES(?,?,?,?,?,?)',
                       (uuid4().hex, player_id, day(), q['id'], i, now()))

    def task_view(db, row, mode):
        result = dict(row)
        qrow = question_row(db, row['question_id'])
        q = json.loads(qrow['data'])
        available = bool(usable(qrow, mode))
        result['available'] = available
        result['reviewed'] = qrow['status'] == 'published'
        result['question'] = ({'id': qrow['id'], 'version': qrow['version'], 'title': q['title'],
                               'content': q['content'], 'options': q['options'], 'question_type': q.get('question_type', 'choice'), 'topic': q['topic'],
                               'skill_tags': q['skill_tags'], 'difficulty': q.get('difficulty', 1),
                               'solution': {'answer': q['answer'], 'hints': q['hints'], 'explanation': q['explanation']}} if available else None)
        if row['state'] == 'solved':
            result['explanation'] = q['explanation']
        return result

    def today_view(db, player_id, mode):
        rows = db.execute('SELECT * FROM tasks WHERE player_id=? AND day=? AND archived=0 ORDER BY ordinal', (player_id, day())).fetchall()
        tasks = [task_view(db, row, mode) for row in rows]
        active = [t for t in tasks if t['available']]
        return {'day': day(), 'timezone': str(tz), 'mode': mode, 'player': player(db, player_id), 'tasks': tasks,
                'finished': bool(active) and all(t['state'] in ('solved', 'skipped') for t in active),
                'message': ('试玩数据独立保存，不计入正式学习记录' if mode == 'practice' else
                            ('今天的冒险已准备好' if active else '今天的冒险还在准备中，请家长审核并发布题目'))}

    @app.get('/health')
    def health():
        with store.connect() as db:
            db.execute('SELECT 1').fetchone()
        return {'ok': True, 'service': 'math-adventure'}

    @app.get('/', include_in_schema=False)
    def page():
        return FileResponse(Path(__file__).with_name('parent.html'))

    @app.get('/api/math/parent/questions', dependencies=[Depends(parent)])
    def questions(mode: str = Query('live', pattern='^(live|practice)$'), owner=Depends(parent)):
        pid = owner_player(owner,mode)
        with store.connect() as db:
            return [{**admin_question(q), **history(db, pid, q['question_key'])} for q in db.execute("SELECT * FROM questions WHERE owner=? AND status<>'deleted' ORDER BY question_key,version DESC",(owner,))]

    @app.post('/api/math/parent/questions', dependencies=[Depends(parent)], status_code=201)
    def new_question(body: QuestionInput, owner=Depends(parent)):
        key = uuid4().hex
        with store.connect(write=True) as db:
            qid = f'{key}-v1'
            db.execute('INSERT INTO questions(id,question_key,version,data,created_at,owner) VALUES(?,?,1,?,?,?)',
                       (qid, key, encode(body.model_dump()), now(),owner))
            audit(db, 'draft', qid, {}, owner=owner)
            return admin_question(question_row(db, qid, owner))

    @app.post('/api/math/parent/questions/import', status_code=201)
    def import_questions(body: QuestionBatch, owner=Depends(parent)):
        # Validate the entire batch before opening one atomic write transaction.
        with store.connect(write=True) as db:
            rows = []
            for question in body.questions:
                key = uuid4().hex
                qid = key + '-v1'
                db.execute('INSERT INTO questions(id,question_key,version,data,created_at,owner) VALUES(?,?,1,?,?,?)',
                           (qid, key, encode(question.model_dump()), now(), owner))
                audit(db, 'draft', qid, {'batch_import': True}, owner=owner)
                rows.append(admin_question(question_row(db, qid, owner)))
            return rows

    @app.post('/api/math/parent/questions/{qid}/versions', dependencies=[Depends(parent)], status_code=201)
    def revise(qid: str, body: QuestionInput, owner=Depends(parent)):
        with store.connect(write=True) as db:
            old = question_row(db, qid, owner)
            version = db.execute('SELECT MAX(version)+1 FROM questions WHERE question_key=?', (old['question_key'],)).fetchone()[0]
            new_id = f'{old["question_key"]}-v{version}'
            db.execute('INSERT INTO questions(id,question_key,version,data,created_at,owner) VALUES(?,?,?,?,?,?)',
                       (new_id, old['question_key'], version, encode(body.model_dump()), now(),owner))
            audit(db, 'revision', new_id, {'from': qid}, owner=owner)
            return admin_question(question_row(db, new_id))

    @app.post('/api/math/parent/questions/{qid}/check', dependencies=[Depends(parent)])
    def check(qid: str, owner=Depends(parent)):
        with store.connect(write=True) as db:
            row = question_row(db, qid, owner)
            if row['status'] not in ('draft', 'pending'):
                raise HTTPException(409, '仅草稿或待审核题目可以自动检查')
            result = check_question(json.loads(row['data']))
            db.execute('UPDATE questions SET checks=?,status=? WHERE id=?',
                       (encode(result), 'pending' if result['passed'] else 'draft', qid))
            audit(db, 'check', qid, result, owner=owner)
            return result

    @app.post('/api/math/parent/questions/{qid}/publish', dependencies=[Depends(parent)])
    def publish(qid: str, body: Review, owner=Depends(parent)):
        with store.connect(write=True) as db:
            row = question_row(db, qid, owner)
            if row['status'] != 'pending' or not body.independently_solved:
                raise HTTPException(409, '须先通过自动检查，并由审核者独立解题确认')
            if not check_question(json.loads(row['data']))['passed']:
                raise HTTPException(409, '题目自动检查未通过')
            latest = db.execute('SELECT MAX(version) FROM questions WHERE question_key=?', (row['question_key'],)).fetchone()[0]
            if row['version'] != latest:
                raise HTTPException(409, '请审核最新版本')
            # Keep already assigned historical versions valid; disable them explicitly if erroneous.
            db.execute("UPDATE questions SET status='published',active=1,reviewer=?,reviewed_at=?,review_comment=?,published_at=? WHERE id=?",
                       (body.reviewer, now(), body.comment, now(), qid))
            audit(db, 'publish', qid, body.model_dump(), owner=owner)
            return admin_question(question_row(db, qid, owner))

    @app.post('/api/math/parent/questions/{qid}/reject', dependencies=[Depends(parent)])
    def reject(qid: str, body: Note, owner=Depends(parent)):
        with store.connect(write=True) as db:
            row = question_row(db, qid, owner)
            if row['status'] not in ('draft', 'pending'):
                raise HTTPException(409, '已发布题目请先停用或创建新版本')
            db.execute("UPDATE questions SET status='deleted',active=0,review_comment=? WHERE id=?", (body.comment, qid))
            audit(db, 'reject', qid, body.model_dump(), owner=owner)
            return {'ok': True}

    @app.post('/api/math/parent/questions/{qid}/disable', dependencies=[Depends(parent)])
    def disable(qid: str, body: Note, owner=Depends(parent)):
        with store.connect(write=True) as db:
            question_row(db, qid, owner)
            db.execute("UPDATE questions SET active=0,status='disabled' WHERE id=?", (qid,))
            audit(db, 'disable', qid, body.model_dump(), owner=owner)
            return {'ok': True}

    @app.post('/api/math/parent/devices', dependencies=[Depends(parent)], status_code=201)
    def register(body: DeviceInput, owner=Depends(parent)):
        value, did = secrets.token_urlsafe(32), uuid4().hex
        with store.connect(write=True) as db:
            db.execute('INSERT INTO devices(id,name,player_id,token_hash) VALUES(?,?,?,?)',
                       (did, body.name, ensure_player(db,owner,body.mode), token_hash(value)))
            audit(db, 'device_create', did, {'name': body.name, 'mode': body.mode}, owner=owner)
        return {'id': did, 'token': value, 'mode': body.mode, 'message': '令牌仅显示一次，请妥善保存'}

    @app.get('/api/math/parent/devices', dependencies=[Depends(parent)])
    def devices(owner=Depends(parent)):
        with store.connect() as db:
            return [dict(row) for row in db.execute('SELECT id,name,player_id,revoked,last_seen_at,firmware_version FROM devices WHERE player_id IN (SELECT id FROM players WHERE owner=?)',(owner,))]

    @app.post('/api/math/parent/devices/{did}/revoke', dependencies=[Depends(parent)])
    def revoke(did: str, owner=Depends(parent)):
        with store.connect(write=True) as db:
            if not db.execute('UPDATE devices SET revoked=1 WHERE id=? AND player_id IN (SELECT id FROM players WHERE owner=?)', (did,owner)).rowcount:
                raise HTTPException(404, '设备不存在')
            audit(db, 'device_revoke', did, {}, owner=owner)
        return {'ok': True}

    @app.patch('/api/math/parent/player', dependencies=[Depends(parent)])
    def rename(body: PlayerInput, mode: str = Query('live', pattern='^(live|practice)$'), owner=Depends(parent)):
        pid=owner_player(owner,mode)
        with store.connect(write=True) as db:
            db.execute('UPDATE players SET nickname=? WHERE id=?', (body.nickname, pid))
            return player(db, pid)

    @app.get('/api/math/parent/dashboard', dependencies=[Depends(parent)])
    def dashboard(mode: str = Query('live', pattern='^(live|practice)$'), owner=Depends(parent)):
        pid=owner_player(owner,mode)
        with store.connect() as db:
            result = today_view(db, pid, mode)
            result['attempts'] = [dict(r) for r in db.execute(
                'SELECT a.*,t.question_id,t.day,q.data FROM attempts a JOIN tasks t ON t.id=a.task_id '
                'JOIN questions q ON q.id=t.question_id WHERE t.player_id=? ORDER BY a.id DESC LIMIT 100', (pid,))]
            for a in result['attempts']:
                q = json.loads(a.pop('data'))
                a['title'], a['content'], a['selected'] = q['title'], q['content'], (a['answer'] if q.get('question_type') == 'number' else q['options'][int(a['answer'])])
            result['trend'] = [dict(r) for r in db.execute(
                'SELECT t.day,COUNT(*) AS attempts,SUM(a.correct) AS correct,SUM(a.duration_seconds) AS seconds '
                'FROM attempts a JOIN tasks t ON t.id=a.task_id WHERE t.player_id=? '
                'GROUP BY t.day ORDER BY t.day DESC LIMIT 14', (pid,))]
            result['skills'] = [dict(r) for r in db.execute(
                "SELECT json_extract(q.data,'$.topic') AS topic,COUNT(*) AS practiced,SUM(t.state='solved') AS solved "
                'FROM tasks t JOIN questions q ON q.id=t.question_id WHERE t.player_id=? GROUP BY topic', (pid,))]
            return result

    @app.post('/api/math/parent/tasks/generate', dependencies=[Depends(parent)])
    def generate(mode: str = Query('live', pattern='^(live|practice)$'), owner=Depends(parent)):
        pid=owner_player(owner,mode)
        with store.connect(write=True) as db:
            ensure_tasks(db, pid, mode)
            return today_view(db, pid, mode)

    @app.post('/api/math/parent/tasks/select')
    def select_tasks(body: TaskSelection, mode: str = Query('live', pattern='^(live|practice)$'), owner=Depends(parent)):
        pid = owner_player(owner, mode)
        with store.connect(write=True) as db:
            rows = [question_row(db, qid, owner) for qid in dict.fromkeys(body.question_ids)]
            if len({q['question_key'] for q in rows}) != len(rows):
                raise HTTPException(422, '同一道题只能选择一个版本')
            if any(not usable(q, mode) for q in rows):
                raise HTTPException(409, '请先确认发布所选题目，已停用的题目不能安排')
            existing = db.execute('SELECT * FROM tasks WHERE player_id=? AND day=? ORDER BY ordinal', (pid, day())).fetchall()
            chosen = {q['id'] for q in rows}
            for t in existing:
                db.execute('UPDATE tasks SET archived=? WHERE id=?', (int(t['question_id'] not in chosen), t['id']))
            retained = {t['question_id'] for t in existing if t['question_id'] in chosen}
            ordinal = max([t['ordinal'] for t in existing] + [-1]) + 1
            for q in rows:
                if q['id'] not in retained:
                    db.execute('INSERT INTO tasks(id,player_id,day,question_id,ordinal,created_at) VALUES(?,?,?,?,?,?)', (uuid4().hex,pid,day(),q['id'],ordinal,now()))
                    ordinal += 1
            return today_view(db, pid, mode)

    @app.get('/api/math/parent/rewards', dependencies=[Depends(parent)])
    def reward_ledger(mode: str = Query('live', pattern='^(live|practice)$'), owner=Depends(parent)):
        pid=owner_player(owner,mode)
        with store.connect() as db:
            return [dict(r) for r in db.execute(
                'SELECT * FROM rewards WHERE player_id=? ORDER BY id DESC LIMIT 100', (pid,))]

    @app.get('/api/math/parent/audit', dependencies=[Depends(parent)])
    def audit_log(owner=Depends(parent)):
        with store.connect() as db:
            return [dict(r) for r in db.execute('SELECT * FROM audit WHERE owner=? ORDER BY id DESC LIMIT 100',(owner,))]

    @app.get('/api/math/adventure/today')
    @app.get('/api/math/device/state')
    def today(dev=Depends(device)):
        with store.connect(write=True) as db:
            ensure_tasks(db, dev['player_id'], dev['mode'])
            return today_view(db, dev['player_id'], dev['mode'])

    @app.get('/api/math/questions/{qid}')
    def assigned_question(qid: str, dev=Depends(device)):
        with store.connect() as db:
            row = db.execute('SELECT * FROM tasks WHERE question_id=? AND player_id=? AND day=? AND archived=0',
                             (qid, dev['player_id'], day())).fetchone()
            if row is None:
                raise HTTPException(404, '题目未分配给该设备')
            return task_view(db, row, dev['mode'])

    @app.post('/api/math/device/heartbeat')
    def heartbeat(dev=Depends(device)):
        return {'ok': True, 'server_time': now(), 'mode': dev['mode']}

    def reward(db, player_id, key, xp, coins):
        cursor = db.execute('INSERT OR IGNORE INTO rewards(player_id,business_key,xp,coins,created_at) VALUES(?,?,?,?,?)',
                            (player_id, key, xp, coins, now()))
        if cursor.rowcount:
            db.execute('UPDATE players SET xp=xp+?,coins=coins+? WHERE id=?', (xp, coins, player_id))
            return xp, coins
        return 0, 0

    def operate(body, kind, dev):
        payload = encode({'kind': kind, **body.model_dump()})
        with store.connect(write=True) as db:
            cached = db.execute('SELECT * FROM requests WHERE device_id=? AND request_id=?',
                                (dev['id'], body.request_id)).fetchone()
            if cached:
                if cached['payload'] != payload:
                    raise HTTPException(409, '同一请求ID不可用于不同内容或操作')
                return json.loads(cached['response'])
            task = db.execute('SELECT * FROM tasks WHERE id=? AND player_id=?', (body.task_id, dev['player_id'])).fetchone()
            if task is None:
                raise HTTPException(404, '任务不存在')
            if task['day'] != day():
                raise HTTPException(409, '这不是今天的任务，请重新同步')
            qrow = question_row(db, task['question_id'])
            if not usable(qrow, dev['mode']):
                raise HTTPException(409, '题目已停用，请重新同步')
            q = json.loads(qrow['data'])
            response = {'task_id': task['id'], 'mode': dev['mode'], 'reward': {'xp': 0, 'coins': 0}}
            if kind == 'answer':
                if q.get('question_type', 'choice') == 'choice' and (type(body.answer) is not int or not 0 <= body.answer < len(q['options'])):
                    raise HTTPException(422, '选项不存在')
                correct = body.answer == q['answer']
                response.update(correct=correct, task_completed=task['state'] == 'solved' or correct)
                # Completed tasks are read-only, even with a new request ID.
                if task['state'] != 'solved':
                    db.execute('INSERT INTO attempts(task_id,answer,correct,hint_count,duration_seconds,created_at) VALUES(?,?,?,?,?,?)',
                               (task['id'], body.answer, int(correct), task['hint_count'], body.duration_seconds, now()))
                    if correct:
                        db.execute("UPDATE tasks SET state='solved',completed_at=? WHERE id=?", (now(), task['id']))
                        first = db.execute('SELECT COUNT(*) FROM attempts WHERE task_id=?', (task['id'],)).fetchone()[0] == 1
                        xp, coins = reward(db, dev['player_id'], 'question:'+qrow['question_key'], 20,
                                           5 if first and task['hint_count'] == 0 else 0)
                        response['reward'] = {'xp': xp, 'coins': coins}
                        current = today_view(db, dev['player_id'], dev['mode'])
                        active = [t for t in current['tasks'] if t['available']]
                        if active and all(t['state'] == 'solved' for t in active):
                            bonus, _ = reward(db, dev['player_id'], 'daily:'+day(), 10, 0)
                            response['reward']['xp'] += bonus
                if correct or task['state'] == 'solved':
                    response['explanation'] = q['explanation']
                response['message'] = '好办法！谢谢你' if correct else '再想一想，你可以的'
            elif kind == 'hint':
                if task['state'] == 'solved':
                    response['hint'] = q['explanation']
                else:
                    n = min(task['hint_count'], len(q['hints'])-1)
                    response['hint'] = q['hints'][n]
                    db.execute('UPDATE tasks SET hint_count=hint_count+1 WHERE id=?', (task['id'],))
            elif kind == 'skip':
                if task['state'] != 'solved':
                    db.execute("UPDATE tasks SET state='skipped' WHERE id=?", (task['id'],))
                response['message'] = '进度存好啦，之后再想想'
            response['player'] = player(db, dev['player_id'])
            response['state'] = db.execute('SELECT state FROM tasks WHERE id=?', (task['id'],)).fetchone()[0]
            db.execute('INSERT INTO requests(device_id,request_id,payload,response,created_at) VALUES(?,?,?,?,?)',
                       (dev['id'], body.request_id, payload, encode(response), now()))
            return response

    @app.post('/api/math/answers')
    def answer(body: Answer, dev=Depends(device)):
        return operate(body, 'answer', dev)

    @app.post('/api/math/tasks/hint')
    def hint(body: Operation, dev=Depends(device)):
        return operate(body, 'hint', dev)

    @app.post('/api/math/tasks/skip')
    def skip(body: Operation, dev=Depends(device)):
        return operate(body, 'skip', dev)

    return app


app = create_app()
