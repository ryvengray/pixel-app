import asyncio
import base64
import io
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).parents[1]))
from server.app import ink, ink_gallery as gallery
from server.app.ink_letter import daily_letter, entry, puzzle
from server.app.ink_letter_render import COLORS


def test_daily_letters_and_previous_answer_for_a_year():
    start = date(2026, 11, 9)
    layouts, themes = set(), set()
    for offset in range(366):
        day = start + timedelta(days=offset)
        letter = daily_letter(day)
        assert letter == daily_letter(day)
        assert letter.echo == entry(day - timedelta(days=1))['answer']
        assert ('谜底' in letter.echo_title) == ((day - timedelta(days=1)).weekday() in (0, 3))
        layouts.add(letter.layout)
        themes.add(letter.theme)
        if day.weekday() in (0, 3):
            assert letter.body == puzzle(day)[1]
        if day.weekday() == 5:
            # The weekly story uses the same destination on Friday and Saturday.
            assert letter.title.replace('的回应', '') in entry(day - timedelta(days=1))['body']
    assert layouts == {'orbit', 'postcard', 'notebook'}
    assert themes == {'space', 'animal', 'music'}


def test_generated_content_fits_without_truncation():
    import os
    font_path = os.environ.get('TEST_FONT', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
    draw = ImageDraw.Draw(Image.new('RGB', (800, 480)))
    def lines(value, size, width):
        font = ImageFont.truetype(font_path, size)
        result = ['']
        for char in value:
            if char == '\n' or draw.textlength(result[-1] + char, font=font) > width:
                result.append('' if char == '\n' else char)
            else:
                result[-1] += char
        return len(result)
    for offset in range(366):
        letter = daily_letter(date(2026,11,9) + timedelta(days=offset))
        assert lines(letter.title, 27, 456) == 1
        assert lines(letter.body, 22, 456) <= 5, letter
        assert lines(letter.invitation, 17, 456) <= 2
        assert lines(letter.echo, 18, 738) <= 2


@pytest.mark.parametrize('layout', ['orbit', 'postcard', 'notebook'])
@pytest.mark.parametrize('theme', ['space', 'animal', 'music'])
def test_letter_render_palette_and_photographs(monkeypatch, layout, theme):
    import os
    monkeypatch.setenv('INK_FONT_PATH', os.environ.get('TEST_FONT', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'))
    letter = daily_letter(date(2026,11,9)).model_copy(update={'layout':layout, 'theme':theme})
    for photograph in (False, True):
        background = ''
        if photograph:
            buffer = io.BytesIO()
            Image.new('RGB', (800,480), '#336699').save(buffer, format='PNG')
            background = base64.b64encode(buffer.getvalue()).decode()
        image = Image.open(io.BytesIO(ink.render_today(ink.Today(letter=letter, background=background))))
        assert image.size == (800,480)
        assert set(image.getdata()) <= set(COLORS)
        assert len(image.getcolors(800*480)) > 2
        assert image.getpixel((300,360)) == (255,255,255)


def test_daily_default_schedule_and_empty_gallery(monkeypatch, tmp_path):
    monkeypatch.delenv('INK_REFRESH_HOURS', raising=False)
    monkeypatch.setenv('INK_GALLERY_DIR', str(tmp_path))
    monkeypatch.setenv('INK_API_BASE', 'http://example')
    monkeypatch.setenv('INK_TOKEN', 'test')
    zone = ZoneInfo('Asia/Shanghai')
    assert gallery.schedule() == ['06:00']
    for hour in (6, 14, 20, 23):
        assert gallery.slot_at(datetime(2026,11,9,hour,tzinfo=zone)) == '2026-11-09T06:00'
    async def content():
        return ink.Today(letter=daily_letter(date(2026,11,9)), use_gallery=False)
    sent = []
    async def forward(device, raw):
        sent.append(raw)
        return {'version':'test'}
    monkeypatch.setattr(ink, 'today_data', content)
    monkeypatch.setattr(ink, 'render_today', lambda data: data.letter.title.encode())
    monkeypatch.setattr(ink, 'forward', forward)
    morning = datetime(2026,11,9,6,tzinfo=zone)
    assert asyncio.run(gallery.rotate(morning))['version'] == 'test'
    assert asyncio.run(gallery.rotate(morning.replace(hour=20)))['skipped'] == 'already sent'
    assert len(sent) == 1
    assert asyncio.run(gallery.rotate(morning+timedelta(days=1)))['version'] == 'test'
    assert len(sent) == 2


def test_preview_and_send_use_same_gallery_picture(monkeypatch, tmp_path):
    monkeypatch.setenv('INK_GALLERY_DIR', str(tmp_path))
    buffer = io.BytesIO()
    Image.new('RGB', (800,480), 'red').save(buffer, format='PNG')
    gallery.save(buffer.getvalue())
    rendered = []
    def render(data):
        rendered.append(data.background)
        return b'png'
    async def forward(*args):
        return {'version':'test'}
    monkeypatch.setattr(ink, 'render_today', render)
    monkeypatch.setattr(ink, 'forward', forward)
    payload = ink.Today(letter=daily_letter(date(2026,11,9)))
    ink.preview(payload)
    asyncio.run(ink.today('desk-01', payload))
    assert rendered[0] and rendered[0] == rendered[1]
    rendered.clear()
    payload.use_gallery = False
    ink.preview(payload)
    asyncio.run(ink.today('desk-01', payload))
    assert rendered == ['', '']


def test_parent_feedback_persists_and_can_be_corrected(monkeypatch, tmp_path):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    monkeypatch.setenv('INK_GALLERY_DIR', str(tmp_path))
    app = FastAPI()
    app.include_router(ink.router)
    with TestClient(app) as client:
        url = '/v1/ink/letters/2026-11-09/feedback'
        assert client.get(url).json()['rating'] is None
        assert client.post(url, json={'rating':'like'}).status_code == 200
        assert client.get(url).json()['rating'] == 'like'
        assert client.post(url, json={'rating':'easy'}).status_code == 200
        assert client.get(url).json()['rating'] == 'easy'
        assert client.post(url, json={'rating':'unknown'}).status_code == 422
        assert client.get('/v1/ink/letters/not-a-date/feedback').status_code == 422
    assert ink.get_letter_feedback(date(2026,11,9))['rating'] == 'easy'


@pytest.mark.parametrize('enabled, expected', [('false', 0), ('true', 1)])
def test_reminders_do_not_trigger_extra_sends_by_default(monkeypatch, enabled, expected):
    monkeypatch.setenv('INK_REFRESH_ON_REMINDER', enabled)
    sent = []
    async def rotate():
        return {'skipped':'already sent'}
    async def refresh():
        sent.append(True)
    class Event:
        def is_set(self): return True
        def clear(self): pass
        def wait(self, timeout): raise RuntimeError('end single loop iteration')
    monkeypatch.setattr(gallery, 'rotate', rotate)
    monkeypatch.setattr(gallery, 'refresh_content', refresh)
    monkeypatch.setattr(gallery, '_content_changed', Event())
    with pytest.raises(RuntimeError, match='end single loop iteration'):
        asyncio.run(gallery.loop())
    assert len(sent) == expected
