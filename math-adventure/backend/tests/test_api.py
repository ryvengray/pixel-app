from concurrent.futures import ThreadPoolExecutor
import json
import sqlite3
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from app.main import check_question, create_app

ADMIN = 'test-parent-credential-32-characters'
PARENT = {'Authorization': 'Bearer '+ADMIN}

@pytest.fixture
def system(tmp_path):
    path = tmp_path / 'math.db'
    app = create_app(path, ADMIN)
    with TestClient(app) as client:
        yield client, path

def device(client, mode='practice'):
    r = client.post('/api/math/parent/devices', headers=PARENT, json={'name': '测试ESP32', 'mode': mode})
    assert r.status_code == 201, r.text
    d = r.json()
    return {'Authorization': 'Bearer '+d['token']}, d

def questions(client):
    return client.get('/api/math/parent/questions', headers=PARENT).json()

def publish(client, qid):
    assert client.post(f'/api/math/parent/questions/{qid}/check', headers=PARENT).json()['passed']
    r = client.post(f'/api/math/parent/questions/{qid}/publish', headers=PARENT,
                    json={'reviewer': '测试用虚构审核人', 'comment': '仅在隔离测试库中模拟人工确认', 'independently_solved': True})
    assert r.status_code == 200, r.text

def plan(client, auth):
    r = client.get('/api/math/adventure/today', headers=auth)
    assert r.status_code == 200, r.text
    return r.json()

def answer(client, auth, task, option, request_id=None):
    return client.post('/api/math/answers', headers=auth, json={
        'task_id': task['id'], 'answer': option, 'duration_seconds': 10, 'request_id': request_id or uuid4().hex})

def counts(path):
    with sqlite3.connect(path) as db:
        return tuple(db.execute('SELECT COUNT(*) FROM '+t).fetchone()[0] for t in ('attempts','rewards','requests'))

def test_fail_closed(tmp_path):
    with pytest.raises(RuntimeError, match='24 characters'):
        with TestClient(create_app(tmp_path/'missing.db', 'short')):
            pass

def test_auth_and_separate_scopes(system):
    c, path = system
    assert c.get('/health').json()['ok']
    assert c.get('/').status_code == 200
    assert c.get('/api/math/parent/questions').status_code == 401
    assert c.get('/api/math/device/state', headers=PARENT).status_code == 401
    auth, d = device(c)
    assert c.get('/api/math/parent/questions', headers=auth).status_code == 401
    assert c.get('/api/math/parent/devices', headers=PARENT).json()[0].get('token_hash') is None
    with sqlite3.connect(path) as db:
        assert d['token'] not in db.execute('SELECT token_hash FROM devices').fetchone()[0]
    assert c.post(f'/api/math/parent/devices/{d["id"]}/revoke', headers=PARENT).status_code == 200
    assert c.get('/api/math/device/state', headers=auth).status_code == 401

def test_drafts_are_not_live_and_samples_check(system):
    c, _ = system
    rows = questions(c)
    assert len(rows) == 10
    assert all(q['status']=='draft' and q['data']['source_type']=='ai_draft' for q in rows)
    assert all(check_question(q['data'])['passed'] for q in rows)
    live, _ = device(c, 'live')
    assert plan(c, live)['tasks'] == []
    practice, _ = device(c)
    p = plan(c, practice)
    assert len(p['tasks']) == 3 and p['mode'] == 'practice'
    for t in p['tasks']:
        assert not t['reviewed']
        assert not {'answer', 'explanation', 'hints', 'expression'}.intersection(t['question'])
    assert plan(c, practice)['tasks'] == p['tasks']

def test_review_and_expression_is_not_evaluated(system):
    c, _ = system
    row = questions(c)[0]
    qid = row['id']
    body = {'reviewer':'test','comment':'test','independently_solved':True}
    assert c.post(f'/api/math/parent/questions/{qid}/publish',headers=PARENT,json=body).status_code == 409
    c.post(f'/api/math/parent/questions/{qid}/check', headers=PARENT)
    body['independently_solved'] = False
    assert c.post(f'/api/math/parent/questions/{qid}/publish',headers=PARENT,json=body).status_code == 409
    assert c.post(f'/api/math/parent/questions/{qid}/reject',headers=PARENT,json={'comment':'表述待改'}).status_code == 200
    assert all(q['id'] != qid for q in questions(c))
    assert c.post(f'/api/math/parent/questions/{qid}/check', headers=PARENT).status_code == 404
    data = row['data']
    data['expression'] = '__import__("os").system("false")'
    r = c.post('/api/math/parent/questions',headers=PARENT,json=data)
    bad_id = r.json()['id']
    assert c.post(f'/api/math/parent/questions/{bad_id}/check',headers=PARENT).json()['passed']
    assert c.post(f'/api/math/parent/questions/{bad_id}/publish',headers=PARENT,json=body).status_code == 409
    data['expression'] = '1+1'
    data['answer'] = 0
    assert check_question(data)['passed']

