import hashlib
import os
import tempfile
from pathlib import Path

# Run with the dependencies from server/requirements.txt plus httpx and pytest.
tmp = tempfile.TemporaryDirectory()
os.environ['DATA_DIR'] = tmp.name
os.environ['PIXEL_TOKEN'] = 'test-token'
os.environ['FONT_PATH'] = os.environ.get('FONT_PATH', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import app as module
from fastapi.testclient import TestClient


def test_render_and_api():
    # Avoid requiring a broker for the API test.
    module.client.connect_async = lambda *args, **kwargs: None
    module.client.loop_start = lambda: None
    module.client.loop_stop = lambda: None
    module.client.disconnect = lambda: None
    class Info: rc = 0
    module.client.publish = lambda *args, **kwargs: Info()
    with TestClient(module.app) as c:
        auth = {'Authorization':'Bearer test-token'}
        assert c.get('/v1/devices/a/desired').status_code == 401
        assert c.get('/v1/devices/a/desired', headers=auth).json()['version'] is None
        r = c.post('/v1/devices/a/display', headers=auth, json={'title':'你好','body':'Pixel\n测试'})
        assert r.status_code == 200
        version = r.json()['version']
        img = c.get('/v1/devices/a/bitmap', headers=auth, params={'version':version})
        assert len(img.content) == 192000
        assert all((byte >> 4) < 6 and (byte & 15) < 6 for byte in img.content)
        assert 2 in {n for byte in img.content for n in (byte >> 4, byte & 15)}
        assert hashlib.sha256(img.content).hexdigest() == version
        assert c.post('/v1/devices/a/ack', headers=auth, json={'version':version,'status':'displayed'}).status_code == 200
        assert c.get('/v1/devices/a/desired', headers=auth).json()['displayed_version'] == version
        assert c.post('/v1/devices/a/ack', headers=auth, json={'version':'old','status':'displayed'}).status_code == 409


def test_history_navigation_and_restart():
    auth = {'Authorization': 'Bearer test-token'}
    with TestClient(module.app) as c:
        assert c.get('/v1/devices/history-test/history').status_code == 401
        assert c.get('/v1/devices/history-test/history', headers=auth).json()['version'] is None
        versions = [c.post('/v1/devices/history-test/display', headers=auth,
                         json={'title': title}).json()['version'] for title in ('first', 'second', 'third')]
        latest = c.get('/v1/devices/history-test/history', headers=auth).json()
        assert latest['version'] == versions[2]
        previous = c.get('/v1/devices/history-test/history', headers=auth,
                         params={'cursor': latest['seq'], 'direction': 'previous'}).json()
        assert previous['version'] == versions[1]
        first = c.get('/v1/devices/history-test/history', headers=auth,
                      params={'cursor': previous['seq'], 'direction': 'previous'}).json()
        assert first['version'] == versions[0]
        assert c.get('/v1/devices/history-test/history', headers=auth,
                     params={'cursor': first['seq'], 'direction': 'previous'}).json() == first
        assert c.get('/v1/devices/history-test/history', headers=auth,
                     params={'cursor': first['seq'], 'direction': 'next'}).json() == previous
        assert c.get('/v1/devices/history-test/history', headers=auth,
                     params={'cursor': first['seq'], 'direction': 'latest'}).json() == latest
        image = c.get('/v1/devices/history-test/bitmap', headers=auth, params={'version':versions[0]})
        assert hashlib.sha256(image.content).hexdigest() == versions[0]
        assert c.get('/v1/devices/other/bitmap', headers=auth, params={'version':versions[0]}).status_code == 404
        assert c.post('/v1/devices/history-test/ack', headers=auth,
                      json={'version':versions[0], 'status':'displayed'}).status_code == 200
        state = c.get('/v1/devices/history-test/desired', headers=auth).json()
        assert state['version'] == versions[2] and state['displayed_version'] == versions[0]
        with module.db() as db:
            db.execute('INSERT INTO devices(id,version,bitmap,updated) VALUES(?,?,?,?)',
                       ('legacy', versions[0], image.content, 1))
    with TestClient(module.app) as c:
        assert c.get('/v1/devices/history-test/history', headers=auth).json() == latest
        assert c.get('/v1/devices/legacy/history', headers=auth).json()['version'] == versions[0]
