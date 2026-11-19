#!/usr/bin/env python3
"""Configure the AHT20 firmware over USB."""
import argparse
import getpass
import json
import time
from urllib.parse import urlsplit

import serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="/dev/cu.usbserial-1440")
    parser.add_argument("--server", required=True, help="http://UBUNTU_LAN_IP:8787")
    parser.add_argument("--ssid", help="2.4 GHz Wi-Fi name (prompted if omitted)")
    args = parser.parse_args()
    server = urlsplit(args.server)
    if (
        server.scheme != "http" or not server.hostname or server.username is not None
        or server.query or server.fragment or len(args.server.encode()) >= 200
    ):
        parser.error("Use a LAN HTTP base URL")

    ssid = args.ssid or input("2.4 GHz Wi-Fi name: ").strip()
    password = getpass.getpass("Wi-Fi password: ")
    token = getpass.getpass("AHT20_DEVICE_TOKEN: ").strip()
    if not 1 <= len(ssid.encode()) <= 32 or len(password.encode()) > 63:
        parser.error("Invalid Wi-Fi name or password length")
    if not 24 <= len(token) <= 128 or not token.isascii() or not token.isalnum():
        parser.error("Use a 24–128 character alphanumeric token, such as openssl rand -hex 32")

    config = dict(op="config", ssid=ssid, password=password, server=args.server, token=token)
    with serial.Serial(args.port, 115200, timeout=1) as port:
        port.dtr = False
        port.rts = False
        time.sleep(2)
        port.reset_input_buffer()
        port.write((json.dumps(config, ensure_ascii=False) + "\n").encode())
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            message = port.readline().decode(errors="replace").strip()
            if message == "Configuration saved. Restarting.":
                print(message)
                return
            if message in {"Configuration write failed", "Invalid Wi-Fi, LAN HTTP server URL, or token"}:
                raise SystemExit(message)
    raise SystemExit("No configuration acknowledgement. Check the port and firmware.")


if __name__ == "__main__":
    main()