def test_new_draft_does_not_hide_published_and_disabled_does_not_fallback(system):
    c, _ = system
    row = questions(c)[0]
    publish(c, row['id'])
    revised = c.post(f'/api/math/parent/questions/{row["id"]}/versions',headers=PARENT,json=row['data']).json()
    assert revised['version'] == 2 and revised['status'] == 'draft'
    auth, _ = device(c,'live')
    first = plan(c,auth)
    assert [t['question_id'] for t in first['tasks']] == [row['id']]
    publish(c,revised['id'])
    assert plan(c,auth)['tasks'][0]['question_id'] == row['id'] # Existing plan keeps its version.
    c.post(f'/api/math/parent/questions/{row["id"]}/disable',headers=PARENT,json={'comment':'错误停用'})
    assert not plan(c,auth)['tasks'][0]['available']
    t=first['tasks'][0]
    assert answer(c,auth,t,row['data']['answer']).status_code == 409

def test_idempotence_conflicts_and_duplicate_rewards(system):
    c,path = system
    auth, _ = device(c)
    task = plan(c,auth)['tasks'][0]
    key = {q['id']:q for q in questions(c)}
    right = key[task['question_id']]['data']['answer']
    rid=uuid4().hex
    first=answer(c,auth,task,right,rid)
    assert first.status_code == 200, first.text
    assert first.json()['reward'] == {'xp':20,'coins':5}
    assert answer(c,auth,task,right,rid).json() == first.json()
    assert answer(c,auth,task,(right+1)%3,rid).status_code == 409
    assert answer(c,auth,task,right).json()['reward'] == {'xp':0,'coins':0}
    assert counts(path)[:2] == (1,1)
    assert c.post('/api/math/tasks/skip',headers=auth,json={'task_id':task['id'],'request_id':rid}).status_code == 409

def test_hint_wrong_answer_skip_and_daily_bonus(system):
    c,path=system
    auth,_=device(c)
    tasks=plan(c,auth)['tasks']
    answers={q['id']:q['data']['answer'] for q in questions(c)}
    t=tasks[0]
    operation={'task_id':t['id'],'request_id':uuid4().hex}
    assert c.post('/api/math/tasks/hint',headers=auth,json=operation).status_code==200
    c.post('/api/math/tasks/hint',headers=auth,json=operation)
    assert plan(c,auth)['tasks'][0]['hint_count']==1
    wrong=answer(c,auth,t,(answers[t['question_id']]+1)%3).json()
    assert wrong['reward']=={'xp':0,'coins':0} and 'explanation' not in wrong
    c.post('/api/math/tasks/skip',headers=auth,json={'task_id':t['id'],'request_id':uuid4().hex})
    assert plan(c,auth)['tasks'][0]['state']=='skipped'
    assert answer(c,auth,t,answers[t['question_id']]).json()['reward']=={'xp':20,'coins':0}
    for t in tasks[1:]:
        assert answer(c,auth,t,answers[t['question_id']]).status_code==200
    state=plan(c,auth)
    assert state['finished']
    assert state['player']['xp']==70 and state['player']['coins']==10
    assert c.get('/api/math/parent/dashboard',headers=PARENT).json()['player']['xp']==0
    dashboard=c.get('/api/math/parent/dashboard?mode=practice',headers=PARENT).json()
    assert len(dashboard['attempts'])==4 and dashboard['skills'] and dashboard['trend']
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM rewards WHERE business_key LIKE 'daily:%'").fetchone()[0]==1

def test_other_mode_cannot_use_task_or_question(system):
    c,_=system
    practice,_=device(c)
    live,_=device(c,'live')
    t=plan(c,practice)['tasks'][0]
    assert answer(c,live,t,0).status_code==404
    assert c.get('/api/math/questions/'+t['question_id'],headers=live).status_code==404
    public=c.get('/api/math/questions/'+t['question_id'],headers=practice).json()
    assert 'answer' not in public['question']

