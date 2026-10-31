import asyncio
import importlib
import io
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
from PIL import Image

sys.path.insert(0,str(Path(__file__).parents[1]))
gallery=importlib.import_module('server.app.ink_gallery')
ink=importlib.import_module('server.app.ink')


def test_rotation_slots():
    zone=ZoneInfo('Asia/Shanghai')
    assert gallery.slot_at(datetime(2026,10,31,5,59,tzinfo=zone)) is None
    for hour,slot in [(6,6),(13,6),(14,14),(19,14),(20,20),(23,20)]:
        assert gallery.slot_at(datetime(2026,10,31,hour,tzinfo=zone))==f'2026-10-31T{slot:02d}:00'


def test_gallery_rotation_persists_and_retries(monkeypatch,tmp_path):
    monkeypatch.setenv('INK_GALLERY_DIR',str(tmp_path));monkeypatch.setenv('INK_API_BASE','http://example');monkeypatch.setenv('INK_TOKEN','test')
    for color in ['red','blue']:
        buffer=io.BytesIO();Image.new('RGB',(100,100),color).save(buffer,format='PNG');gallery.save(buffer.getvalue())
    (tmp_path/'broken.png').write_bytes(b'broken')
    (tmp_path/'escape.jpg').symlink_to(tmp_path/'broken.png')
    assert len(gallery.photos())==3
    async def content():return ink.Today()
    sent=[]
    async def forward(device,raw):sent.append(raw);return {'version':'test'}
    monkeypatch.setattr(ink,'today_data',content);monkeypatch.setattr(ink,'render_today',lambda data:data.background.encode());monkeypatch.setattr(ink,'forward',forward)
    zone=ZoneInfo('Asia/Shanghai');morning=datetime(2026,10,31,6,tzinfo=zone);afternoon=morning.replace(hour=14)
    first=asyncio.run(gallery.rotate(morning))
    assert first['background']
    assert asyncio.run(gallery.rotate(morning))['skipped']=='already sent'
    async def fail(*args):raise RuntimeError('offline')
    monkeypatch.setattr(ink,'forward',fail)
    import pytest
    with pytest.raises(RuntimeError):asyncio.run(gallery.rotate(afternoon))
    monkeypatch.setattr(ink,'forward',forward)
    second=asyncio.run(gallery.rotate(afternoon))
    assert second['background']!=first['background']
    assert len(sent)==2
