"""Local-only release writer, run in the api container by the Ubuntu script."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import ota


def publish(c, device, build, image):
    ota.validate_image(image, build)
    digest = hashlib.sha256(image).hexdigest()
    # Release and assignment commit together, including across concurrent writers.
    with c:
        c.execute('BEGIN IMMEDIATE')
        old = c.execute('SELECT sha256 FROM firmware_releases WHERE build=?', (build,)).fetchone()
        if old and old[0] != digest:
            raise ValueError('build already exists with different firmware; increment --build')
        state = c.execute('SELECT target,running_build FROM firmware_devices WHERE device_id=?', (device,)).fetchone()
        if state and any(v is not None and build < v for v in state):
            raise ValueError('build is older than the device target or running version')
        c.execute('INSERT OR IGNORE INTO firmware_releases(build,sha256,image) VALUES(?,?,?)', (build, digest, image))
        c.execute('INSERT INTO firmware_devices(device_id,target) VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET target=excluded.target', (device, build))
    return {'device': device, 'build': build, 'sha256': digest, 'size': len(image)}


def reserve_build(c, minimum=1):
    with c:
        c.execute('BEGIN IMMEDIATE')
        highest = max(minimum,
                      c.execute('SELECT COALESCE(MAX(build),0) FROM firmware_releases').fetchone()[0],
                      c.execute('SELECT COALESCE(MAX(running_build),0) FROM firmware_devices').fetchone()[0],
                      c.execute('SELECT COALESCE(MAX(build),0) FROM firmware_build_reservations').fetchone()[0])
        if highest >= 999999999:
            raise ValueError('firmware build numbers exhausted')
        build = highest + 1
        c.execute('INSERT INTO firmware_build_reservations(build) VALUES(?)', (build,))
    return build


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--build', type=int)
    action.add_argument('--withdraw', action='store_true')
    action.add_argument('--reserve-build', action='store_true')
    parser.add_argument('--minimum', type=int, default=1)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.device):
        parser.error('invalid device id')
    if not 1 <= args.minimum <= 999999999:
        parser.error('minimum must be 1..999999999')
    if args.build is not None and not 1 <= args.build <= 999999999:
        parser.error('build must be 1..999999999')
    # Refuse to create a different DB accidentally when deployment is not ready.
    database = (Path(os.getenv('DATA_DIR', '/data')) / 'pixel.db').resolve()
    c = sqlite3.connect(f'{database.as_uri()}?mode=rw', uri=True, timeout=30)
    with c:
        ota.init(c)
    if args.reserve_build:
        result = {'build': reserve_build(c, args.minimum)}
    elif args.withdraw:
        with c:
            c.execute('UPDATE firmware_devices SET target=NULL WHERE device_id=?', (args.device,))
        result = {'device': args.device, 'withdrawn': True}
    else:
        image = sys.stdin.buffer.read(ota.MAX_FIRMWARE_SIZE+1)
        result = publish(c, args.device, args.build, image)
    print(json.dumps(result))
    c.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Includes validation HTTPException from the shared image validator.
        print(getattr(exc, 'detail', str(exc)), file=sys.stderr)
        sys.exit(1)
