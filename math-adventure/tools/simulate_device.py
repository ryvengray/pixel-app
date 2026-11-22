"""Exercise the actual device API using a dedicated practice credential, never live data."""
import argparse
import os
import time
from uuid import uuid4
import httpx

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://127.0.0.1:8788')
args = parser.parse_args()
token = os.environ.get('MATH_DEVICE_TOKEN', '')
if not token:
    raise SystemExit('请先设置MATH_DEVICE_TOKEN为家长页面创建的试玩设备令牌')

with httpx.Client(base_url=args.base_url.rstrip('/')+'/', headers={'Authorization': 'Bearer '+token}, timeout=10) as client:
    today = client.get('api/math/adventure/today')
    today.raise_for_status()
    plan = today.json()
    if plan['mode'] != 'practice':
        raise SystemExit('模拟脚本仅允许使用试玩设备凭证')
    for task in plan['tasks']:
        if not task['available'] or task['state'] == 'solved':
            continue
        q = task['question']
        print(q['title'], q['content'])
        for option in range(len(q['options'])):
            payload = {'task_id': task['id'], 'request_id': uuid4().hex, 'answer': option, 'duration_seconds': 5}
            # Transport retries use the exact same request ID and payload.
            for attempt in range(3):
                try:
                    result = client.post('api/math/answers', json=payload)
                    result.raise_for_status()
                    break
                except httpx.TransportError:
                    if attempt == 2:
                        raise
                    time.sleep(1)
            response = result.json()
            print('  选项', option, response['message'], response['reward'])
            if response['correct']:
                duplicate = client.post('api/math/answers', json=payload)
                duplicate.raise_for_status()
                assert duplicate.json() == response, '重复请求响应不一致'
                print('  去重验证通过：', response['explanation'])
                break
    state = client.get('api/math/device/state')
    state.raise_for_status()
    print('试玩完成：', state.json()['player'])
