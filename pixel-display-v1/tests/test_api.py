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
        assert len(img.content) == 48000
        assert hashlib.sha256(img.content).hexdigest() == version
        assert c.post('/v1/devices/a/ack', headers=auth, json={'version':version,'status':'displayed'}).status_code == 200
        assert c.get('/v1/devices/a/desired', headers=auth).json()['displayed_version'] == version
        assert c.post('/v1/devices/a/ack', headers=auth, json={'version':'old','status':'displayed'}).status_code == 409
