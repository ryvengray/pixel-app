#!/usr/bin/env python3
"""One-time USB provisioning. Secrets go to NVS, never source code."""
import argparse
import getpass
import json
import shlex
import subprocess
import time
import serial

p = argparse.ArgumentParser()
p.add_argument('--port', default='/dev/cu.usbserial-1440')
p.add_argument('--server', required=True, help='http://UBUNTU_LAN_IP:8787')
p.add_argument('--ssid', help='2.4 GHz Wi-Fi name; prompted if omitted')
p.add_argument('--ssh', help='Optional Ubuntu SSH target; fetch device token without printing it')
args = p.parse_args()
ssid = args.ssid or input('2.4 GHz Wi-Fi name: ').strip()
password = getpass.getpass('Wi-Fi password: ')
if args.ssh:
    if args.ssh.startswith('-'):
        p.error('Invalid SSH target')
    remote = shlex.join(['docker','exec','greta-service','python','-c',
                        'import os; print(os.environ["PHONE_CONTROL_DEVICE_TOKEN"])'])
    token = subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8',args.ssh,remote],
                           check=True,capture_output=True,text=True,timeout=15).stdout.strip()
    print('Device token obtained through SSH (hidden).')
else:
    token = getpass.getpass('PHONE_CONTROL_DEVICE_TOKEN: ')
if not ssid or len(ssid.encode())>32 or len(token)<24:
    p.error('Invalid SSID or device token')
with serial.Serial(args.port, 115200, timeout=1) as port:
    port.dtr = False
    port.rts = False
    # Opening the port may reset the board; let BLE initialization finish first.
    time.sleep(4)
    port.write((json.dumps(dict(op='config', ssid=ssid, password=password,
                               server=args.server, token=token))+'\n').encode())
    deadline = time.monotonic()+8
    while time.monotonic()<deadline:
        print(port.readline().decode(errors='replace').rstrip())
