import importlib.util
import io
import os
from pathlib import Path
from PIL import Image
from fastapi.testclient import TestClient


def load(name, path):
    import sys
    sys.path.insert(0,str(Path(__file__).parents[1]))
    if str(path).endswith('server/app/ink.py'):name='server.app.'+name
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_today_canvas(monkeypatch):
    module = load('ink_canvas', Path(__file__).parents[1] / 'server/app/ink.py')
    font = os.environ.get('TEST_FONT', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
    monkeypatch.setenv('INK_FONT_PATH', font)
    content = module.render_today(module.Today(todos=['测试待办'] * 8))
    image = Image.open(io.BytesIO(content))
    assert image.size == (800, 480)
    assert len(image.getcolors(800*480)) > 2
    assert set(image.getdata()) <= {(255,255,255),(0,0,0),(255,0,0),(255,255,0),(0,255,0),(0,0,255)}


def test_upload_protocol(monkeypatch, tmp_path):
    path = os.environ.get('INK_SERVER_PATH')
    if not path:
        import pytest
        pytest.skip('Set INK_SERVER_PATH to ink-screen server/app.py')
    monkeypatch.setenv('DATA_DIR', str(tmp_path))
    monkeypatch.setenv('PIXEL_TOKEN', 'test')
    module = load('ink_upstream', path)
    for name in ['connect_async', 'loop_start', 'loop_stop', 'disconnect']:
        monkeypatch.setattr(module.client, name, lambda *a, **k: None)
    class Info:
        rc = 0
    monkeypatch.setattr(module.client, 'publish', lambda *a, **k: Info())
    headers = {'Authorization': 'Bearer test'}
    image = Image.new('RGBA', (100, 200), (255, 0, 0, 255))
    data = io.BytesIO(); image.save(data, format='PNG')
    with TestClient(module.app) as client:
        url = '/v1/devices/test/image'
        assert client.post(url, content=data.getvalue()).status_code == 401
        assert client.post(url, content=b'invalid', headers=headers).status_code == 400
        response = client.post(url, content=data.getvalue(), headers=headers)
        assert response.status_code == 200
        version = response.json()['version']
        packed = client.get('/v1/devices/test/bitmap', params={'version':version}, headers=headers).content
        assert len(packed) == 192000
        assert all(b >> 4 < 6 and b & 15 < 6 for b in packed)
        assert packed[0] == 0  # portrait image leaves white side margins
        assert packed[240*400+200] == 0x22  # center remains red


def test_background_photo_and_validation(monkeypatch):
    import base64
    import pytest
    from fastapi import HTTPException
    module = load('ink_photo', Path(__file__).parents[1] / 'server/app/ink.py')
    monkeypatch.setenv('INK_FONT_PATH', os.environ.get('TEST_FONT', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'))
    source = Image.new('RGB', (800,480), '#336699')
    buffer = io.BytesIO();source.save(buffer,format='PNG')
    background = 'data:image/png;base64,' + base64.b64encode(buffer.getvalue()).decode()
    result = Image.open(io.BytesIO(module.render_today(module.Today(background=background))))
    patch=result.crop((80,180,180,280))
    pixels=list(patch.getdata())
    average=tuple(sum(p[k] for p in pixels)/len(pixels) for k in range(3))
    assert all(abs(a-b)<20 for a,b in zip(average,(51,102,153)))  # dithering preserves average photo color
    assert all(c > 200 for c in result.getpixel((760,50)))  # readable glass card
    with pytest.raises(HTTPException) as exc:
        module.render_today(module.Today(background='invalid'))
    assert exc.value.status_code == 400
