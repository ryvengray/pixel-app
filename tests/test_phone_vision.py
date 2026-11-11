import asyncio
import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from PIL import Image
from server.app import phone_control as pc

spec = importlib.util.spec_from_file_location('phone_vision', Path(__file__).parents[1]/'server/phone_vision.py')
vision = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vision)
A = {'Authorization': 'Bearer '+'a'*32}


def test_coordinate_contract():
    result = vision.parse_result(json.dumps({'found': True, 'box': [100,200,300,400]}), 1001,2001)
    assert result['bounds'] == [100,400,300,800]
    assert result['point'] == {'x':200,'y':600}
    assert vision.parse_result('{"found":false}',100,100)['point'] is None
    for box in ([0,0,1001,20],[10,20,5,30],[False,0,50,50],[0,0,float('nan'),50]):
        with pytest.raises(HTTPException):
            vision.parse_result(json.dumps({'found':True,'box':box}),100,100)


def test_gateway_auth_and_real_image_mapping(monkeypatch, tmp_path):
    monkeypatch.setenv('HOME', str(tmp_path))
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN','a'*32)
    monkeypatch.setattr(vision,'config',SimpleNamespace(model='test',ollama='http://ollama'))
    monkeypatch.setattr(vision,'inference_lock',asyncio.Lock())
    image=io.BytesIO();Image.new('RGB',(1001,2001),'white').save(image,'PNG')
    async def capture():return image.getvalue()
    monkeypatch.setattr(vision,'capture',capture)
    class Client:
        def __init__(self,**kwargs):pass
        async def __aenter__(self):return self
        async def __aexit__(self,*args):pass
        async def post(self,url,json):
            assert json['messages'][0]['images']
            assert json['options']['num_ctx']==4096
            return httpx.Response(200,json={'message':{'content':'{"found":true,"box":[100,200,300,400]}'}},request=httpx.Request('POST',url))
    monkeypatch.setattr(vision.httpx,'AsyncClient',Client)
    with TestClient(vision.app) as client:
        assert client.post('/locate',json={'target':'设置'}).status_code==401
        assert client.post('/locate',headers=A,json={'target':' '}).status_code==422
        result=client.post('/locate',headers=A,json={'target':'设置'}).json()
        assert result['point']=={'x':200,'y':600}
        assert result['width']==1001 and result['height']==2001
        assert result['input_executed'] is False
        assert result['image_base64']
        sample = json.loads((tmp_path/'.local/share/pixel-vision/benchmark/latest.json').read_text())
        assert sample['target'] == '设置'
        assert sample['request']['messages'][0]['images'][0] == result['image_base64']
        assert sample['baseline']['result']['point'] == result['point']
        assert 'a'*32 not in json.dumps(sample)


def test_api_auth_and_no_input(monkeypatch):
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN','a'*32)
    monkeypatch.setenv('PHONE_CONTROL_DEVICE_TOKEN','b'*32)
    calls=[]
    async def request(method,path,payload=None):
        calls.append((method,path,payload));return {'found':False,'input_executed':False}
    monkeypatch.setattr(pc,'vision_request',request)
    app=FastAPI();app.include_router(pc.router)
    with TestClient(app) as client:
        url='/v1/phone-control/vision/locate'
        assert client.post(url,json={'target':'设置'}).status_code==401
        assert client.post(url,headers={'Authorization':'Bearer '+'b'*32},json={'target':'设置'}).status_code==401
        assert client.post(url,headers=A,json={'target':'设置','click':True}).status_code==422
        assert client.post(url,headers=A,json={'target':'设置'}).json()['input_executed'] is False
        assert calls==[('POST','/locate',{'target':'设置'})]


def test_click_proxy_is_authenticated_and_rejects_client_coordinates(monkeypatch):
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN','a'*32)
    monkeypatch.setenv('PHONE_CONTROL_DEVICE_TOKEN','b'*32)
    calls=[]
    async def request(method,path,payload=None):
        calls.append((method,path,payload));return {'state':'running','id':'c'*32}
    monkeypatch.setattr(pc,'vision_request',request)
    app=FastAPI();app.include_router(pc.router)
    with TestClient(app) as client:
        url='/v1/phone-control/vision/click';body={'id':'c'*32,'result_id':'d'*32}
        assert client.post(url,json=body).status_code==401
        assert client.post(url,headers={'Authorization':'Bearer '+'b'*32},json=body).status_code==401
        assert client.post(url,headers=A,json={**body,'x':880,'y':1819}).status_code==422
        assert client.post(url,headers=A,json=body).status_code==200
        assert client.get(url+'/'+'c'*32,headers=A).status_code==200
        assert client.get(url+'/invalid',headers=A).status_code==422
        assert calls==[('POST','/click',body),('GET','/click/'+'c'*32,None)]


