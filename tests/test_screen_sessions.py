import time

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from server.app import ink, phone_control


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN', 'admin-'+'a'*32)
    monkeypatch.setenv('PHONE_CONTROL_DEVICE_TOKEN', 'device-'+'b'*32)
    monkeypatch.setenv('PHONE_CONTROL_DB_PATH', str(tmp_path/'control.db'))
    monkeypatch.setattr(ink, 'verify_totp', lambda code: code == '123456')
    app = FastAPI()
    app.include_router(ink.auth_router)
    app.include_router(phone_control.router)
    app.add_api_route('/esp32-screen', phone_control.page)
    return TestClient(app, base_url='https://testserver')


def test_persistent_cookie_and_control_access_after_one_day(client, monkeypatch):
    now = int(time.time())
    response=client.post('/v1/ink/auth/login', json={'code':'123456'})
    assert response.status_code==204
    cookie=response.headers['set-cookie']
    assert 'Max-Age=31536000' in cookie
    assert 'HttpOnly' in cookie and 'Secure' in cookie and 'SameSite=strict' in cookie
    monkeypatch.setattr(ink.time, 'time', lambda: now+2*86400)
    assert client.get('/v1/phone-control/status').status_code==200
    response=client.get('/v1/ink/auth/session')
    assert response.status_code==200
    assert response.headers['cache-control']=='no-store'
    assert int(client.cookies[ink.SESSION_COOKIE].split('.')[0])==now+2*86400+ink.SESSION_SECONDS
    # A browser login never grants the ESP32 device-polling identity.
    assert client.post('/v1/phone-control/device/poll',json={}).status_code==401


def test_valid_legacy_cookie_upgrades_and_survives_browser_restart(client, monkeypatch):
    now=int(time.time())
    payload=str(now+86400)
    client.cookies.set(ink.SESSION_COOKIE,payload+'.'+ink.session_signature(payload))
    response=client.get('/v1/ink/auth/session')
    assert response.status_code==200
    renewed=response.cookies[ink.SESSION_COOKIE]
    assert int(renewed.split('.')[0])>=now+ink.SESSION_SECONDS
    cookie_value=response.headers['set-cookie']
    assert 'Max-Age=31536000' in cookie_value
    assert all(not cookie.discard for cookie in response.cookies.jar)
    client.cookies.clear()
    client.cookies.set(ink.SESSION_COOKIE,renewed)
    monkeypatch.setattr(ink.time,'time',lambda:now+30*86400)
    assert client.get('/v1/ink/auth/session').status_code==200
    assert client.get('/v1/phone-control/status').status_code==200


def test_invalid_or_expired_sessions_do_not_renew(client):
    assert client.post('/v1/ink/auth/login',json={'code':'000000'}).status_code==401
    now=int(time.time())
    for value in ['not-a-cookie', str(now+100)+'.forged', str(now-1)+'.'+ink.session_signature(str(now-1))]:
        client.cookies.clear()
        client.cookies.set(ink.SESSION_COOKIE,value)
        response=client.get('/v1/ink/auth/session')
        assert response.status_code==401
        assert 'set-cookie' not in response.headers
        assert client.get('/v1/phone-control/status').status_code==401


def test_control_page_long_term_login_copy(client):
    page=client.get('/esp32-screen')
    assert '长期保持登录' in page.text
    assert '有效期 1 天' not in page.text
