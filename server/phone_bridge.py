#!/usr/bin/env python3
"""Run on Ubuntu host (not the service container): ADB screenshots and HID tests."""
import argparse
import json
import os
import subprocess
import sys
import time
import uuid

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', default='http://127.0.0.1:8787')
    sub = parser.add_subparsers(dest='mode', required=True)
    capture = sub.add_parser('capture')
    capture.add_argument('--serial', required=True, help='Exact adb devices serial / IP:port')
    capture.add_argument('--adb', default='adb')
    capture.add_argument('--interval', type=float, default=2, help='Seconds, 0 for one frame')
    sub.add_parser('status')
    command = sub.add_parser('command')
    command.add_argument('action', help='JSON, e.g. {"type":"move","dx":20}')
    command.add_argument('--id', default=None, help='Reuse same id to query/retry enqueue without duplicate input')
    sub.add_parser('cancel')
    args = parser.parse_args()
    token = os.getenv('PHONE_CONTROL_ADMIN_TOKEN','')
    if len(token)<24:
        parser.error('Set PHONE_CONTROL_ADMIN_TOKEN (24+ characters)')
    base = args.server.rstrip('/')+'/v1/phone-control'
    with httpx.Client(headers={'Authorization':'Bearer '+token}, timeout=15, trust_env=False) as client:
        if args.mode=='status':
            response=client.get(base+'/status')
        elif args.mode=='cancel':
            response=client.post(base+'/commands/cancel')
        elif args.mode=='command':
            action=json.loads(args.action)
            response=client.post(base+'/commands',json={'id':args.id or uuid.uuid4().hex,'action':action})
        else:
            if args.interval<0:
                parser.error('--interval must be nonnegative')
            while True:
                try:
                    result=subprocess.run([args.adb,'-s',args.serial,'exec-out','screencap','-p'],
                                          capture_output=True,timeout=12,check=True)
                    if len(result.stdout)>4*1024*1024:
                        raise ValueError('Screenshot exceeds 4 MiB')
                    response=client.post(base+'/screenshots',files={'file':('screen.png',result.stdout,'image/png')})
                    response.raise_for_status()
                    print(json.dumps(response.json()),flush=True)
                except (subprocess.SubprocessError,httpx.HTTPError,ValueError,OSError) as error:
                    print('Capture failed: '+str(error),file=sys.stderr,flush=True)
                    if args.interval==0:
                        return 1
                if args.interval==0:
                    return 0
                time.sleep(max(args.interval,0.5))
        response.raise_for_status()
        print(json.dumps(response.json(),ensure_ascii=False,indent=2))
    return 0


if __name__=='__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
