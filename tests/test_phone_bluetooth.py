import asyncio
import importlib.util
import io
import time
from pathlib import Path

import pytest
from fastapi import HTTPException
from PIL import Image

spec=importlib.util.spec_from_file_location('phone_bluetooth',Path(__file__).parents[1]/'server/phone_bluetooth.py')
ble=importlib.util.module_from_spec(spec);spec.loader.exec_module(ble)


def png(color='white'):
    output=io.BytesIO();Image.new('RGB',(960,2142),color).save(output,'PNG');return output.getvalue()


def record():
    return dict(found=True,raw=png(),focus='example/.Main',width=960,height=2142,
        bounds=[800,1700,910,1840],point={'x':850,'y':1770},ready_at=time.time(),captured_at=time.time())


class FakeClicker(ble.BluetoothClicker):
    def __init__(self,path,fault=None):
        async def capture():return png()
        super().__init__('adb','pixel','a'*32,capture,lambda raw:((960,2142),'test-image'),path)
        self.position=[500.,1000.];self.actions=[];self.fault=fault;self.focus_reads=0
    async def ready(self,*args,**kwargs):return 'boot1'
    async def focus(self):
        self.focus_reads+=1
        return 'changed/.Activity' if self.fault=='focus' and self.focus_reads>1 else 'example/.Main'
    async def pointer(self):return '17',(self.position[0]-10,self.position[1]-7)
    async def command(self,client,job_id,boot,step,action):
        self.actions.append(action)
        if self.fault=='lost_move' and action['type']=='move':raise HTTPException(409,'unknown move')
        if action['type']=='move':
            # Deliberately different gains on the axes: feedback must correct them.
            self.position=[max(0,min(959,self.position[0]+action['dx']*2.1)),
                           max(0,min(2141,self.position[1]+action['dy']*1.7))]
        if self.fault=='lost_click' and action['type']=='click':raise HTTPException(409,'unknown click')


def test_sprite_geometry_rejects_ambiguous_or_missing():
    text='  Layer [17] [BBQ] Sprite#16#17\n    visible reason= buffer=1\n    bounds={0,0,54,54} toDisplayTransform={ tx=-10.0000 ty=-7.0000 }\n'
    assert ble.sprite_position(text)==('17',(-10.,-7.))
    for bad in ('',text+text):
        with pytest.raises(HTTPException):ble.sprite_position(bad)
    assert ble.target_difference(png(),png(),[10,10,50,50])==0
    assert ble.target_difference(png(),png('black'),[10,10,50,50])==1


def test_feedback_reaches_target_and_clicks_once(tmp_path):
    async def scenario():
        clicker=FakeClicker(tmp_path/'jobs.db');r=record()
        clicker.submit('a'*32,'b'*32,r)
        await asyncio.gather(*clicker.tasks)
        job=clicker.job('a'*32)
        assert job['state']=='succeeded'
        assert job['result']['transport']=='esp32_ble'
        assert job['result']['business_confirmed'] is False
        assert max(abs(clicker.position[i]-[850,1770][i]) for i in range(2))<=3
        assert len([a for a in clicker.actions if a['type']=='click'])==1
        count=len(clicker.actions)
        assert clicker.submit('a'*32,'b'*32,None)['state']=='succeeded'
        assert len(clicker.actions)==count
        with pytest.raises(HTTPException):clicker.submit('c'*32,'b'*32,r)
    asyncio.run(scenario())


@pytest.mark.parametrize('fault,state',[('focus','failed'),('lost_move','failed'),('lost_click','unknown')])
def test_no_replayed_click_on_change_or_uncertain_delivery(tmp_path,fault,state):
    async def scenario():
        clicker=FakeClicker(tmp_path/'jobs.db',fault=fault)
        clicker.submit('a'*32,'b'*32,record());await asyncio.gather(*clicker.tasks)
        assert clicker.job('a'*32)['state']==state
        actions=list(clicker.actions)
        assert len([a for a in actions if a['type']=='click'])==(1 if fault=='lost_click' else 0)
        clicker.submit('a'*32,'b'*32,None)
        assert clicker.actions==actions
    asyncio.run(scenario())


def test_expired_result_and_recovery_do_not_execute(tmp_path):
    clicker=FakeClicker(tmp_path/'jobs.db');r=record();r['ready_at']-=100
    with pytest.raises(HTTPException):clicker.submit('a'*32,'b'*32,r)
    assert not clicker.actions
    with clicker.db() as db:
        db.execute('INSERT INTO click_job VALUES(?,?,?,?,?,?,NULL)',('c'*32,'d'*32,'running','pending',time.time(),time.time()))
    recovered=FakeClicker(tmp_path/'jobs.db')
    assert recovered.job('c'*32)['state']=='unknown'
    assert not recovered.actions


def test_android_current_window_format():
    assert ble.window_focus('  mCurrentFocus=Window{dfda747 u0 com.example/.Activity}\n')=='com.example/.Activity'
    with pytest.raises(HTTPException):ble.window_focus('mCurrentFocus=null')
