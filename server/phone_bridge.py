#!/usr/bin/env python3
"""Run on Ubuntu host (not the service container): ADB screenshots and HID tests."""
import argparse
import json
import os
import subprocess
import sys
import time
import uuid
import struct
from pathlib import Path

import httpx


def adb_run(adb, serial, *arguments, timeout=12):
    return subprocess.run([adb,'-s',serial,*map(str,arguments)],capture_output=True,timeout=timeout,check=True).stdout


def screenshot(adb, serial):
    raw=adb_run(adb,serial,'exec-out','screencap','-p')
    if not raw.startswith(b'\x89PNG\r\n\x1a\n') or len(raw)<24:
        raise ValueError('ADB did not return a PNG screenshot')
    if len(raw)>4*1024*1024:raise ValueError('Screenshot exceeds 4 MiB')
    return raw,struct.unpack('>II',raw[16:24])


def execute_touch(adb, serial, command):
    _,size=screenshot(adb,serial)
    if size!=(command['width'],command['height']):return 'screen_changed'
    action=command['action'];width,height=size
    if action['type']=='tap':
        points=[(action['x'],action['y'])]
        args=['tap',action['x'],action['y']]
    elif action['type']=='swipe':
        points=[(action['x1'],action['y1']),(action['x2'],action['y2'])]
        duration=action['duration_ms']
        if type(duration) is not int or not 100<=duration<=1500:return 'invalid_action'
        args=['swipe',action['x1'],action['y1'],action['x2'],action['y2'],duration]
    else:return 'invalid_action'
    if any(type(x) is not int or type(y) is not int or not 0<=x<width or not 0<=y<height for x,y in points):return 'invalid_coordinates'
    adb_run(adb,serial,'shell','input',*args,timeout=5)
    return 'ok'


def run_bridge(client,base,args):
    boot=uuid.uuid4().hex;connected=False;error='';ack=None
    next_check=0;next_capture=0;last_id=None
    while True:
        now=time.monotonic()
        if now>=next_check:
            try:
                connected=adb_run(args.adb,args.serial,'get-state',timeout=4).strip()==b'device'
                error='' if connected else 'ADB is not ready'
            except (subprocess.SubprocessError,OSError) as exc:
                connected=False;error=('ADB: '+type(exc).__name__)[:200]
            next_check=now+3
        status={'boot_id':boot,'connected':connected,'error':error}
        if ack:status['ack']=ack
        try:
            response=client.post(base+'/bridge/poll',json=status);response.raise_for_status();data=response.json()
            command=data.get('command')
            if command and command['id']!=last_id:
                last_id=command['id']
                try:result=execute_touch(args.adb,args.serial,command)
                except (subprocess.SubprocessError,OSError,ValueError,KeyError):result='adb_execution_failed'
                ack={'id':last_id,'result':result}
                print(json.dumps({'id':last_id,'result':result}),flush=True)
                next_capture=0
            if data.get('capture') and connected and time.monotonic()>=next_capture:
                raw,_=screenshot(args.adb,args.serial)
                response=client.post(base+'/screenshots',files={'file':('screen.png',raw,'image/png')});response.raise_for_status()
                next_capture=time.monotonic()+args.interval
        except (httpx.HTTPError,subprocess.SubprocessError,OSError,ValueError) as exc:
            error=('Bridge: '+type(exc).__name__)[:200]
            if isinstance(exc,(subprocess.SubprocessError,OSError)):connected=False
            print(error,file=sys.stderr,flush=True)
        time.sleep(0.4)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', default='http://127.0.0.1:8787')
    parser.add_argument('--env-file', help='Read only PHONE_CONTROL_ADMIN_TOKEN from this dotenv file')
    sub = parser.add_subparsers(dest='mode', required=True)
    capture = sub.add_parser('capture')
    capture.add_argument('--serial', required=True, help='Exact adb devices serial / IP:port')
    capture.add_argument('--adb', default='adb')
    capture.add_argument('--interval', type=float, default=2, help='Seconds, 0 for one frame')
    bridge=sub.add_parser('bridge',help='Run the on-demand screenshot and touch worker')
    bridge.add_argument('--serial',required=True)
    bridge.add_argument('--adb',default='adb')
    bridge.add_argument('--interval',type=float,default=1)
    sub.add_parser('status')
    command = sub.add_parser('command')
    command.add_argument('action', help='JSON, e.g. {"type":"move","dx":20}')
    command.add_argument('--id', default=None, help='Reuse same id to query/retry enqueue without duplicate input')
    sub.add_parser('cancel')
    args = parser.parse_args()
    token = os.getenv('PHONE_CONTROL_ADMIN_TOKEN','')
    if args.env_file:
        for line in Path(args.env_file).read_text().splitlines():
            if line.startswith('PHONE_CONTROL_ADMIN_TOKEN='):
                token=line.split('=',1)[1].strip().strip('"').strip("'")
    if len(token)<24:
        parser.error('Set PHONE_CONTROL_ADMIN_TOKEN (24+ characters)')
    base = args.server.rstrip('/')+'/v1/phone-control'
    with httpx.Client(headers={'Authorization':'Bearer '+token}, timeout=15, trust_env=False) as client:
        if args.mode=='bridge':
            if args.interval<0.5:parser.error('Bridge screenshot interval must be >=0.5')
            return run_bridge(client,base,args)
        elif args.mode=='status':
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
