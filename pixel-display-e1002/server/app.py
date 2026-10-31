import hashlib, io, json, os, re, sqlite3, time, uuid
from pathlib import Path
from contextlib import asynccontextmanager
import paho.mqtt.client as mqtt
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field
from PIL import Image, ImageDraw, ImageFont

DATA = Path(os.getenv('DATA_DIR', '/data')); DATA.mkdir(parents=True, exist_ok=True)
TOKEN = os.getenv('PIXEL_TOKEN', 'change-me')
MQTT_HOST = os.getenv('MQTT_HOST', 'mosquitto')
ID = re.compile(r'^[A-Za-z0-9_-]{1,64}$')

def db():
    c = sqlite3.connect(DATA / 'pixel.db', timeout=10); c.row_factory = sqlite3.Row
    return c

def check_id(device_id):
    if not ID.fullmatch(device_id): raise HTTPException(400, 'invalid device id')

def auth(authorization: str = Header(default='')):
    if not TOKEN or authorization != 'Bearer ' + TOKEN: raise HTTPException(401, 'unauthorized')

from fastapi import Depends
client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='pixel-api-' + uuid.uuid4().hex[:8])
@asynccontextmanager
async def lifespan(app):
    with db() as c:
        c.execute('CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, version TEXT, bitmap BLOB, updated INTEGER, displayed_version TEXT, last_seen INTEGER, status TEXT)')
    client.reconnect_delay_set(1, 30)
    client.connect_async(MQTT_HOST, int(os.getenv('MQTT_PORT', '1883')), 60); client.loop_start()
    yield
    client.loop_stop(); client.disconnect()

app = FastAPI(title='Pixel Display v1', lifespan=lifespan)
class DisplayRequest(BaseModel):
    title: str = Field(default='', max_length=80)
    body: str = Field(default='', max_length=1000)
    title_color: str = Field(default='blue', pattern='^(black|white|red|yellow|green|blue)$')
class Ack(BaseModel):
    version: str = Field(max_length=64)
    status: str = Field(pattern='^(displayed|failed)$')

def font(size):
    return ImageFont.truetype(os.getenv('FONT_PATH', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'), size)

# Wire palette values are protocol-specific, not panel register values.
COLORS = {'white': (255,255,255), 'black': (0,0,0), 'red': (255,0,0), 'yellow': (255,255,0), 'green': (0,255,0), 'blue': (0,0,255)}
PALETTE = list(COLORS.values())
def render(req):
    im = Image.new('P', (800,480), 0)
    im.putpalette([v for rgb in PALETTE for v in rgb] + [0]*(768-len(PALETTE)*3))
    d = ImageDraw.Draw(im)
    d.rectangle((0,0,799,479), outline=1, width=2)
    d.text((30,22), req.title, font=font(36), fill=list(COLORS).index(req.title_color))
    d.line((30,80,770,80), fill=2, width=2)
    f = font(26); y = 100; line = ''
    for ch in req.body + '\n':
        if ch == '\n' or d.textlength(line + ch, font=f) > 740:
            if y > 437: break
            d.text((30,y), line, font=f, fill=1); y += 38; line = ''
            if ch != '\n': line = ch
        else: line += ch
    # P-mode drawing writes the protocol's palette indices directly.
    indices = im.tobytes()
    return bytes((indices[i] << 4) | indices[i+1] for i in range(0, len(indices), 2))

@app.get('/health')
def health(): return {'ok': True, 'mqtt_connected': client.is_connected()}

@app.post('/v1/devices/{device_id}/display', dependencies=[Depends(auth)])
def display(device_id: str, req: DisplayRequest):
    check_id(device_id); bitmap = render(req); version = hashlib.sha256(bitmap).hexdigest()
    now = int(time.time())
    with db() as c:
        c.execute('INSERT INTO devices(id,version,bitmap,updated) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version, bitmap=excluded.bitmap, updated=excluded.updated', (device_id,version,bitmap,now))
    info = client.publish(f'pixel/v1/devices/{device_id}/notify', json.dumps({'version':version}), qos=1, retain=False)
    return {'device_id': device_id, 'version': version, 'mqtt_queued': info.rc == mqtt.MQTT_ERR_SUCCESS}

@app.get('/v1/devices/{device_id}/desired', dependencies=[Depends(auth)])
def desired(device_id: str):
    check_id(device_id)
    with db() as c:
        row = c.execute('SELECT version,updated,displayed_version,last_seen,status FROM devices WHERE id=?',(device_id,)).fetchone()
    if not row: return {'version': None}
    return dict(row)

@app.get('/v1/devices/{device_id}/bitmap', dependencies=[Depends(auth)])
def bitmap(device_id: str, version: str):
    check_id(device_id)
    with db() as c: row = c.execute('SELECT version,bitmap FROM devices WHERE id=?',(device_id,)).fetchone()
    if not row or row['version'] != version: raise HTTPException(404, 'version not found')
    return Response(row['bitmap'], media_type='application/octet-stream', headers={'Cache-Control':'no-store'})

@app.post('/v1/devices/{device_id}/ack', dependencies=[Depends(auth)])
def ack(device_id: str, req: Ack):
    check_id(device_id)
    with db() as c:
        row = c.execute('SELECT version FROM devices WHERE id=?',(device_id,)).fetchone()
        if not row or row['version'] != req.version: raise HTTPException(409, 'version no longer current')
        c.execute('UPDATE devices SET displayed_version=?,last_seen=?,status=? WHERE id=?', (req.version if req.status == 'displayed' else None,int(time.time()),req.status,device_id))
    return {'ok':True}
