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


def test_large_phone_photo_downsample_and_orientation():
    # Typical 48 MP phone photo, formerly rejected by the 24 MP limit.
    source=Image.new('RGB',(8000,6000),'red')
    exif=Image.Exif();exif[274]=6
    raw=io.BytesIO();source.save(raw,format='JPEG',exif=exif);source.close()
    normalized=Image.open(io.BytesIO(gallery.normalize(raw.getvalue())))
    assert normalized.size==(1800,2400)
    assert normalized.mode=='RGB'


def test_invalid_image_reports_format():
    import pytest
    from fastapi import HTTPException
    with pytest.raises(HTTPException) as error:gallery.normalize(b'not an image')
    assert error.value.status_code==400
    assert '格式' in error.value.detail


def test_today_weather_loads_and_reports_missing_config(monkeypatch):
    import sqlite3
    import types
    db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row
    db.execute('CREATE TABLE reminder(title TEXT,due_at TEXT,state TEXT)')
    monkeypatch.setitem(sys.modules,'server.app.main',types.SimpleNamespace(connect=lambda:db))
    monkeypatch.delenv('INK_LATITUDE',raising=False);monkeypatch.delenv('INK_LONGITUDE',raising=False)
    assert '未配置' in asyncio.run(ink.today_data()).weather
    monkeypatch.setenv('INK_LATITUDE','30.598593');monkeypatch.setenv('INK_LONGITUDE','103.920888')
    class Client:
        def __init__(self,**kwargs):pass
        async def __aenter__(self):return self
        async def __aexit__(self,*args):pass
        async def get(self,url,params):
            assert params['latitude']==30.598593
            return types.SimpleNamespace(raise_for_status=lambda:None,json=lambda:{'daily':{'temperature_2m_min':[17],'temperature_2m_max':[24],'precipitation_probability_max':[60],'weather_code':[3]}})
    monkeypatch.setattr(ink.httpx,'AsyncClient',Client)
    data=asyncio.run(ink.today_data())
    assert data.low==17 and data.high==24
    assert '多云' in data.weather and '60%' in data.weather
    assert '带伞' in data.clothing
    db.close()
