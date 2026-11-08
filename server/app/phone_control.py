"""Single Pixel / ESP32 bridge. Input delivery is at-most-once, never replayed."""
from __future__ import annotations

import hashlib
import io
import json
import os
import secrets
import sqlite3
import time
import warnings
from contextlib import contextmanager
from pathlib import Path
from typing import Annotated, Literal, Union

from fastapi import APIRouter, Depends, File, Header, HTTPException, UploadFile, Request
from fastapi.responses import Response, HTMLResponse
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, field_validator

router = APIRouter(prefix='/v1/phone-control', tags=['phone-control'])


def auth(env: str, authorization: str | None) -> None:
    expected = os.getenv(env, '')
    if len(expected) < 24:
        raise HTTPException(503, 'Phone control is disabled: configure separate admin/device tokens (24+ characters)')
    supplied = authorization.removeprefix('Bearer ') if authorization else ''
    if not secrets.compare_digest(supplied.encode(), expected.encode()):
        raise HTTPException(401, 'Invalid phone-control token')


def admin(request: Request, authorization: str | None = Header(default=None)) -> None:
    # The operator already uses the existing ink-screen TOTP login. Device
    # polling remains strictly token-only; this cookie is never sent to ESP32.
    from .ink import SESSION_COOKIE, valid_session
    if len(os.getenv('PHONE_CONTROL_ADMIN_TOKEN', '')) >= 24 and valid_session(request.cookies.get(SESSION_COOKIE, '')):
        return
    auth('PHONE_CONTROL_ADMIN_TOKEN', authorization)


def device(authorization: str | None = Header(default=None)) -> None:
    auth('PHONE_CONTROL_DEVICE_TOKEN', authorization)


@contextmanager
def connect():
    default = Path(os.getenv('DATABASE_PATH', str(Path(__file__).parents[1] / 'data/greta.db'))).parent / 'phone-control.db'
    path = Path(os.getenv('PHONE_CONTROL_DB_PATH', str(default)))
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=10)
    db.row_factory = sqlite3.Row
    try:
        db.executescript('''
          CREATE TABLE IF NOT EXISTS controller (
            id INTEGER PRIMARY KEY CHECK(id=1), boot_id TEXT NOT NULL,
            seen REAL NOT NULL, status TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS command (
            id TEXT PRIMARY KEY, boot_id TEXT NOT NULL, action TEXT NOT NULL,
            state TEXT NOT NULL, created REAL NOT NULL, expires REAL NOT NULL,
            dispatched REAL, completed REAL, result TEXT);
          CREATE INDEX IF NOT EXISTS command_state ON command(state,created);
          CREATE TABLE IF NOT EXISTS screenshot (
            id INTEGER PRIMARY KEY CHECK(id=1), version TEXT NOT NULL,
            received REAL NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
            content BLOB NOT NULL);
        ''')
        yield db
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()


class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)


class Move(Strict):
    type: Literal['move']
    dx: int = Field(default=0, ge=-127, le=127)
    dy: int = Field(default=0, ge=-127, le=127)
    wheel: int = Field(default=0, ge=-127, le=127)


class Click(Strict):
    type: Literal['click']
    button: Literal[1, 2, 4] = 1  # left, right, middle


class Key(Strict):
    type: Literal['key']
    usage: int = Field(ge=4, le=101)
    modifiers: int = Field(default=0, ge=0, le=255)


class Text(Strict):
    type: Literal['text']
    text: str = Field(min_length=1, max_length=160, pattern=r'^[\x20-\x7e\n\t]+$')


class Release(Strict):
    type: Literal['release']


Action = Annotated[Union[Move, Click, Key, Text, Release], Field(discriminator='type')]


class Command(Strict):
    id: str = Field(min_length=8, max_length=64, pattern=r'^[A-Za-z0-9_-]+$')
    action: Action
    ttl_seconds: int = Field(default=15, ge=1, le=60)


class TypeRequest(Strict):
    id: str = Field(min_length=8, max_length=64, pattern=r'^[A-Za-z0-9_-]+$')
    text: str = Field(min_length=1, max_length=160)

    @field_validator('text')
    @classmethod
    def single_line_ascii(cls, value):
        if any(ord(char)<32 or ord(char)>126 for char in value):
            raise ValueError('目前仅支持单行英文、数字和英文标点，不包含回车或制表符')
        return value


class Ack(Strict):
    id: str = Field(min_length=1, max_length=64)
    result: str = Field(min_length=1, max_length=100)


class Status(Strict):
    boot_id: str = Field(min_length=1, max_length=64)
    ble_connected: bool
    armed: bool
    ip: str = Field(default='', max_length=64)
    heap: int = Field(default=0, ge=0)
    uptime_ms: int = Field(default=0, ge=0)
    firmware: str = Field(default='', max_length=32)
    logs: list[Annotated[str, Field(max_length=100)]] = Field(default_factory=list, max_length=6)
    ack: Ack | None = None


def expire(db, now):
    # Never infer success from timeout, and never retry side effects.
    db.execute("UPDATE command SET state=CASE WHEN state='queued' THEN 'expired' ELSE 'unknown' END WHERE state IN ('queued','dispatched') AND expires<?", (now,))


