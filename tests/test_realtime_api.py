import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from server.app import phone_control as pc


def test_realtime_proxy_operator_auth_and_validation(monkeypatch):
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN','admin-'+'a'*32)
    monkeypatch.setenv('PHONE_CONTROL_DEVICE_TOKEN','device-'+'b'*32)
    calls=[]
    async def proxy(method,path,payload=None):
        calls.append((method,path,payload))
        return {'id':'a'*32,'sdp':'answer','type':'answer'}
    monkeypatch.setattr(pc,'realtime_request',proxy)
    app=FastAPI();app.include_router(pc.router);client=TestClient(app)
    body={'sdp':'test-offer','type':'offer'};admin={'Authorization':'Bearer admin-'+'a'*32};device={'Authorization':'Bearer device-'+'b'*32}
    assert client.post('/v1/phone-control/realtime/offer',json=body).status_code==401
    assert client.post('/v1/phone-control/realtime/offer',headers=device,json=body).status_code==401
    assert client.post('/v1/phone-control/realtime/offer',headers=admin,json=body).status_code==200
    assert calls[-1]==('POST','/offer',body)
    assert client.post('/v1/phone-control/realtime/close/'+('a'*32),headers=admin).status_code==200
    assert client.post('/v1/phone-control/realtime/close/invalid',headers=admin).status_code==422
    assert client.post('/v1/phone-control/realtime/offer',headers=admin,json={'sdp':'x','type':'answer'}).status_code==422


def test_gateway_proxy_forwards_internal_token_only(monkeypatch):
    import asyncio
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN','hidden-test-token')
    seen={}
    class Response:
        status_code=200
        def json(self):return {'sessions':[]}
    class Client:
        def __init__(self,**kwargs):assert kwargs['trust_env'] is False
        async def __aenter__(self):return self
        async def __aexit__(self,*args):pass
        async def request(self,method,url,**kwargs):seen.update(method=method,url=url,**kwargs);return Response()
    monkeypatch.setattr(pc.httpx,'AsyncClient',Client)
    assert asyncio.run(pc.realtime_request('GET','/status'))=={'sessions':[]}
    assert seen['url']=='http://127.0.0.1:8788/status'
    assert seen['headers']['Authorization']=='Bearer hidden-test-token'