@pytest.mark.parametrize('content,status,matched', [
    ('{"matched":true,"reason":"名字一致"}', 200, True),
    ('{"matched":false,"reason":"名字不一致"}', 200, False),
    ('{"matched":"true","reason":"不确定"}', 502, None),
    ('{"matched":true}', 502, None),
    ('not json', 502, None),
])
def test_fresh_verification_contract(monkeypatch, content, status, matched):
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN', 'a'*32)
    monkeypatch.setattr(vision, 'config', SimpleNamespace(model='test', ollama='http://ollama'))
    monkeypatch.setattr(vision, 'inference_lock', asyncio.Lock())
    monkeypatch.setattr(vision, 'clicker', None)
    captured = []
    async def capture():
        captured.append(True)
        image = io.BytesIO()
        Image.new('RGB', (100, 200)).save(image, 'PNG')
        return image.getvalue()
    monkeypatch.setattr(vision, 'capture', capture)
    class Client:
        def __init__(self, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def post(self, url, json):
            assert 'images' in json['messages'][0]
            return httpx.Response(200, json={'message': {'content': content}}, request=httpx.Request('POST', url))
    monkeypatch.setattr(vision.httpx, 'AsyncClient', Client)
    with TestClient(vision.app) as client:
        assert client.post('/verify', json={'assertion': '名字'}).status_code == 401
        response = client.post('/verify', headers=A, json={'assertion': '名字'})
        assert response.status_code == status
        if status == 200:
            assert response.json()['matched'] is matched
            assert response.json()['input_executed'] is False
    assert captured == [True]


def test_verify_proxy_auth_and_validation(monkeypatch):
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN', 'a'*32)
    calls = []
    async def request(method, path, payload=None):
        calls.append((method, path, payload))
        return {'matched': False}
    monkeypatch.setattr(pc, 'vision_request', request)
    app = FastAPI(); app.include_router(pc.router)
    with TestClient(app) as client:
        url = '/v1/phone-control/vision/verify'
        assert client.post(url, json={'assertion': '输入框为空'}).status_code == 401
        assert client.post(url, headers=A, json={'assertion': '输入框为空', 'click': True}).status_code == 422
        assert client.post(url, headers=A, json={'assertion': '输入框为空'}).json() == {'matched': False}
    assert calls == [('POST', '/verify', {'assertion': '输入框为空'})]


@pytest.mark.parametrize('valid', [True, False])
def test_observation_is_read_only_fresh_evidence(monkeypatch, valid):
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN', 'a'*32)
    monkeypatch.setattr(vision, 'config', SimpleNamespace(model='test', ollama='http://ollama'))
    monkeypatch.setattr(vision, 'inference_lock', asyncio.Lock())
    monkeypatch.setattr(vision, 'clicker', None)
    captures = []
    async def capture():
        captures.append(True)
        image = io.BytesIO(); Image.new('RGB', (100, 200)).save(image, 'PNG')
        return image.getvalue()
    monkeypatch.setattr(vision, 'capture', capture)
    class Client:
        def __init__(self, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def post(self, url, json):
            assert json['format'] == vision.OBSERVATION_SCHEMA
            data = {'description': '主页有弹窗', 'visible_text': ['关闭'], 'controls': ['关闭按钮'],
                    'elements': [{'label': '关闭', 'box': [100, 200, 300, 400]}],
                    'keyboard_visible': False if valid else 'false'}
            return httpx.Response(200, json={'message': {'content': __import__('json').dumps(data)}},
                                  request=httpx.Request('POST', url))
    monkeypatch.setattr(vision.httpx, 'AsyncClient', Client)
    with TestClient(vision.app) as client:
        assert client.post('/observe', json={'objective': '查找微信'}).status_code == 401
        result = client.post('/observe', headers=A, json={'objective': '查找微信'})
        assert result.status_code == (200 if valid else 502)
        if valid:
            assert result.json()['input_executed'] is False
            assert result.json()['description'] == '主页有弹窗'
            element = result.json()['elements'][0]
            assert element['bounds'] == [10, 40, 30, 80]
            assert element['version'] == result.json()['version']
            assert vision.located_results[element['result_id']]['raw'].startswith(b'\x89PNG')
            old_id = element['element_id']
            next_result = client.post('/observe', headers=A, json={'objective': '查找微信'}).json()
            assert next_result['elements'][0]['element_id'] != old_id
            assert old_id not in vision.located_results
    assert captures == ([True, True] if valid else [True])
