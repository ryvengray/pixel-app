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


def test_wake_schedule(monkeypatch):
    monkeypatch.setattr(module.client, 'connect_async', lambda *a, **kw: None)
    monkeypatch.setattr(module.client, 'loop_start', lambda: None)
    monkeypatch.setattr(module.client, 'loop_stop', lambda: None)
    monkeypatch.setattr(module.client, 'disconnect', lambda: None)
    monkeypatch.setattr(module.time, 'time', lambda: 1793577600)
    auth = {'Authorization': 'Bearer test-token'}
    path = '/v1/devices/schedule-test/schedule'
    with TestClient(module.app) as c:
        assert c.get(path).status_code == 401
        assert c.put(path, json={'times': ['08:00']}).status_code == 401
        default = c.get(path, headers=auth).json()
        assert default == {'timezone': 'Asia/Shanghai',
                           'times': ['06:30', '09:30', '12:30', '18:30', '22:30'],
                           'minutes': [390, 570, 750, 1110, 1350],
                           'server_time': 1793577600}
        for times in ([], ['24:00'], ['6:30'], ['12:60'], ['09:00', '09:00'], ['bad']):
            assert c.put(path, headers=auth, json={'times': times}).status_code == 422
        assert c.get(path, headers=auth).json() == default
        changed = c.put(path, headers=auth, json={'times': ['22:00', '07:00']})
        assert changed.status_code == 200
        assert changed.json()['minutes'] == [420, 1320]
        assert c.get('/v1/devices/another/schedule', headers=auth).json() == default
        assert c.get('/v1/devices/bad!/schedule', headers=auth).status_code == 400
    with TestClient(module.app) as c:
        assert c.get(path, headers=auth).json()['times'] == ['07:00', '22:00']


def test_ota_local_release_lifecycle(monkeypatch):
    monkeypatch.setattr(module.client, 'connect_async', lambda *a, **kw: None)
    monkeypatch.setattr(module.client, 'loop_start', lambda: None)
    monkeypatch.setattr(module.client, 'loop_stop', lambda: None)
    monkeypatch.setattr(module.client, 'disconnect', lambda: None)
    import struct
    import pytest
    from publish_firmware import publish
    from fastapi import HTTPException
    def image(build, extra=b''):
        data = bytearray(512)
        data[0] = 0xE9
        data[23] = 1
        struct.pack_into('<H', data, 12, 9)
        struct.pack_into('<I', data, 32, 0xABCD5432)
        marker = f'INK_E1002_BUILD={build};'.encode()
        data[288:288+len(marker)] = marker
        data.extend(extra)
        return bytes(data) + hashlib.sha256(data).digest()
    auth = {'Authorization': 'Bearer test-token'}
    path = '/v1/devices/ota-test/firmware'
    binary = image(2)
    with TestClient(module.app) as c:
        assert c.get(path).status_code == 401
        assert c.get(path+'.bin?build=2').status_code == 401
        assert c.post(path+'/status', json={'build': 1, 'status': 'running'}).status_code == 401
        # Publishing/upload routes are removed, even for authenticated callers.
        assert c.put('/v1/firmware/2', headers=auth, content=binary).status_code == 404
        assert c.put(path, headers=auth, json={'build': 2}).status_code == 405
        assert c.delete(path, headers=auth).status_code == 405
        assert c.get(path, headers=auth).json()['release'] is None
        with module.db() as db:
            for bad in (b'bad', binary[:-1]+b'x', image(3), bytes(module.ota.MAX_FIRMWARE_SIZE+1)):
                with pytest.raises(HTTPException): publish(db, 'ota-test', 2, bad)
            result = publish(db, 'ota-test', 2, binary)
            assert result['sha256'] == hashlib.sha256(binary).hexdigest()
            assert publish(db, 'ota-test', 2, binary) == result
            with pytest.raises(ValueError): publish(db, 'ota-test', 2, image(2, b'changed'))
            assert db.execute('SELECT sha256 FROM firmware_releases WHERE build=2').fetchone()[0] == result['sha256']
        assert c.get(path, headers=auth).json()['release']['build'] == 2
        assert c.get(path+'.bin?build=2', headers=auth).content == binary
        assert c.get('/v1/devices/other/firmware.bin?build=2', headers=auth).status_code == 404
        assert c.post(path+'/status', headers=auth, json={'build': 2, 'status': 'running'}).status_code == 200
        with module.db() as db:
            with pytest.raises(ValueError): publish(db, 'ota-test', 1, image(1))
            assert db.execute('SELECT 1 FROM firmware_releases WHERE build=1').fetchone() is None
    with TestClient(module.app) as c:
        assert c.get(path, headers=auth).json()['release']['build'] == 2
        assert c.get(path, headers=auth).json()['running_build'] == 2
        import subprocess
        result = subprocess.run([sys.executable, str(Path(module.__file__).parent/'publish_firmware.py'),
                                 '--device', 'ota-test', '--withdraw'], capture_output=True)
        assert result.returncode == 0, result.stderr
        assert c.get(path, headers=auth).json()['release'] is None
        assert c.get(path+'.bin?build=2', headers=auth).status_code == 404
