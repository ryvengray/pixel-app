import io
import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image

from server.app import phone_control as pc


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv('PHONE_CONTROL_DB_PATH', str(tmp_path/'control.db'))
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN', 'admin-'+'a'*32)
    monkeypatch.setenv('PHONE_CONTROL_DEVICE_TOKEN', 'device-'+'b'*32)
    app = FastAPI()
    app.include_router(pc.router)
    app.add_api_route('/esp32-screen', pc.page, methods=['GET'])
    return TestClient(app)


A = {'Authorization': 'Bearer admin-'+'a'*32}
D = {'Authorization': 'Bearer device-'+'b'*32}
P = '/v1/phone-control'


def poll(c, boot='boot1', **kwargs):
    payload = dict(boot_id=boot, armed=True, ble_connected=True)
    payload.update(kwargs)
    return c.post(P+'/device/poll', headers=D, json=payload)


def command(c, id='command-0001', action=None):
    return c.post(P+'/commands', headers=A, json=dict(id=id, action=action or {'type':'click'}))


def test_disabled_and_role_separation(client, monkeypatch):
    assert client.get(P+'/status').status_code == 401
    assert client.get(P+'/status', headers=D).status_code == 401
    assert client.post(P+'/device/poll', headers=A, json={}).status_code == 401
    monkeypatch.delenv('PHONE_CONTROL_DEVICE_TOKEN')
    assert poll(client).status_code == 503


def test_not_ready_and_action_validation(client):
    assert command(client).status_code == 409
    poll(client, armed=False)
    assert command(client).status_code == 409
    poll(client)
    for action in [{'type':'move','dx':128}, {'type':'text','text':'你好'},
                   {'type':'click','x':12,'y':34}, {'type':'shell','cmd':'anything'},
                   {'type':'move','dx':'2'}, {'type':'key','usage':0}]:
        assert command(client, action=action).status_code == 422


def test_idempotency_delivery_and_ack(client):
    poll(client)
    assert command(client).status_code == 200
    assert command(client).status_code == 200
    assert command(client, action={'type':'move','dx':2}).status_code == 409
    first = poll(client).json()['command']
    assert first['id'] == 'command-0001'
    assert poll(client).json()['command'] is None  # lost response never repeats input
    poll(client, ack={'id':first['id'], 'result':'ok'})
    result = client.get(P+'/commands/'+first['id'], headers=A).json()
    assert result['state'] == 'succeeded'
    assert command(client).json()['state'] == 'succeeded'


def test_reboot_discards_stale_commands(client):
    poll(client)
    command(client)
    poll(client)
    command(client, id='command-0002')
    assert poll(client, boot='new-boot').json()['command'] is None
    assert client.get(P+'/commands/command-0001', headers=A).json()['state']=='unknown'
    assert client.get(P+'/commands/command-0002', headers=A).json()['state']=='cancelled'
    # A late ack from another boot cannot claim success.
    poll(client, boot='new-boot', ack={'id':'command-0001','result':'ok'})
    assert client.get(P+'/commands/command-0001', headers=A).json()['state']=='unknown'


def test_expiry_and_queue_cancellation(client):
    poll(client)
    command(client)
    with pc.connect() as db:
        db.execute('UPDATE command SET expires=?', (time.time()-1,))
    assert poll(client).json()['command'] is None
    assert client.get(P+'/commands/command-0001', headers=A).json()['state']=='expired'
    command(client, id='command-0002')
    assert client.post(P+'/commands/cancel', headers=A).json()['cancelled']==1
    assert poll(client).json()['command'] is None


def test_concurrent_polls_deliver_once(client):
    poll(client)
    command(client)
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(lambda _:poll(client).json()['command'], range(2)))
    assert sum(x is not None for x in results)==1


def test_pause_does_not_dispatch_and_status(client):
    poll(client)
    command(client)
    assert poll(client, armed=False).json()['command'] is None
    status = client.get(P+'/status', headers=A).json()
    assert status['online'] and status['device']['armed'] is False
    assert status['commands'][0]['state']=='cancelled'
    assert poll(client, ble_connected=False).json()['command'] is None


def test_screenshot_normalization_and_replacement(client):
    assert client.get(P+'/screenshots/latest', headers=A).status_code==404
    assert client.post(P+'/screenshots', headers=A, files={'file':('x.png',b'invalid','image/png')}).status_code==400
    assert client.post(P+'/screenshots', headers=A, files={'file':('x.png',b'x'*(4*1024*1024+1),'image/png')}).status_code==413
    for size in [(100,200),(200,100)]:
        buf=io.BytesIO();Image.new('RGB',size,'red').save(buf,'PNG')
        response=client.post(P+'/screenshots', headers=A, files={'file':('screen.png',buf.getvalue(),'image/png')})
        assert response.status_code==200
        meta=response.json()
        assert (meta['width'],meta['height'])==size
        image=client.get(P+'/screenshots/latest', headers=A)
        assert image.headers['x-screenshot-version']==meta['version']
        assert Image.open(io.BytesIO(image.content)).size==size
    with pc.connect() as db:
        assert db.execute('SELECT count(*) FROM screenshot').fetchone()[0]==1


def test_control_page_and_existing_login_cookie(client, monkeypatch):
    from server.app import ink
    page = client.get('/esp32-screen')
    assert page.status_code == 200
    assert '确认并输入' in page.text and 'Pixel 蓝牙控制台' in page.text
    assert page.headers['cache-control'] == 'no-store'
    monkeypatch.setattr(ink, 'valid_session', lambda value: value == 'valid-cookie')
    client.cookies.set(ink.SESSION_COOKIE, 'valid-cookie')
    assert client.get(P+'/status').status_code == 200
    assert client.post(P+'/device/poll', json={}).status_code == 401
    monkeypatch.delenv('PHONE_CONTROL_ADMIN_TOKEN')
    assert client.get(P+'/status').status_code == 503


def test_web_typing_single_line_and_idempotency(client):
    poll(client)
    payload = {'id':'web-type-0001','text':'hello pixel 123'}
    assert client.post(P+'/type',headers=A,json=payload).status_code==200
    assert client.post(P+'/type',headers=A,json=payload).status_code==200
    item=poll(client).json()['command']
    assert item['action']=={'type':'text','text':'hello pixel 123'}
    assert poll(client).json()['command'] is None
    for text in ['你好','hello\n','hello\tworld','hello\rworld','', 'a'*161]:
        response=client.post(P+'/type',headers=A,json={'id':'web-type-0002','text':text})
        assert response.status_code==422
    assert client.post(P+'/type',headers=D,json=payload).status_code==401
