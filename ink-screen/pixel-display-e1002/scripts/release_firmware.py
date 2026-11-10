#!/usr/bin/env python3
"""Compile and publish on the Ubuntu Compose host, without a publishing API."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', help='defaults to DEVICE_ID in firmware/include/config.h')
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--build', type=int, help='defaults to the next available build number')
    action.add_argument('--withdraw', action='store_true', help='cancel pending release')
    parser.add_argument('--pio', help='optional PlatformIO executable path')
    args = parser.parse_args()
    config = ROOT / 'firmware/include/config.h'
    content = config.read_text() if config.is_file() else ''
    device = re.search(r'^\s*#define\s+DEVICE_ID\s+"([^"]+)"', content, re.M)
    if args.device is None:
        if not device:
            parser.error('set DEVICE_ID in firmware/include/config.h first')
        args.device = device[1]
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.device):
        parser.error('invalid device id')
    if args.build is not None and not 1 <= args.build <= 999999999:
        parser.error('--build must be 1..999999999')
    docker = ['docker', 'compose', 'exec', '-T', 'api', 'python', '/app/publish_firmware.py', '--device', args.device]
    # Ensure the running service contains this release writer before compiling.
    run(['docker', 'compose', 'exec', '-T', 'api', 'test', '-f', '/app/publish_firmware.py'])
    if args.withdraw:
        run(docker+['--withdraw'])
        return
    if not config.is_file():
        parser.error('fill firmware/include/config.h with real device settings first')
    if not device or device[1] != args.device:
        parser.error('--device must match DEVICE_ID in firmware/include/config.h')
    if any(value in content for value in ('YOUR_WIFI', 'replace-with-the-same-token', 'YOUR_WIFI_PASSWORD')):
        parser.error('replace example Wi-Fi and token values in config.h first')
    with (ROOT / '.ota-release.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        pio = args.pio or shutil.which('pio')
        if not pio:
            venv = ROOT / '.ota-tools'
            pio = str(venv / 'bin/pio')
            if not Path(pio).is_file():
                run([sys.executable, '-m', 'venv', str(venv)])
                run([str(venv/'bin/python'), '-m', 'pip', 'install', 'platformio==6.2.0'])
        if args.build is None:
            version = (ROOT/'firmware/include/firmware_version.h').read_text()
            baseline = re.search(r'^\s*#define\s+FIRMWARE_BUILD\s+([0-9]+)', version, re.M)
            if not baseline:
                parser.error('cannot read default FIRMWARE_BUILD from firmware_version.h')
            result = run(docker+['--reserve-build', '--minimum', baseline[1]], capture_output=True, text=True)
            args.build = json.loads(result.stdout)['build']
            if not isinstance(args.build, int) or not 1 <= args.build <= 999999999:
                raise ValueError('invalid reserved build number')
        print(f'Compiling and publishing {args.device}, build {args.build}', flush=True)
        env = os.environ.copy()
        env['INK_FIRMWARE_BUILD'] = str(args.build)
        run([pio, 'run', '-d', str(ROOT/'firmware'), '-e', 'e1002'], env=env)
        # Build failures stop here. No release is written until validation succeeds.
        with (ROOT/'firmware/.pio/build/e1002/firmware.bin').open('rb') as binary:
            run(docker+['--build', str(args.build)], stdin=binary)
    print('Published. Device installs on its next scheduled or button wake.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as exc:
        print(f'Release failed: {exc}', file=sys.stderr)
        sys.exit(1)