def test_concurrent_retry_and_concurrent_new_requests(system):
    c,path=system
    auth,_=device(c)
    t=plan(c,auth)['tasks'][0]
    right=next(q for q in questions(c) if q['id']==t['question_id'])['data']['answer']
    rid=uuid4().hex
    with ThreadPoolExecutor(max_workers=6) as pool:
        responses=list(pool.map(lambda _:answer(c,auth,t,right,rid),range(8)))
    assert all(r.status_code==200 for r in responses)
    assert all(r.json()==responses[0].json() for r in responses)
    with ThreadPoolExecutor(max_workers=6) as pool:
        responses=list(pool.map(lambda _:answer(c,auth,t,right),range(8)))
    assert all(r.status_code==200 for r in responses)
    assert counts(path)[:2]==(1,1)

def test_restart_and_expired_task_replay(system):
    c,path=system
    auth,_=device(c)
    t=plan(c,auth)['tasks'][0]
    right=next(q for q in questions(c) if q['id']==t['question_id'])['data']['answer']
    rid=uuid4().hex
    result=answer(c,auth,t,right,rid).json()
    with sqlite3.connect(path) as db:
        db.execute("UPDATE tasks SET day='2000-01-01' WHERE id=?",(t['id'],))
    assert answer(c,auth,t,right,rid).json()==result # Lost response can still be recovered.
    assert answer(c,auth,t,right).status_code==409
    with TestClient(create_app(path,ADMIN)) as restarted:
        dashboard=restarted.get('/api/math/parent/dashboard?mode=practice',headers=PARENT).json()
        assert dashboard['player']['xp']==20
        assert len(questions(restarted))==10
        assert restarted.post('/api/math/device/heartbeat',headers=auth).status_code==200

def test_live_reviewed_tasks_and_input_validation(system):
    c,_=system
    for q in questions(c)[:3]:
        publish(c,q['id'])
    auth,_=device(c,'live')
    tasks=plan(c,auth)['tasks']
    assert len(tasks)==3 and all(t['reviewed'] for t in tasks)
    assert answer(c,auth,tasks[0],99).status_code==422
    bad=questions(c)[0]['data']
    bad['answer']=2;bad['options']=['1','2']
    assert c.post('/api/math/parent/questions',headers=PARENT,json=bad).status_code==422
    assert c.post('/api/math/parent/devices',headers=PARENT,json={'name':'bad','mode':'other'}).status_code==422
    assert c.get('/api/math/parent/dashboard?mode=other',headers=PARENT).status_code==422


def test_parent_does_not_assign_during_question_review(system):
    c,_=system
    rows=questions(c)
    publish(c,rows[0]['id'])
    assert c.get('/api/math/parent/dashboard',headers=PARENT).json()['tasks']==[]
    for q in rows[1:3]:
        publish(c,q['id'])
    generated=c.post('/api/math/parent/tasks/generate',headers=PARENT).json()
    assert len(generated['tasks'])==3
    assert c.post('/api/math/parent/tasks/generate',headers=PARENT).json()['tasks']==generated['tasks']
    assert c.get('/api/math/parent/rewards',headers=PARENT).json()==[]


def test_disabled_latest_version_does_not_resurrect_old_version(system):
    c,path=system
    row=questions(c)[0]
    publish(c,row['id'])
    revised=c.post(f'/api/math/parent/questions/{row["id"]}/versions',headers=PARENT,json=row['data']).json()
    publish(c,revised['id'])
    c.post(f'/api/math/parent/questions/{revised["id"]}/disable',headers=PARENT,json={'comment':'最新题目有误'})
    auth,_=device(c,'live')
    assert plan(c,auth)['tasks']==[]


def test_cross_day_repeat_has_no_second_question_reward(system):
    c,path=system
    auth,_=device(c)
    tasks=plan(c,auth)['tasks']
    answers={q['id']:q['data']['answer'] for q in questions(c)}
    for t in tasks:
        answer(c,auth,t,answers[t['question_id']])
    with sqlite3.connect(path) as db:
        db.execute("UPDATE tasks SET day='2000-01-01' WHERE player_id='practice'")
    auto_tasks=plan(c,auth)['tasks']
    assert {t['question_id'] for t in tasks}.isdisjoint(t['question_id'] for t in auto_tasks)
    new_tasks=c.post('/api/math/parent/tasks/select?mode=practice',headers=PARENT,json={'question_ids':[t['question_id'] for t in tasks]}).json()['tasks']
    assert {t['id'] for t in tasks}.isdisjoint(t['id'] for t in new_tasks)
    for t in new_tasks:
        r=answer(c,auth,t,answers[t['question_id']]).json()
        assert r['reward']=={'xp':0,'coins':0} # Same date's daily bonus was already paid too.
    assert plan(c,auth)['player']['xp']==70


