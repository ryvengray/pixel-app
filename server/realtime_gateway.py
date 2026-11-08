#!/usr/bin/env python3
"""Loopback-only WebRTC gateway for scrcpy 3.3.4 H.264 and live touch."""
import argparse
import asyncio
import contextlib
import json
import os
import secrets
import struct
import time
from collections import deque
from fractions import Fraction
from pathlib import Path
from contextlib import asynccontextmanager

import av
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration, RTCRtpSender, MediaStreamTrack
from aiortc.mediastreams import MediaStreamError
from fastapi import FastAPI, Header, HTTPException, Depends
from pydantic import BaseModel, Field, ConfigDict
from realtime_protocol import touch_packet

VERSION='3.3.4'
config=None
sessions={}
offer_lock=asyncio.Lock()


async def adb(*args,timeout=12):
    proc=await asyncio.create_subprocess_exec(config.adb,'-s',config.serial,*map(str,args),stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
    try:
        out,err=await asyncio.wait_for(proc.communicate(),timeout)
    except BaseException:
        proc.kill();await proc.wait();raise
    if proc.returncode:raise RuntimeError('ADB command failed: '+err.decode(errors='replace')[:200])
    return out


class EncodedVideo(MediaStreamTrack):
    kind='video'
    def __init__(self,source):super().__init__();self.source=source
    async def recv(self):
        packet=await self.source.queue.get()
        if packet is None:raise MediaStreamError
        return packet


class Scrcpy:
    def __init__(self,owner):
        self.owner=owner;self.queue=asyncio.Queue(maxsize=8);self.proc=None;self.port=None
        self.video=None;self.control=None;self.tasks=[];self.logs=deque(maxlen=8)
        self.width=0;self.height=0;self.epoch=0;self.frames=0;self.dropped=0;self.closed=False
        self.config_packet=b'';self.first_pts=None;self.last_pts=-1
    async def start(self):
        scid=secrets.randbits(31);socket=f'scrcpy_{scid:08x}'
        await adb('push',config.server_jar,f'/data/local/tmp/pixel-scrcpy-{VERSION}.jar')
        self.port=int((await adb('forward','tcp:0','localabstract:'+socket)).strip())
        args=[config.adb,'-s',config.serial,'shell',f'CLASSPATH=/data/local/tmp/pixel-scrcpy-{VERSION}.jar','app_process','/','com.genymobile.scrcpy.Server',VERSION,
              f'scid={scid:08x}','tunnel_forward=true','audio=false','control=true','clipboard_autosync=false','power_on=false',
              'send_device_meta=false','max_size=1280','max_fps=30','video_bit_rate=2000000',
              'video_codec_options=profile:int=1,level:int=512,i-frame-interval:int=1','log_level=warn']
        self.proc=await asyncio.create_subprocess_exec(*args,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.STDOUT)
        async def logs():
            while line:=await self.proc.stdout.readline():self.logs.append(line.decode(errors='replace').strip())
        self.tasks.append(asyncio.create_task(logs()))
        deadline=time.monotonic()+10
        while True:
            writer=None
            try:
                reader,writer=await asyncio.open_connection('127.0.0.1',self.port)
                dummy=await asyncio.wait_for(reader.readexactly(1),1)
                if dummy!=b'\0':raise RuntimeError('invalid scrcpy handshake')
                self.video=writer;break
            except (OSError,asyncio.TimeoutError,asyncio.IncompleteReadError):
                if writer:writer.close()
                if time.monotonic()>deadline or self.proc.returncode is not None:
                    raise RuntimeError('scrcpy did not start: '+'; '.join(self.logs))
                await asyncio.sleep(.15)
        control_reader,self.control=await asyncio.open_connection('127.0.0.1',self.port)
        codec,width,height=struct.unpack('>III',await asyncio.wait_for(reader.readexactly(12),8))
        if codec!=0x68323634:raise RuntimeError('expected H.264 stream')
        self.width,self.height=width,height;self.epoch=1
        async def drain():
            while await control_reader.read(4096):pass
        self.tasks.extend([asyncio.create_task(drain()),asyncio.create_task(self.read_video(reader))])
    async def read_video(self,reader):
        waiting_key=True
        try:
            while True:
                flags,size=struct.unpack('>QI',await asyncio.wait_for(reader.readexactly(12),10))
                if size>8*1024*1024:raise RuntimeError('oversize video packet')
                raw=await asyncio.wait_for(reader.readexactly(size),5)
                if flags & (1<<63):self.config_packet=raw;continue
                key=bool(flags&(1<<62));pts=flags&((1<<62)-1)
                if self.queue.full():
                    while not self.queue.empty():self.queue.get_nowait();self.dropped+=1
                    waiting_key=True
                    asyncio.create_task(self.owner.request_keyframe())
                if waiting_key and not key:self.dropped+=1;continue
                if key:
                    raw=self.config_packet+raw;waiting_key=False
                    # Decode only independent keyframes to track rotation/cropping;
                    # the RTP sender forwards encoded packets without re-encoding.
                    try:
                        decoder=av.CodecContext.create('h264','r')
                        frames=decoder.decode(av.Packet(raw))
                        if frames:
                            width,height=frames[0].width,frames[0].height
                            if (width,height)!=(self.width,self.height):
                                self.width,self.height=width,height;self.epoch+=1
                                await self.owner.cancel_touch()
                                self.owner.send({'type':'format','width':width,'height':height,'epoch':self.epoch})
                    except av.FFmpegError:pass
                if self.first_pts is None:self.first_pts=pts
                self.last_pts=max(self.last_pts+1,pts-self.first_pts)
                packet=av.Packet(raw);packet.pts=self.last_pts;packet.dts=self.last_pts;packet.time_base=Fraction(1,1000000)
                self.queue.put_nowait(packet);self.frames+=1
        except (asyncio.IncompleteReadError,asyncio.TimeoutError,OSError,RuntimeError):
            if not self.closed:asyncio.create_task(self.owner.close())
    async def close(self):
        if self.closed:return
        self.closed=True
        for task in self.tasks:task.cancel()
        for writer in (self.video,self.control):
            if writer:writer.close()
        while not self.queue.empty():self.queue.get_nowait()
        self.queue.put_nowait(None)
        if self.proc and self.proc.returncode is None:
            self.proc.terminate()
            try:await asyncio.wait_for(self.proc.wait(),2)
            except asyncio.TimeoutError:self.proc.kill();await self.proc.wait()
        await asyncio.gather(*self.tasks,return_exceptions=True)
        if self.port:
            with contextlib.suppress(Exception):await adb('forward','--remove',f'tcp:{self.port}',timeout=3)


class Touch(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True)
    type:str=Field(pattern='^touch$')
    action:str=Field(pattern='^(down|move|up|cancel)$')
    seq:int=Field(ge=0)
    epoch:int=Field(ge=1)
    x:int; y:int
    width:int=Field(ge=1,le=8192)
    height:int=Field(ge=1,le=8192)


class Session:
    def __init__(self):
        self.id=secrets.token_hex(16);self.pc=RTCPeerConnection(RTCConfiguration(iceServers=[]))
        self.source=Scrcpy(self);self.channel=None;self.closed=False;self.last_seen=time.monotonic()
        self.pointer=None;self.last_seq=-1;self.control_lock=asyncio.Lock();self.touch_count=0
        self.media_started=False;self.last_keyframe_request=0
        self.watchdog=None
        @self.pc.on('connectionstatechange')
        async def state():
            if self.pc.connectionState in ('failed','closed'):await self.close()
        @self.pc.on('datachannel')
        def channel(ch):
            if ch.label!='control':ch.close();return
            self.channel=ch
            @ch.on('open')
            def opened():self.send({'type':'format','width':self.source.width,'height':self.source.height,'epoch':self.source.epoch})
            @ch.on('message')
            def message(value):
                if not isinstance(value,str) or len(value)>1024:return
                try:data=json.loads(value)
                except ValueError:return
                if not isinstance(data,dict):return
                if data.get('type')=='ping':
                    if not self.media_started:
                        self.media_started=True;asyncio.create_task(self.request_keyframe())
                    self.last_seen=time.monotonic();self.send({'type':'pong','t':data.get('t')})
                    self.send({'type':'format','width':self.source.width,'height':self.source.height,'epoch':self.source.epoch});return
                asyncio.create_task(self.touch(data))
            @ch.on('close')
            def closed():asyncio.create_task(self.close())
    def send(self,data):
        if self.channel and self.channel.readyState=='open':self.channel.send(json.dumps(data))
    async def request_keyframe(self):
        async with self.control_lock:
            now=time.monotonic()
            if self.closed or not self.source.control or now-self.last_keyframe_request<1:return
            self.last_keyframe_request=now
            with contextlib.suppress(OSError,ConnectionError):
                self.source.control.write(bytes([17]));await self.source.control.drain()
    async def cancel_touch(self):
        async with self.control_lock:
            if self.pointer and self.source.control:
                x,y=self.pointer;w,h=self.source.width,self.source.height
                with contextlib.suppress(Exception):
                    self.source.control.write(touch_packet(3,min(x,w-1),min(y,h-1),w,h));await self.source.control.drain()
            self.pointer=None
    async def touch(self,data):
        try:event=Touch.model_validate(data)
        except ValueError:return
        async with self.control_lock:
            if self.closed or event.seq<=self.last_seq:return
            self.last_seq=event.seq
            if event.epoch!=self.source.epoch or (event.width,event.height)!=(self.source.width,self.source.height):
                self.send({'type':'error','message':'画面方向已变化，请松开后重新操作'});return
            if event.action=='down' and self.pointer is not None:return
            if event.action!='down' and self.pointer is None:return
            code={'down':0,'up':1,'move':2,'cancel':3}[event.action]
            try:
                packet=touch_packet(code,event.x,event.y,event.width,event.height)
                self.source.control.write(packet);await self.source.control.drain()
                self.pointer=None if code in (1,3) else (event.x,event.y)
                self.touch_count+=1
                if code in (0,1,3):self.send({'type':'ack','seq':event.seq})
            except (ValueError,OSError,ConnectionError):
                self.send({'type':'error','message':'触控连接不可用'})
    async def start(self,offer):
        await self.source.start()
        await self.pc.setRemoteDescription(RTCSessionDescription(sdp=offer.sdp,type='offer'))
        sender=self.pc.addTrack(EncodedVideo(self.source))
        codecs=[c for c in RTCRtpSender.getCapabilities('video').codecs if c.mimeType.lower()=='video/h264' and str(c.parameters.get('packetization-mode'))=='1']
        for transceiver in self.pc.getTransceivers():
            if transceiver.sender==sender:transceiver.setCodecPreferences(codecs)
        await self.pc.setLocalDescription(await self.pc.createAnswer())
        async def watch():
            while not self.closed:
                await asyncio.sleep(2)
                if time.monotonic()-self.last_seen>15:await self.close()
        self.last_seen=time.monotonic()
        self.watchdog=asyncio.create_task(watch())
        return {'id':self.id,'sdp':self.pc.localDescription.sdp,'type':'answer'}
    async def close(self):
        if self.closed:return
        self.closed=True
        await self.cancel_touch();await self.source.close();await self.pc.close()
        if self.watchdog and self.watchdog!=asyncio.current_task():self.watchdog.cancel()
        sessions.pop(self.id,None)


def access(authorization:str|None=Header(default=None)):
    key=os.getenv('PHONE_CONTROL_ADMIN_TOKEN','')
    if len(key)<24:raise HTTPException(503,'gateway token is not configured')
    if not secrets.compare_digest((authorization or '').encode(),('Bearer '+key).encode()):raise HTTPException(401,'invalid gateway token')


@asynccontextmanager
async def lifespan(app):
    yield
    for session in list(sessions.values()):await session.close()


app=FastAPI(lifespan=lifespan)
class Offer(BaseModel):
    sdp:str=Field(min_length=1,max_length=200000)
    type:str=Field(pattern='^offer$')


@app.post('/offer',dependencies=[Depends(access)])
async def offer(request:Offer):
    async with offer_lock:
        for old in list(sessions.values()):await old.close()
        session=Session();sessions[session.id]=session
        try:return await asyncio.wait_for(session.start(request),20)
        except Exception as error:
            await session.close();print('Realtime start failed:',type(error).__name__,str(error)[:300],flush=True)
            raise HTTPException(503,'实时视频启动失败，请检查手机无线调试和解锁状态')


@app.post('/close/{session_id}',dependencies=[Depends(access)])
async def close(session_id:str):
    session=sessions.get(session_id)
    if session:await session.close()
    return {'ok':True}


@app.get('/status',dependencies=[Depends(access)])
def status():
    return {'sessions':[{'id':s.id,'state':s.pc.connectionState,'width':s.source.width,'height':s.source.height,'frames':s.source.frames,'dropped':s.source.dropped,'touch_events':s.touch_count} for s in sessions.values()]}


if __name__=='__main__':
    import uvicorn
    parser=argparse.ArgumentParser();parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True)
    parser.add_argument('--server-jar',required=True);parser.add_argument('--env-file',required=True);parser.add_argument('--port',type=int,default=8788)
    config=parser.parse_args()
    for line in Path(config.env_file).read_text().splitlines():
        if line.startswith('PHONE_CONTROL_ADMIN_TOKEN='):os.environ['PHONE_CONTROL_ADMIN_TOKEN']=line.split('=',1)[1].strip().strip('"').strip("'")
    uvicorn.run(app,host='127.0.0.1',port=config.port,log_level='warning')
