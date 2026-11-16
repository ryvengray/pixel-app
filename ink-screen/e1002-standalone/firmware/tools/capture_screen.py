#!/usr/bin/env python3
"""Capture the device's completed 800x480 frame over USB, with CRC validation.

Uses pyserial (already available in the PlatformIO Python environment). This
exports rendered pixels; it does not photograph the physical ink/panel colors.
"""
import argparse
from pathlib import Path
import time
import zlib
import serial

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--port", required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--restart", action="store_true", help="restart using USB RTS before capture")
args = parser.parse_args()
with serial.Serial(args.port, 115200, timeout=0.3) as connection:
    if args.restart:
        connection.dtr = False
        connection.rts = True
        time.sleep(0.15)
        connection.rts = False
        time.sleep(0.5)
    connection.write(b"SCREENSHOT\n")
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        line = connection.readline(512)
        if not line.startswith(b"E1002BMP "):
            continue
        _, size, expected = line.split()
        size = int(size)
        if size != 192118:
            raise RuntimeError("Unexpected screenshot size")
        bitmap = bytearray()
        while len(bitmap) < size and time.monotonic() < deadline:
            bitmap.extend(connection.read(min(8192, size - len(bitmap))))
        if len(bitmap) != size or bitmap[:2] != b"BM":
            raise RuntimeError("Incomplete screenshot")
        if zlib.crc32(bitmap[118:]) != int(expected, 16):
            raise RuntimeError("Screenshot checksum mismatch; retry while no keys are pressed")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(bitmap)
        args.output.chmod(0o600)
        print(f"Captured 800x480 completed device frame: {args.output}")
        break
    else:
        raise RuntimeError("No screenshot response; wake the device or use --restart")
