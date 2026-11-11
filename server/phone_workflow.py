#!/usr/bin/env python3
"""Submit/query a natural-language phone task to the existing backend Agent."""
import argparse
import json
import os
import secrets
from pathlib import Path

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', default='http://127.0.0.1:8787')
    parser.add_argument('--env-file')
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--goal', help='完整自然语言目标，保留联系人和消息原文')
    group.add_argument('--task-id', help='查询已有任务，或配合--stop停止')
    parser.add_argument('--execute', action='store_true', help='提交后台执行；省略则只打印目标')
    parser.add_argument('--id', help='首次提交可指定32..64位小写十六进制幂等ID')
    parser.add_argument('--stop', action='store_true')
    parser.add_argument('--trace', action='store_true', help='导出指定任务的完整执行路径JSON')
    parser.add_argument('--output', help='配合--trace保存JSON到文件，权限0600')
    args = parser.parse_args()
    if args.stop and not args.task_id:
        parser.error('--stop需要--task-id')
    if args.trace and (not args.task_id or args.stop):
        parser.error('--trace需要--task-id，不能与--stop同时使用')
    if args.output and not args.trace:
        parser.error('--output需要--trace')
    if args.goal and not args.execute:
        print(json.dumps({'goal': args.goal, 'planner': 'existing_backend_agent',
                          'input_executed': False}, ensure_ascii=False, indent=2))
        return
    token = os.getenv('PHONE_CONTROL_ADMIN_TOKEN', '')
    if args.env_file:
        for line in Path(args.env_file).read_text().splitlines():
            if line.startswith('PHONE_CONTROL_ADMIN_TOKEN='):
                token = line.split('=', 1)[1].strip().strip('\"').strip("'")
    if len(token) < 24:
        parser.error('需要PHONE_CONTROL_ADMIN_TOKEN或--env-file')
    if args.goal:
        task_id = args.id or secrets.token_hex(16)
        print('任务ID：'+task_id, flush=True)
        method, path, payload = 'POST', 'tasks', {'id': task_id, 'goal': args.goal}
    else:
        method, path, payload = ('POST', 'tasks/'+args.task_id+'/stop', None) if args.stop else (
            'GET', 'tasks/'+args.task_id, None)
    try:
        with httpx.Client(base_url=args.server.rstrip('/')+'/v1/phone-control/',
                headers={'Authorization': 'Bearer '+token}, timeout=30, trust_env=False) as client:
            if args.trace:
                params = {'after_seq': 0, 'limit': 200}
                exported = None
                while True:
                    response = client.get('tasks/'+args.task_id+'/trace', params=params)
                    response.raise_for_status()
                    page = response.json()
                    if exported is None:
                        exported = {key: page[key] for key in ('schema_version', 'task_id', 'task', 'through_seq')}
                        exported['events'] = []
                    exported['events'].extend(page['events'])
                    if not page['has_more']:
                        break
                    params.update(after_seq=page['next_after_seq'], through_seq=page['through_seq'])
                content = json.dumps(exported, ensure_ascii=False, indent=2)
                if args.output:
                    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    with os.fdopen(fd, 'w') as stream:
                        stream.write(content+'\n')
                    print('已导出完整执行日志：'+args.output)
                else:
                    print(content)
                return
            response = client.request(method, path, json=payload)
            response.raise_for_status()
            print(json.dumps(response.json(), ensure_ascii=False, indent=2))
    except httpx.HTTPError:
        parser.exit(1, '请求未确认，请用原任务ID查询；不要创建新任务重复发送。\n')


if __name__ == '__main__':
    main()
