#!/usr/bin/env python3
"""Compile the shared firmware UI and serve an isolated local desktop preview."""
import argparse
import ast
import io
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
import tarfile
import threading
import urllib.request

ROOT = Path(__file__).resolve().parent

def prepare():
    cache = ROOT / '.cache'
    cache.mkdir(exist_ok=True)
    include = cache / 'arduinojson'
    if not (include / 'ArduinoJson.h').exists():
        print('首次运行：下载 ArduinoJson 6.21.5（与固件一致）', flush=True)
        url = 'https://codeload.github.com/bblanchon/ArduinoJson/tar.gz/refs/tags/v6.21.5'
        with urllib.request.urlopen(url, timeout=60) as response:
            archive = response.read()
        with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as archive:
            for member in archive.getmembers():
                if not member.isfile() or '/src/' not in member.name:
                    continue
                relative = Path(member.name.split('/src/', 1)[1])
                if relative.is_absolute() or '..' in relative.parts:
                    raise RuntimeError('Invalid dependency path')
                target = include / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(member).read())
    compiler = shutil.which('c++') or shutil.which('clang++') or shutil.which('g++')
    if not compiler:
        raise RuntimeError('需要 C++ 编译器。macOS 请先运行 xcode-select --install；Linux 安装 g++。')
    binary = cache / 'preview'
    subprocess.run([compiler, '-std=c++17', '-O1', '-I'+str(include), str(ROOT/'preview.cpp'), '-o', str(binary)], check=True)
    tree = ast.parse((ROOT.parents[1]/'backend/app/samples.py').read_text())
    raw = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t,ast.Name) and t.id=='RAW' for t in n.targets))
    tasks=[]
    for i,row in enumerate(raw[:4]):
        title,content,options,answer,hints,explanation,topic,skill,expression=row
        tasks.append({'id':str(i),'state':'pending','available':True,'kind':'base','hint_count':0,'question':{'title':title,'content':content,'options':options,'question_type':'choice','solution':{'answer':answer,'hints':hints,'explanation':explanation}}})
    tasks.append({'id':'numeric','state':'pending','available':True,'kind':'base','hint_count':0,'question':{'title':'数字填空','content':'12 + 8 一共有多少？','options':[],'question_type':'number','solution':{'answer':20,'hints':['试试凑整。'],'explanation':'12+8=20。'}}})
    fixture = cache/'fixture.json'
    fixture.write_text(json.dumps({'tasks':tasks,'has_more':False},ensure_ascii=False))
    return subprocess.Popen([str(binary),str(fixture)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=8790)
    parser.add_argument('--smoke-test',action='store_true')
    args=parser.parse_args()
    process=prepare()
    lock=threading.Lock()
    def frame(payload):
        with lock:
            process.stdin.write(json.dumps(payload)+'\n');process.stdin.flush()
            line=process.stdout.readline()
            if not line:raise RuntimeError('预览进程已退出')
            return json.loads(line)
    try:
        if args.smoke_test:
            home=frame({});assert home['screen']==0
            assert ['fillRect',296,0,24,28,0xD718] in home['commands'], 'connection icon missing'
            def click(action):
                nonlocal home
                b=next(b for b in home['buttons'] if b['action']==action)
                home=frame({'x':b['x']+b['w']//2,'y':b['y']+b['h']//2})
                return home
            click(104);assert home['screen']==4
            controls=home['buttons']
            assert {186,187,188,103,140}.issubset({b['action'] for b in controls})
            for i,a in enumerate(controls):
                for b in controls[i+1:]:
                    assert a['x']+a['w']<=b['x'] or b['x']+b['w']<=a['x'] or a['y']+a['h']+2<=b['y'] or b['y']+b['h']+2<=a['y'], 'settings buttons overlap'
            assert ['fillRect',296,0,24,28,0xD718] in home['commands']
            click(188);click(100)
            click(101);assert home['screen']==1
            def check_map_layout():
                buttons=home['buttons']
                for i,a in enumerate(buttons):
                    assert 0<=a['x'] and a['x']+a['w']<=320 and 0<=a['y'] and a['y']+a['h']+2<=240
                    for b in buttons[i+1:]:
                        assert a['x']+a['w']<=b['x'] or b['x']+b['w']<=a['x'] or a['y']+a['h']+2<=b['y'] or b['y']+b['h']+2<=a['y'], 'map buttons overlap'
                for command in home['commands']:
                    if command[0]!='text':continue
                    _,x,y,color,text=command
                    if y!=134:continue
                    for b in buttons:
                        assert y<=b['y'] or y-16>=b['y']+b['h']+2, 'status text overlaps button'
            check_map_layout()
            click(194);check_map_layout();click(193);check_map_layout()
            click(110);assert home['screen']==2
            visible=[c[-1] for c in home['commands'] if c[0]=='text']
            for label in ('A. 68','B. 78','C. 88'):assert visible.count(label)==1, 'short choice duplicated'
            # First fixture has answer B; an incorrect answer, hint, then success.
            click(120);assert home['screen']==3
            click(133);click(130);assert home['screen']==3
            click(133);click(121);assert home['screen']==9
            click(191);assert home['screen']==3
            frame({'reset':True})
            # Last fixture exercises the same numeric keypad as the firmware.
            home=frame({});click(101)
            for _ in range(2):click(194)
            click(114);assert home['screen']==2
            click(185);assert home['screen']==8
            click(171);click(179);click(184);assert home['screen']==9
            assert frame({'reset':True})['screen']==0
            fixture=ROOT/'.cache/fixture.json'
            original=fixture.read_text()
            try:
                data=json.loads(original)
                options=['短选项','先把二十七和十三相加凑成四十，再把四十与三十八相加得到七十八。','另一种思考方法是先把三十八拆成三十和八，然后分步相加。']
                data['tasks'][0]['question']['options']=options
                fixture.write_text(json.dumps(data,ensure_ascii=False))
                home=frame({'reset':True});click(101);click(110)
                body=[]
                for page in range(20):
                    body.extend(c[-1] for c in home['commands'] if c[0]=='text' and c[2] in (45,65,85,105))
                    if any(b['action']==120 for b in home['buttons']):break
                    assert all(b['action'] not in (120,121,122) for b in home['buttons'])
                    click(136)
                else:raise AssertionError('long choices never reach answer buttons')
                full=''.join(body)
                for i,option in enumerate(options):assert chr(65+i)+'. '+option in full, 'choice text lost during pagination'
                labels=[c[-1] for c in home['commands'] if c[0]=='text']
                for label in ('选 A','选 B','选 C'):assert label in labels
                click(121);assert home['screen']==9
            finally:fixture.write_text(original)
            print('PASS: unique short choices, complete paginated long choices, letter-only buttons, shared renderer, touch hit testing, map, wrong answer, hints, success, method, numeric keypad, reset')
            return
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path!='/':self.send_error(404);return
                data=(ROOT/'index.html').read_bytes();self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
            def do_POST(self):
                if self.path!='/frame' or self.headers.get('X-Preview')!='1':self.send_error(403);return
                try:
                    length=int(self.headers.get('Content-Length','0'))
                    if not 0<length<=1024:raise ValueError()
                    payload=json.loads(self.rfile.read(length))
                    if not isinstance(payload,dict):raise ValueError()
                    if payload and payload!={'reset':True}:
                        if set(payload)!={'x','y'} or not all(type(payload[k]) is int for k in ('x','y')):raise ValueError()
                        if not 0<=payload['x']<320 or not 0<=payload['y']<240:raise ValueError()
                    data=json.dumps(frame(payload),ensure_ascii=False).encode()
                    self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
                except (ValueError,KeyError):self.send_error(400)
            def log_message(self,*args):pass
        with ThreadingHTTPServer(('127.0.0.1',args.port),Handler) as server:
            print(f'电脑预览已启动：http://127.0.0.1:{args.port}\n用浏览器打开；Ctrl+C 退出。仅本地数据，不连接正式服务。',flush=True)
            server.serve_forever()
    except KeyboardInterrupt:pass
    finally:
        process.terminate();process.wait(timeout=5)

if __name__=='__main__':main()
