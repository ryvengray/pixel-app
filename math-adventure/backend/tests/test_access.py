import secrets
import sqlite3
import pytest
from fastapi.testclient import TestClient
from app.access import Access
from app.main import create_app

@pytest.fixture
def system(tmp_path,monkeypatch):
    monkeypatch.setenv('MAIL_USERNAME','sender@example.test');monkeypatch.setenv('MAIL_PASSWORD','fake-test-only')
    monkeypatch.setenv('MATH_COOKIE_SECURE','false');monkeypatch.delenv('MATH_PARENT_EMAILS',raising=False)
    sent={};monkeypatch.setattr(Access,'send_mail',lambda self,email,code,hashed:sent.update({email:code}))
    path=tmp_path/'accounts.db'
    with TestClient(create_app(path,'parent-test-secret-at-least-32-chars')) as c:yield c,path,sent

def login(c,sent,email):
    c.cookies.clear();assert c.post('/api/math/auth/code',json={'email':email}).status_code==200
    r=c.post('/api/math/auth/verify',json={'email':email,'code':sent[email]});assert r.status_code==200,r.text
    assert 'HttpOnly' in r.headers['set-cookie'];token=c.cookies.get('math_parent_session');c.cookies.clear()
    return {'Cookie':'math_parent_session='+token,'X-Math-Request':'1'}

def test_users_isolated(system):
    c,path,sent=system;a=login(c,sent,'a@example.test');b=login(c,sent,'b@example.test')
    qa=c.get('/api/math/parent/questions',headers=a).json();qb=c.get('/api/math/parent/questions',headers=b).json()
    assert len(qa)==len(qb)==10;assert {q['id'] for q in qa}.isdisjoint(q['id'] for q in qb)
    assert c.post('/api/math/parent/questions/'+qa[0]['id']+'/check',headers=b).status_code==404
    assert c.get('/api/math/auth/session',headers=a).json()['email']=='a@example.test'
    assert c.post('/api/math/parent/devices',headers=a,json={'name':'A的设备','mode':'practice'}).status_code==201
    da=c.get('/api/math/parent/devices',headers=a).json();assert len(da)==1
    assert c.get('/api/math/parent/devices',headers=b).json()==[]
    assert c.post('/api/math/parent/devices/'+da[0]['id']+'/revoke',headers=b).status_code==404
    c.patch('/api/math/parent/player',headers=a,json={'nickname':'A的小勇士'})
    assert c.get('/api/math/parent/dashboard',headers=b).json()['player']['nickname']!='A的小勇士'
    with sqlite3.connect(path) as db:
        assert db.execute('SELECT COUNT(*) FROM users').fetchone()[0]==2
        assert not db.execute('PRAGMA foreign_key_check').fetchall()

def test_otp_limit_and_session_logout(system):
    c,path,sent=system;email='codes@example.test'
    c.post('/api/math/auth/code',json={'email':email});correct=sent[email];wrong='000000' if correct!='000000' else '000001'
    assert c.post('/api/math/auth/code',json={'email':email}).status_code==429
    for _ in range(5):assert c.post('/api/math/auth/verify',json={'email':email,'code':wrong}).status_code==401
    assert c.post('/api/math/auth/verify',json={'email':email,'code':correct}).status_code==401
    a=login(c,sent,'logout@example.test')
    assert c.post('/api/math/parent/tasks/generate',headers={'Cookie':a['Cookie']}).status_code==403
    assert c.post('/api/math/auth/logout',headers=a).status_code==200
    assert c.get('/api/math/auth/session',headers=a).status_code==401
    assert c.post('/api/math/auth/verify',json={'email':'logout@example.test','code':sent['logout@example.test']}).status_code==401

