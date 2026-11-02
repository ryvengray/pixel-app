"""Authenticated, immutable ESP32-S3 application releases and per-device rollout."""
import hashlib
import re
import struct
import time
from fastapi import Depends, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

MAX_FIRMWARE_SIZE = 0x330000
BUILD_MARKER = re.compile(rb'INK_E1002_BUILD=([1-9][0-9]{0,8});')


def init(c):
    c.execute('CREATE TABLE IF NOT EXISTS firmware_build_reservations (build INTEGER PRIMARY KEY)')
    c.execute('CREATE TABLE IF NOT EXISTS firmware_releases (build INTEGER PRIMARY KEY, sha256 TEXT NOT NULL, image BLOB NOT NULL)')
    c.execute('CREATE TABLE IF NOT EXISTS firmware_devices (device_id TEXT PRIMARY KEY, target INTEGER, running_build INTEGER, status TEXT, last_seen INTEGER)')


def validate_image(data, build):
    if not 288 <= len(data) <= MAX_FIRMWARE_SIZE:
        raise HTTPException(400, 'invalid application size')
    if data[0] != 0xE9 or struct.unpack_from('<H', data, 12)[0] != 9 or struct.unpack_from('<I', data, 32)[0] != 0xABCD5432:
        raise HTTPException(400, 'expected ESP32-S3 application firmware.bin')
    if data[23] != 1 or hashlib.sha256(data[:-32]).digest() != data[-32:]:
        raise HTTPException(400, 'invalid ESP image digest')
    if set(BUILD_MARKER.findall(data)) != {str(build).encode()}:
        raise HTTPException(400, 'firmware build marker does not match release')


class FirmwareStatus(BaseModel):
    build: int = Field(ge=1, le=999999999)
    status: str = Field(pattern='^(running|failed)$')


def register(app, db, auth, check_id):
    @app.get('/v1/devices/{device_id}/firmware', dependencies=[Depends(auth)])
    def manifest(device_id: str):
        check_id(device_id)
        with db() as c:
            state = c.execute('SELECT target,running_build,status,last_seen FROM firmware_devices WHERE device_id=?', (device_id,)).fetchone()
            release = c.execute('SELECT build,sha256,length(image) AS size FROM firmware_releases WHERE build=?', (state['target'],)).fetchone() if state else None
        return {'target': 'e1002', 'release': dict(release) if release else None,
                'running_build': state['running_build'] if state else None,
                'status': state['status'] if state else None,
                'last_seen': state['last_seen'] if state else None}

    @app.get('/v1/devices/{device_id}/firmware.bin', dependencies=[Depends(auth)])
    def download(device_id: str, build: int):
        check_id(device_id)
        with db() as c:
            row = c.execute('SELECT r.image FROM firmware_releases r JOIN firmware_devices d ON d.target=r.build WHERE d.device_id=? AND r.build=?', (device_id, build)).fetchone()
        if not row:
            raise HTTPException(404, 'release not assigned to device')
        return Response(row['image'], media_type='application/octet-stream', headers={'Cache-Control': 'no-store'})

    @app.post('/v1/devices/{device_id}/firmware/status', dependencies=[Depends(auth)])
    def status(device_id: str, req: FirmwareStatus):
        check_id(device_id)
        with db() as c:
            c.execute('INSERT INTO firmware_devices(device_id,running_build,status,last_seen) VALUES(?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET running_build=excluded.running_build,status=excluded.status,last_seen=excluded.last_seen', (device_id, req.build, req.status, int(time.time())))
        return {'ok': True}