def test_quick_publish_and_rejection_reason(system):
    c, _ = system
    qid = questions(c)[0]['id']
    url = f'/api/math/parent/questions/{qid}'
    assert c.post(url+'/check', headers=PARENT).json()['passed']
    assert c.post(url+'/publish', headers=PARENT, json={}).status_code == 422
    assert c.post(url+'/reject', headers=PARENT, json={'comment':'  '}).status_code == 422
    result = c.post(url+'/publish', headers=PARENT, json={'independently_solved':True})
    assert result.status_code == 200
    assert result.json()['status'] == 'published'
    assert result.json()['reviewer'] == '家长'
    assert result.json()['review_comment'] == ''


def test_atomic_batch_import(system):
    c, _ = system
    original = questions(c)
    q = dict(original[0]['data'], source_type='ai_draft', difficulty=2)
    endpoint = '/api/math/parent/questions/import'
    assert c.post(endpoint, json={'questions':[q]}).status_code == 401
    for batch in [[], [q]*51, [q, dict(q, difficulty=4)]]:
        assert c.post(endpoint, headers=PARENT, json={'questions':batch}).status_code == 422
        assert len(questions(c)) == len(original)
    r = c.post(endpoint, headers=PARENT, json={'questions':[q, q]})
    assert r.status_code == 201
    assert len(r.json()) == 2
    assert all(row['status']=='draft' and not row['active'] for row in r.json())
    assert r.json()[0]['data']['difficulty'] == 2
    assert len(questions(c)) == len(original)+2


def test_rejected_question_removed_without_breaking_tasks(system):
    c, path = system
    auth, _ = device(c)
    task = plan(c, auth)['tasks'][0]
    qid = task['question_id']
    url = f'/api/math/parent/questions/{qid}'
    assert c.post(url+'/reject', headers=PARENT, json={'comment':'难度不合适'}).status_code == 200
    assert all(q['id'] != qid for q in questions(c))
    assert not next(t for t in plan(c, auth)['tasks'] if t['id']==task['id'])['available']
    assert c.post(url+'/versions', headers=PARENT, json=questions(c)[0]['data']).status_code == 404
    with sqlite3.connect(path) as db:
        from app.samples import seed
        seed(db)
        assert db.execute('SELECT status FROM questions WHERE id=?', (qid,)).fetchone()[0] == 'deleted'
    assert all(q['id'] != qid for q in questions(c))


def test_numeric_selection_history_and_retry(system):
    c, path = system
    q = questions(c)[0]['data']
    q.update(question_type='number', options=[], answer=-2.5, expression='0-5/2')
    r = c.post('/api/math/parent/questions', headers=PARENT, json=q)
    assert r.status_code == 201, r.text
    qid = r.json()['id']
    publish(c, qid)
    r = c.post('/api/math/parent/tasks/select', headers=PARENT, json={'question_ids':[qid]})
    assert r.status_code == 200, r.text
    task = r.json()['tasks'][0]
    assert task['question']['question_type'] == 'number'
    assert 'answer' not in task['question']
    auth, _ = device(c, 'live')
    req = uuid4().hex
    assert not answer(c, auth, task, 3.5, req).json()['correct']
    assert not answer(c, auth, task, 3.5, req).json()['correct']
    assert answer(c, auth, task, -2.5).json()['correct']
    report = c.get('/api/math/parent/dashboard', headers=PARENT)
    assert report.status_code == 200, report.text
    assert report.json()['attempts'][0]['selected'] == -2.5
    row = next(q for q in questions(c) if q['id'] == qid)
    assert (row['attempt_count'], row['wrong_count']) == (2, 1)
    practice = c.get('/api/math/parent/questions?mode=practice', headers=PARENT).json()
    assert next(q for q in practice if q['id'] == qid)['wrong_count'] == 0
    revision = c.post(f'/api/math/parent/questions/{qid}/versions', headers=PARENT, json=q).json()
    publish(c, revision['id'])
    assert next(q for q in questions(c) if q['id'] == revision['id'])['wrong_count'] == 1


