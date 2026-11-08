import io
import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image
from server.app import phone_control as pc
from server import phone_bridge as worker

P='/v1/phone-control'
A={'Authorization':'Bearer admin-'+'a'*32}
D={'Authorization':'Bearer device-'+'b'*32}


@pytest.fixture
def client(monkeypatch,tmp_path):
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN','admin-'+'a'*32)
    monkeypatch.setenv('PHONE_CONTROL_DEVICE_TOKEN','device-'+'b'*32)
    monkeypatch.setenv('PHONE_CONTROL_DB_PATH',str(tmp_path/'control.db'))
    app=FastAPI();app.include_router(pc.router)
    return TestClient(app)


def bridge(c,boot='worker-1',**kwargs):
    payload={'boot_id':boot,'connected':True};payload.update(kwargs)
    r=c.post(P+'/bridge/poll',headers=A,json=payload);assert r.status_code==200
    return r.json()


def frame(c,size=(100,200),color='red'):
    buf=io.BytesIO();Image.new('RGB',size,color).save(buf,'PNG')
    r=c.post(P+'/screenshots',headers=A,files={'file':('screen.png',buf.getvalue(),'image/png')})
    assert r.status_code==200;return r.json()['version']


def tap(c,version,id='touch-0001',action=None):
    return c.post(P+'/touch',headers=A,json={'id':id,'version':version,'action':action or {'type':'tap','x':20,'y':30}})


def test_watch_and_worker_authentication(client,monkeypatch):
    assert client.post(P+'/screen/watch').status_code==401
    assert client.post(P+'/bridge/poll',headers=D,json={}).status_code==401
    from server.app import ink
    monkeypatch.setattr(ink,'valid_session',lambda value:value=='operator')
    client.cookies.set(ink.SESSION_COOKIE,'operator')
    assert client.post(P+'/screen/watch').status_code==200
    assert client.post(P+'/bridge/poll',json={}).status_code==401
    assert bridge(client)['capture'] is True
    with pc.connect() as db:db.execute('UPDATE screen_view SET until=0')
    assert bridge(client)['capture'] is False


def test_touch_requires_live_bridge_and_recent_frame(client):
    version=frame(client)
    assert tap(client,version).status_code==409
    bridge(client)
    with pc.connect() as db:db.execute('UPDATE screen_frame SET received=?',(time.time()-6,))
    assert tap(client,version).status_code==409
    version=frame(client)
    assert tap(client,version).status_code==200
    assert client.get(P+'/status',headers=A).json()['bridge']['device']['connected'] is True


def test_coordinates_and_rotation(client):
    bridge(client);version=frame(client)
    for action in [{'type':'tap','x':100,'y':0},{'type':'tap','x':0,'y':200},{'type':'tap','x':-1,'y':1},
                   {'type':'swipe','x1':10,'y1':100,'x2':20,'y2':200},
                   {'type':'swipe','x1':10,'y1':100,'x2':20,'y2':50,'duration_ms':0}]:
        assert tap(client,version,action=action).status_code==422
    frame(client,size=(200,100))
    assert tap(client,version).status_code==409


def test_recent_previous_frame_is_accepted(client):
    bridge(client);previous=frame(client);frame(client,color='blue')
    assert tap(client,previous).status_code==200


def test_touch_delivery_ack_no_replay_and_idempotency(client):
    bridge(client);version=frame(client)
    assert tap(client,version).status_code==200
    assert tap(client,version).status_code==200
    assert tap(client,version,action={'type':'tap','x':21,'y':30}).status_code==409
    command=bridge(client)['command'];assert command['width']==100 and command['height']==200
    assert bridge(client)['command'] is None
    bridge(client,ack={'id':command['id'],'result':'ok'})
    r=client.get(P+'/touch/'+command['id'],headers=A)
    assert r.json()['state']=='succeeded'


def test_touch_single_dispatch_across_concurrent_polls(client):
    bridge(client);version=frame(client);tap(client,version)
    with ThreadPoolExecutor(2) as pool:
        results=list(pool.map(lambda _:bridge(client)['command'],range(2)))
    assert sum(value is not None for value in results)==1


def test_bridge_restart_and_expiry_do_not_replay(client):
    bridge(client);version=frame(client);tap(client,version);bridge(client)
    tap(client,version,id='touch-0002')
    assert bridge(client,boot='worker-2')['command'] is None
    assert client.get(P+'/touch/touch-0001',headers=A).json()['state']=='unknown'
    assert client.get(P+'/touch/touch-0002',headers=A).json()['state']=='cancelled'
    tap(client,version,id='touch-0003')
    with pc.connect() as db:db.execute('UPDATE touch_command SET expires=0 WHERE id=?',('touch-0003',))
    assert bridge(client,boot='worker-2')['command'] is None
    assert client.get(P+'/touch/touch-0003',headers=A).json()['state']=='expired'


def test_worker_verifies_screen_and_uses_fixed_argv(monkeypatch):
    calls=[]
    monkeypatch.setattr(worker,'screenshot',lambda *args:(b'',(100,200)))
    monkeypatch.setattr(worker,'adb_run',lambda *args,**kwargs:calls.append((args,kwargs)))
    job={'width':100,'height':200,'action':{'type':'tap','x':20,'y':30}}
    assert worker.execute_touch('adb','pixel',job)=='ok'
    assert calls[0][0]==('adb','pixel','shell','input','tap',20,30)
    calls.clear();job['height']=100
    assert worker.execute_touch('adb','pixel',job)=='screen_changed' and not calls
    job['height']=200;job['action']={'type':'tap','x':100,'y':30}
    assert worker.execute_touch('adb','pixel',job)=='invalid_coordinates' and not calls
    job['action']={'type':'swipe','x1':50,'y1':140,'x2':50,'y2':60,'duration_ms':400}
    assert worker.execute_touch('adb','pixel',job)=='ok'
    assert calls[0][0]==('adb','pixel','shell','input','swipe',50,140,50,60,400)
