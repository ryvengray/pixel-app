"""Email login and physically confirmed device pairing. Secrets stay out of logs."""
import hashlib
import hmac
import os
import secrets
import smtplib
import ssl
import time
from email.message import EmailMessage
from uuid import uuid4

from fastapi import BackgroundTasks, Depends, Header, HTTPException, Request, Response
from pydantic import BaseModel, Field

SCHEMA = '''
CREATE TABLE IF NOT EXISTS users(email TEXT PRIMARY KEY, created REAL NOT NULL);
CREATE TABLE IF NOT EXISTS mail_codes(email TEXT PRIMARY KEY, digest TEXT NOT NULL, expires REAL NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, sent REAL NOT NULL);
CREATE TABLE IF NOT EXISTS web_sessions(digest TEXT PRIMARY KEY, email TEXT NOT NULL, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS auth_limits(bucket TEXT PRIMARY KEY, started REAL NOT NULL, hits INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS pair_codes(id TEXT PRIMARY KEY, owner TEXT NOT NULL, digest TEXT NOT NULL UNIQUE, mode TEXT NOT NULL, expires REAL NOT NULL, state TEXT NOT NULL DEFAULT 'open');
CREATE TABLE IF NOT EXISTS pair_tickets(digest TEXT PRIMARY KEY, name TEXT NOT NULL, expires REAL NOT NULL, code_id TEXT, state TEXT NOT NULL DEFAULT 'waiting', device_id TEXT);
'''
class Email(BaseModel):
    email: str = Field(min_length=3,max_length=254,pattern=r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
class Verify(Email):
    code: str = Field(pattern=r'^\d{6}$')
class PairCode(BaseModel):
    mode: str = Field(default='live',pattern=r'^(live|practice)$')
class Ticket(BaseModel):
    secret: str = Field(min_length=32,max_length=128,pattern=r'^[a-zA-Z0-9_-]+$')
    name: str = Field(min_length=1,max_length=60)
class Claim(Ticket):
    code: str = Field(pattern=r'^\d{4}$')
class Confirm(BaseModel):
    ticket: str = Field(pattern=r'^[a-f0-9]{64}$')

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

class Access:
    def __init__(self,store,key):
        self.store,self.key=store,key
        self.cookie='math_parent_session'

    def setup(self):
        with self.store.connect() as db:
            db.executescript(SCHEMA)

    def mac(self,value):
        return hmac.new(self.key.encode(),value.encode(),hashlib.sha256).hexdigest()

    def allowed(self,email):
        allowed={s.strip().lower() for s in os.getenv('MATH_PARENT_EMAILS','').split(',') if s.strip()}
        return not allowed or email in allowed

    def parent(self,request,authorization):
        if authorization and authorization.startswith('Bearer ') and secrets.compare_digest(authorization[7:].encode(),self.key.encode()):
            return 'administrator'
        raw=request.cookies.get(self.cookie,'')
        with self.store.connect() as db:
            row=db.execute('SELECT * FROM web_sessions WHERE digest=? AND expires>?',(digest(raw),time.time())).fetchone()
        if row and self.allowed(row['email']):
            if request.method not in ('GET','HEAD') and request.headers.get('x-math-request')!='1':
                raise HTTPException(403,'请从家长页面发起操作')
            return row['email']
        raise HTTPException(401,'请先登录家长账户')

    def limit(self,db,bucket,maximum,window):
        now=time.time()
        row=db.execute('SELECT * FROM auth_limits WHERE bucket=?',(bucket,)).fetchone()
        if row and now-row['started']<window:
            if row['hits']>=maximum:raise HTTPException(429,'操作太频繁，请稍后再试')
            db.execute('UPDATE auth_limits SET hits=hits+1 WHERE bucket=?',(bucket,))
        else:db.execute('INSERT OR REPLACE INTO auth_limits VALUES(?,?,1)',(bucket,now))

    def send_mail(self,email,code,code_digest):
        message=EmailMessage()
        message['Subject']='小语数学冒险岛登录验证码'
        message['From']=(os.getenv('MAIL_FROM') or os.getenv('MAIL_USERNAME',''))
        message['To']=email
        message.set_content('你的登录验证码是：'+code+'\n5分钟内有效，请勿转发。若非本人操作，请忽略。')
        try:
            host=os.getenv('MAIL_HOST','smtp.163.com');port=int(os.getenv('MAIL_PORT','465'))
            if os.getenv('MAIL_SSL_ENABLED','true').lower()=='true':
                client=smtplib.SMTP_SSL(host,port,timeout=15,context=ssl.create_default_context())
            else:
                client=smtplib.SMTP(host,port,timeout=15);client.starttls(context=ssl.create_default_context())
            with client:
                client.login(os.getenv('MAIL_USERNAME',''),os.getenv('MAIL_PASSWORD',''))
                client.send_message(message)
        except Exception:
            # Do not log SMTP errors that may contain addresses or authentication material.
            with self.store.connect(write=True) as db:
                db.execute('DELETE FROM mail_codes WHERE email=? AND digest=?',(email,code_digest))

    def install(self,app,parent,ensure_player):
        @app.post('/api/math/auth/code')
        def send(body:Email,request:Request,background:BackgroundTasks):
            email=body.email.strip().lower()
            if not os.getenv('MAIL_USERNAME') or not os.getenv('MAIL_PASSWORD'):
                raise HTTPException(503,'邮箱登录尚未配置，请管理员设置发信参数')
            with self.store.connect(write=True) as db:
                self.limit(db,'send-ip:'+request.client.host,20,3600)
                self.limit(db,'send-mail:'+email,1,60)
                self.limit(db,'hour-mail:'+email,5,3600)
                if self.allowed(email):
                    code=f'{secrets.randbelow(1000000):06d}';hashed=self.mac(email+':'+code)
                    db.execute('INSERT OR REPLACE INTO mail_codes VALUES(?,?,?,0,?)',(email,hashed,time.time()+300,time.time()))
                    background.add_task(self.send_mail,email,code,hashed)
            return {'message':'如该邮箱已获授权，验证码将发送到邮箱，请稍候查看。'}

        @app.post('/api/math/auth/verify')
        def verify(body:Verify,request:Request,response:Response):
            email=body.email.strip().lower();valid=False
            with self.store.connect(write=True) as db:
                self.limit(db,'verify-ip:'+request.client.host,30,300)
                row=db.execute('SELECT * FROM mail_codes WHERE email=?',(email,)).fetchone()
                if row and row['expires']>time.time() and row['attempts']<5 and self.allowed(email):
                    db.execute('UPDATE mail_codes SET attempts=attempts+1 WHERE email=?',(email,))
                    valid=hmac.compare_digest(row['digest'],self.mac(email+':'+body.code))
                    if valid:
                        db.execute('DELETE FROM mail_codes WHERE email=?',(email,))
                        db.execute('INSERT OR IGNORE INTO users VALUES(?,?)',(email,time.time()))
                        token=secrets.token_urlsafe(32)
                        db.execute('INSERT INTO web_sessions VALUES(?,?,?)',(digest(token),email,time.time()+30*86400))
            if not valid:raise HTTPException(401,'验证码无效或过期，请重新获取')
            response.set_cookie(self.cookie,token,max_age=30*86400,httponly=True,secure=os.getenv('MATH_COOKIE_SECURE','true').lower()=='true',samesite='strict',path=os.getenv('MATH_ROOT_PATH','') or '/')
            return {'email':email}

        @app.get('/api/math/auth/session')
        def session(owner=Depends(parent)):
            return {'email':owner}

        @app.post('/api/math/auth/logout')
        def logout(request:Request,response:Response):
            if request.headers.get('x-math-request')!='1':raise HTTPException(403,'请从家长页面退出')
            with self.store.connect(write=True) as db:
                db.execute('DELETE FROM web_sessions WHERE digest=?',(digest(request.cookies.get(self.cookie,'')),))
            response.delete_cookie(self.cookie,path=os.getenv('MATH_ROOT_PATH','') or '/')
            return {'ok':True}

        @app.post('/api/math/parent/pairing')
        def begin(body:PairCode,owner=Depends(parent)):
            with self.store.connect(write=True) as db:
                self.limit(db,'pair-create:'+owner,10,600)
                db.execute("UPDATE pair_codes SET state='cancelled' WHERE owner=? AND state IN ('open','claimed')",(owner,))
                db.execute('DELETE FROM pair_codes WHERE expires<?',(time.time(),))
                for _ in range(100):
                    code=f'{secrets.randbelow(10000):04d}';hashed=self.mac('pair:'+code)
                    if not db.execute('SELECT 1 FROM pair_codes WHERE digest=?',(hashed,)).fetchone():break
                else:raise HTTPException(503,'配对繁忙，请稍后重试')
                pid=uuid4().hex
                db.execute('INSERT INTO pair_codes(id,owner,digest,mode,expires) VALUES(?,?,?,?,?)',(pid,owner,hashed,ensure_player(db,owner,body.mode),time.time()+180))
            return {'id':pid,'code':code,'expires_in':180}

        @app.get('/api/math/parent/pairing/{pid}')
        def pairing_status(pid:str,owner=Depends(parent)):
            with self.store.connect() as db:
                row=db.execute('SELECT * FROM pair_codes WHERE id=? AND owner=?',(pid,owner)).fetchone()
                if not row:raise HTTPException(404,'配对不存在')
                tickets=db.execute("SELECT digest,name FROM pair_tickets WHERE code_id=? AND state='claimed'",(pid,)).fetchall()
                return {'state':row['state'] if row['expires']>time.time() else 'expired','devices':[{**dict(t),'verification':t['digest'][:6]} for t in tickets]}

        @app.post('/api/math/parent/pairing/{pid}/confirm')
        def confirm(pid:str,body:Confirm,owner=Depends(parent)):
            with self.store.connect(write=True) as db:
                row=db.execute("SELECT * FROM pair_codes WHERE id=? AND owner=? AND state='claimed' AND expires>?",(pid,owner,time.time())).fetchone()
                ticket=db.execute("SELECT * FROM pair_tickets WHERE digest=? AND code_id=? AND state='claimed' AND expires>?",(body.ticket,pid,time.time())).fetchone()
                if not row or not ticket:raise HTTPException(409,'配对已过期或已处理')
                did=uuid4().hex
                db.execute('INSERT INTO devices(id,name,player_id,token_hash) VALUES(?,?,?,?)',(did,ticket['name'],row['mode'],ticket['digest']))
                db.execute("UPDATE pair_tickets SET state='approved',device_id=? WHERE digest=?",(did,ticket['digest']))
                db.execute("UPDATE pair_codes SET state='used' WHERE id=?",(pid,))
                return {'id':did,'mode':db.execute('SELECT mode FROM players WHERE id=?',(row['mode'],)).fetchone()[0]}

        @app.post('/api/math/pair/claim')
        def claim(body:Claim,request:Request):
            hashed=digest(body.secret)
            with self.store.connect(write=True) as db:
                self.limit(db,'pair-ip:'+request.client.host,15,180)
                self.limit(db,'pair-ticket:'+hashed,5,180)
                existing=db.execute('SELECT * FROM pair_tickets WHERE digest=?',(hashed,)).fetchone()
                if existing and existing['state']=='approved':return {'state':'approved'}
                if existing and existing['state']=='claimed':return {'state':'claimed'}
                row=db.execute("SELECT * FROM pair_codes WHERE digest=? AND state='open' AND expires>?",(self.mac('pair:'+body.code),time.time())).fetchone()
                if row:
                    db.execute("INSERT OR REPLACE INTO pair_tickets VALUES(?,?,?,?,'claimed',NULL)",(hashed,body.name,time.time()+300,row['id']))
                    db.execute("UPDATE pair_codes SET state='claimed' WHERE id=?",(row['id'],))
            if not row:raise HTTPException(400,'配对码无效或已使用，请重新开启配对')
            return {'state':'claimed','verification':hashed[:6],'message':'请在家长网页确认屏幕上的设备名称'}

        @app.get('/api/math/pair/status')
        def poll(authorization:str|None=Header(default=None)):
            raw=(authorization or '').removeprefix('Bearer ')
            with self.store.connect() as db:
                row=db.execute('SELECT * FROM pair_tickets WHERE digest=?',(digest(raw),)).fetchone()
                if not row:raise HTTPException(404,'配对不存在')
                code=db.execute('SELECT * FROM pair_codes WHERE id=?',(row['code_id'],)).fetchone()
                status=row['state']
                if status!='approved' and (row['expires']<time.time() or not code or code['state']=='cancelled' or code['expires']<time.time()):status='expired'
                return {'state':status,'verification':row['digest'][:6]}