def test_replace_started_daily_plan_preserves_history(system):
    c, path = system
    auth, _ = device(c)
    tasks = plan(c, auth)['tasks']
    qrows = questions(c)
    first = tasks[0]
    correct = next(q['data']['answer'] for q in qrows if q['id'] == first['question_id'])
    assert answer(c, auth, first, (correct+1) % 2).status_code == 200
    assert answer(c, auth, first, correct).json()['correct']
    replacement = next(q for q in qrows if q['id'] not in {t['question_id'] for t in tasks})
    url = '/api/math/parent/tasks/select?mode=practice'
    changed = c.post(url, headers=PARENT, json={'question_ids':[replacement['id']]})
    assert changed.status_code == 200, changed.text
    assert [t['question_id'] for t in changed.json()['tasks']] == [replacement['id']]
    dashboard = c.get('/api/math/parent/dashboard?mode=practice', headers=PARENT).json()
    assert len(dashboard['attempts']) == 2
    restored = c.post(url, headers=PARENT, json={'question_ids':[first['question_id']]}).json()['tasks']
    assert len(restored) == 1
    assert restored[0]['id'] == first['id']
    assert restored[0]['state'] == 'solved'
    stats = c.get('/api/math/parent/questions?mode=practice', headers=PARENT).json()
    row = next(q for q in stats if q['id'] == first['question_id'])
    assert row['attempt_count'] == 2
    assert row['correct_count'] == 1
    assert row['wrong_count'] == 1


@pytest.mark.parametrize('option', ['20颗', '20 颗', '２０颗', '20个', '20厘米', '20.0元', '20'])
def test_format_check_preserves_unit_options(system, option):
    c, _ = system
    q = questions(c)[0]['data']
    q.update(options=['18颗', option, '22颗'], answer=1, expression='12+8')
    created = c.post('/api/math/parent/questions', headers=PARENT, json=q)
    assert created.status_code == 201, created.text
    publish(c, created.json()['id'])
    saved = next(row for row in questions(c) if row['id'] == created.json()['id'])
    assert saved['data']['options'][1] == option


@pytest.mark.parametrize('option', ['21颗', '20或30颗', '大约20颗', '20颗以上', '20+1', 'nan', 'inf'])
def test_format_check_does_not_judge_answer_content(system, option):
    c, _ = system
    q = questions(c)[0]['data']
    q.update(options=['18颗', option, '22颗'], answer=1, expression='12+8')
    assert check_question(q)['passed']


def test_latest_revision_marker_includes_deleted_latest(system):
    c, _ = system
    original = questions(c)[0]
    revised = c.post(f"/api/math/parent/questions/{original['id']}/versions", headers=PARENT, json=original['data']).json()
    rows = [row for row in questions(c) if row['question_key'] == original['question_key']]
    assert {row['id']: row['is_latest'] for row in rows} == {original['id']: False, revised['id']: True}
    assert c.post(f"/api/math/parent/questions/{revised['id']}/reject", headers=PARENT, json={'comment':'不适合'}).status_code == 200
    rows = [row for row in questions(c) if row['question_key'] == original['question_key']]
    assert len(rows) == 1 and not rows[0]['is_latest']


def test_saved_old_unit_failure_is_recomputed_for_display(system):
    c, path = system
    q = questions(c)[0]['data']
    q.update(options=['12分钟', '15分钟', '18分钟'], answer=1, expression='6/(3-1)*(6-1)')
    created = c.post('/api/math/parent/questions', headers=PARENT, json=q).json()
    with sqlite3.connect(path) as db:
        db.execute('UPDATE questions SET checks=? WHERE id=?', (json.dumps({'passed':False, 'errors':['计算表达式或数字答案无效']}), created['id']))
    row = next(row for row in questions(c) if row['id'] == created['id'])
    assert row['checks']['passed'] and row['status'] == 'draft'
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM audit WHERE subject=? AND action='check'", (created['id'],)).fetchone()[0] == 0


def test_reject_invalid_calculation_does_not_run_check(system):
    c, path = system
    q = questions(c)[0]['data']
    q['expression'] = '1/0'
    created = c.post('/api/math/parent/questions', headers=PARENT, json=q).json()
    result = c.post(f"/api/math/parent/questions/{created['id']}/reject", headers=PARENT, json={'comment':'条件不清楚'})
    assert result.status_code == 200, result.text
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT checks,status FROM questions WHERE id=?", (created['id'],)).fetchone() == (None, 'deleted')
        assert db.execute("SELECT COUNT(*) FROM audit WHERE subject=? AND action='check'", (created['id'],)).fetchone()[0] == 0
