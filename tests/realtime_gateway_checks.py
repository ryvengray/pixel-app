"""Run with the realtime venv on Ubuntu: unittest, no phone/ADB required."""
import asyncio
import struct
import types
import unittest
from realtime_gateway import Session


class Writer:
    def __init__(self):self.messages=[]
    def write(self,value):self.messages.append(value)
    async def drain(self):pass


class GatewayChecks(unittest.IsolatedAsyncioTestCase):
    def make_session(self):
        s=object.__new__(Session);s.closed=False;s.pointer=None;s.last_seq=-1;s.touch_count=0;s.control_lock=asyncio.Lock()
        s.last_keyframe_request=0
        s.source=types.SimpleNamespace(width=576,height=1280,epoch=1,control=Writer())
        s.send=lambda data:None
        return s
    def event(self,action,seq,**kwargs):
        data={'type':'touch','action':action,'seq':seq,'epoch':1,'x':100,'y':200,'width':576,'height':1280};data.update(kwargs);return data
    async def test_live_moves_arrive_before_release(self):
        s=self.make_session()
        await s.touch(self.event('down',1));await s.touch(self.event('move',2,y=240))
        self.assertEqual(len(s.source.control.messages),2)
        self.assertEqual(s.pointer,(100,240))
        await s.touch(self.event('up',3,y=240));self.assertIsNone(s.pointer)
        self.assertEqual([struct.unpack('>BBqiiHHHII',m)[1] for m in s.source.control.messages],[0,2,1])
    async def test_replay_rotation_and_cancel(self):
        s=self.make_session()
        await s.touch(self.event('move',1));self.assertEqual(s.source.control.messages,[])
        await s.touch(self.event('down',2));await s.touch(self.event('move',2));self.assertEqual(len(s.source.control.messages),1)
        await s.touch(self.event('move',3,epoch=2));self.assertEqual(len(s.source.control.messages),1)
        await s.cancel_touch();self.assertIsNone(s.pointer)
        self.assertEqual(struct.unpack('>BBqiiHHHII',s.source.control.messages[-1])[1],3)
    async def test_bad_coordinates_not_injected(self):
        s=self.make_session();await s.touch(self.event('down',1,x=-1));await s.touch(self.event('down',2,x=True))
        self.assertEqual(s.source.control.messages,[])
    async def test_keyframe_request_is_not_a_touch_and_is_throttled(self):
        s=self.make_session();await s.request_keyframe();await s.request_keyframe()
        self.assertEqual(s.source.control.messages,[bytes([17])])
        self.assertIsNone(s.pointer)


if __name__=='__main__':unittest.main()