@router.post('/device/poll', dependencies=[Depends(device)])
def poll(status: Status):
    now = time.time()
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        old = db.execute('SELECT boot_id FROM controller WHERE id=1').fetchone()
        if old and old['boot_id'] != status.boot_id:
            db.execute("UPDATE command SET state=CASE WHEN state='queued' THEN 'cancelled' ELSE 'unknown' END WHERE state IN ('queued','dispatched')")
        db.execute('INSERT INTO controller VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET boot_id=excluded.boot_id,seen=excluded.seen,status=excluded.status',
                   (status.boot_id, now, status.model_dump_json(exclude={'ack'})))
        if status.ack:
            db.execute("UPDATE command SET state=?,completed=?,result=? WHERE id=? AND boot_id=? AND state IN ('dispatched','unknown')",
                       ('succeeded' if status.ack.result == 'ok' else 'failed', now, status.ack.result, status.ack.id, status.boot_id))
        expire(db, now)
        if not status.armed or not status.ble_connected:
            db.execute("UPDATE command SET state='cancelled' WHERE state='queued'")
        command = None
        if status.armed and status.ble_connected:
            # One in flight; a lost HTTP response becomes unknown, never replayed.
            active = db.execute("SELECT 1 FROM command WHERE state='dispatched'").fetchone()
            if not active:
                row = db.execute("SELECT * FROM command WHERE state='queued' AND boot_id=? ORDER BY created,id LIMIT 1", (status.boot_id,)).fetchone()
                if row:
                    db.execute("UPDATE command SET state='dispatched',dispatched=? WHERE id=?", (now, row['id']))
                    command = {'id': row['id'], 'action': json.loads(row['action'])}
        return {'command': command, 'server_time': now}


@router.post('/commands', dependencies=[Depends(admin)])
def enqueue(request: Command):
    now = time.time()
    payload = json.dumps(request.action.model_dump(), sort_keys=True)
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        expire(db, now)
        existing = db.execute('SELECT * FROM command WHERE id=?', (request.id,)).fetchone()
        if existing:
            if existing['action'] != payload:
                raise HTTPException(409, 'Command id already used with a different action')
            result = dict(existing)
            result['action'] = json.loads(result['action'])
            return result
        controller = db.execute('SELECT * FROM controller WHERE id=1').fetchone()
        if not controller or now-controller['seen'] > 5:
            raise HTTPException(409, 'ESP32 is offline')
        state = json.loads(controller['status'])
        if not state['armed'] or not state['ble_connected']:
            raise HTTPException(409, 'Pair Bluetooth and press BOOT to arm the ESP32')
        count = db.execute("SELECT count(*) FROM command WHERE state IN ('queued','dispatched')").fetchone()[0]
        if count >= 20:
            raise HTTPException(429, 'Command queue full')
        db.execute("INSERT INTO command(id,boot_id,action,state,created,expires) VALUES(?,?,?,'queued',?,?)",
                   (request.id, controller['boot_id'], payload, now, now+request.ttl_seconds))
        return {'id': request.id, 'state': 'queued'}


@router.get('/commands/{command_id}', dependencies=[Depends(admin)])
def command_status(command_id: str):
    with connect() as db:
        expire(db, time.time())
        row = db.execute('SELECT * FROM command WHERE id=?', (command_id,)).fetchone()
        if not row:
            raise HTTPException(404, 'Command not found')
        item = dict(row)
        item['action'] = json.loads(item['action'])
        return item


@router.post('/type', dependencies=[Depends(admin)])
def type_text(request: TypeRequest):
    return enqueue(Command(id=request.id, action=Text(type='text', text=request.text), ttl_seconds=8))


def page():
    return HTMLResponse(Path(__file__).with_name('esp32_screen.html').read_text(encoding='utf-8'),
                        headers={'Cache-Control': 'no-store'})


@router.post('/commands/cancel', dependencies=[Depends(admin)])
def cancel():
    with connect() as db:
        count = db.execute("UPDATE command SET state='cancelled' WHERE state='queued'").rowcount
    return {'cancelled': count, 'note': 'Dispatched input cannot be recalled; use physical BOOT to pause'}


@router.get('/status', dependencies=[Depends(admin)])
def get_status():
    now = time.time()
    with connect() as db:
        expire(db, now)
        row = db.execute('SELECT * FROM controller WHERE id=1').fetchone()
        screen = db.execute('SELECT version,received,width,height FROM screenshot WHERE id=1').fetchone()
        recent = [dict(r) for r in db.execute('SELECT id,state,created,result FROM command ORDER BY created DESC LIMIT 20')]
    return {'online': bool(row and now-row['seen'] <= 5),
            'last_seen': row['seen'] if row else None,
            'device': json.loads(row['status']) if row else None,
            'screenshot': dict(screen) if screen else None, 'commands': recent}


@router.post('/screenshots', dependencies=[Depends(admin)])
async def upload(file: UploadFile = File(...)):
    try:
        raw = await file.read(4*1024*1024+1)
    finally:
        await file.close()
    if len(raw)>4*1024*1024:
        raise HTTPException(413, 'Screenshot exceeds 4 MiB')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as im:
                if im.format not in ('PNG', 'JPEG') or im.width*im.height>20_000_000:
                    raise ValueError('Unsupported screenshot')
                im.load()
                width, height = im.size
                buf = io.BytesIO()
                im.convert('RGB').save(buf, 'JPEG', quality=85)
                content = buf.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise HTTPException(400, 'Expected PNG/JPEG screenshot, at most 20 megapixels')
    version = hashlib.sha256(content).hexdigest()
    now = time.time()
    with connect() as db:
        db.execute('INSERT INTO screenshot VALUES(1,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,received=excluded.received,width=excluded.width,height=excluded.height,content=excluded.content',
                   (version, now, width, height, content))
    return {'version': version, 'received': now, 'width': width, 'height': height}


@router.get('/screenshots/latest', dependencies=[Depends(admin)])
def latest():
    with connect() as db:
        row = db.execute('SELECT * FROM screenshot WHERE id=1').fetchone()
    if not row:
        raise HTTPException(404, 'No screenshot received')
    return Response(row['content'], media_type='image/jpeg',
                    headers={'Cache-Control': 'no-store', 'X-Screenshot-Version': row['version'],
                             'X-Screenshot-Received': str(row['received'])})