def test_pair_confirmation_and_scopes(system):
    c,path,sent=system;a=login(c,sent,'owner@example.test');b=login(c,sent,'other@example.test')
    p=c.post('/api/math/parent/pairing',headers=a,json={'mode':'practice'}).json()
    assert len(p['code'])==4 and p['code'].isdigit();secret=secrets.token_hex(32)
    claim={'secret':secret,'name':'小语板子','code':p['code']};r=c.post('/api/math/pair/claim',json=claim)
    assert r.status_code==200 and r.json()['state']=='claimed';auth={'Authorization':'Bearer '+secret}
    assert c.get('/api/math/device/state',headers=auth).status_code==401
    status=c.get('/api/math/parent/pairing/'+p['id'],headers=a).json();ticket=status['devices'][0]['digest']
    assert status['devices'][0]['verification']==r.json()['verification']
    assert c.get('/api/math/parent/pairing/'+p['id'],headers=b).status_code==404
    assert c.post('/api/math/parent/pairing/'+p['id']+'/confirm',headers=b,json={'ticket':ticket}).status_code==409
    assert c.post('/api/math/parent/pairing/'+p['id']+'/confirm',headers=a,json={'ticket':ticket}).status_code==200
    assert c.get('/api/math/pair/status',headers=auth).json()['state']=='approved'
    assert c.get('/api/math/device/state',headers=auth).json()['mode']=='practice'
    assert c.get('/api/math/parent/dashboard?mode=practice',headers=b).json()['tasks']==[]
    assert c.get('/api/math/parent/questions',headers=auth).status_code==401
    assert c.post('/api/math/parent/pairing/'+p['id']+'/confirm',headers=a,json={'ticket':ticket}).status_code==409
    assert c.post('/api/math/pair/claim',json={**claim,'secret':secrets.token_hex(32)}).status_code==400

def test_bad_pair_codes_are_limited(system):
    c,path,sent=system;secret=secrets.token_hex(32)
    for _ in range(5):assert c.post('/api/math/pair/claim',json={'secret':secret,'name':'board','code':'0000'}).status_code==400
    assert c.post('/api/math/pair/claim',json={'secret':secret,'name':'board','code':'0000'}).status_code==429

def test_pair_expiry_and_cancel(system):
    c,path,sent=system;a=login(c,sent,'expires@example.test');p=c.post('/api/math/parent/pairing',headers=a,json={}).json()
    c.post('/api/math/parent/pairing',headers=a,json={})
    assert c.get('/api/math/parent/pairing/'+p['id'],headers=a).json()['state']=='cancelled'
    with sqlite3.connect(path) as db:db.execute('UPDATE pair_codes SET expires=0')
    assert c.post('/api/math/pair/claim',json={'secret':secrets.token_hex(32),'name':'board','code':p['code']}).status_code==400

def test_old_database_upgrade_preserves_records(tmp_path):
    from app.store import Store,encode,now
    from app.samples import RAW
    path=tmp_path/'legacy.db';store=Store(path);store.setup()
    with store.connect() as db:
        db.execute("INSERT INTO players(id,nickname,mode,xp,coins) VALUES('live','原角色','live',77,11)")
        db.execute("INSERT INTO questions(id,question_key,version,data,created_at) VALUES('old-q','old',1,?,?)",(encode({'legacy':True}),now()))
        db.execute("INSERT INTO tasks(id,player_id,day,question_id,ordinal,created_at) VALUES('old-task','live','2000-01-01','old-q',0,?)",(now(),))
    with TestClient(create_app(path,'legacy-parent-secret-at-least-32-chars')) as c:
        headers={'Authorization':'Bearer legacy-parent-secret-at-least-32-chars'}
        assert c.get('/api/math/parent/dashboard',headers=headers).json()['player']['xp']==77
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT owner FROM players WHERE id='live'").fetchone()[0]=='administrator'
        assert db.execute("SELECT question_id FROM tasks WHERE id='old-task'").fetchone()[0]=='old-q'
        assert db.execute('PRAGMA foreign_key_check').fetchall()==[]
