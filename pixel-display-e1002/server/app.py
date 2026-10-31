import hashlib, io, json, os, re, sqlite3, time, uuid
from pathlib import Path
from contextlib import asynccontextmanager
import paho.mqtt.client as mqtt
from fastapi import FastAPI, Header, HTTPException, Request
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
        c.execute('CREATE TABLE IF NOT EXISTS history (seq INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, version TEXT NOT NULL, bitmap BLOB NOT NULL, created INTEGER NOT NULL)')
        c.execute('INSERT INTO history(device_id,version,bitmap,created) SELECT id,version,bitmap,updated FROM devices d WHERE version IS NOT NULL AND NOT EXISTS (SELECT 1 FROM history h WHERE h.device_id=d.id)')
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
    return save_bitmap(device_id, render(req))

def save_bitmap(device_id, bitmap):
    check_id(device_id); version = hashlib.sha256(bitmap).hexdigest()
    now = int(time.time())
    with db() as c:
        c.execute('INSERT INTO history(device_id,version,bitmap,created) VALUES(?,?,?,?)', (device_id,version,bitmap,now))
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
    with db() as c: row = c.execute('SELECT bitmap FROM history WHERE device_id=? AND version=? ORDER BY seq DESC LIMIT 1',(device_id,version)).fetchone()
    if not row: raise HTTPException(404, 'version not found')
    return Response(row['bitmap'], media_type='application/octet-stream', headers={'Cache-Control':'no-store'})

@app.post('/v1/devices/{device_id}/ack', dependencies=[Depends(auth)])
def ack(device_id: str, req: Ack):
    check_id(device_id)
    with db() as c:
        row = c.execute('SELECT 1 FROM history WHERE device_id=? AND version=?',(device_id,req.version)).fetchone()
        if not row: raise HTTPException(409, 'unknown version')
        c.execute('UPDATE devices SET displayed_version=?,last_seen=?,status=? WHERE id=?', (req.version if req.status == 'displayed' else None,int(time.time()),req.status,device_id))
    return {'ok':True}

@app.get('/v1/devices/{device_id}/history', dependencies=[Depends(auth)])
def history(device_id: str, cursor: int = 0, direction: str = 'latest'):
    check_id(device_id)
    if direction not in ('latest', 'previous', 'next'): raise HTTPException(400, 'invalid direction')
    with db() as c:
        if direction == 'latest' or cursor == 0:
            row = c.execute('SELECT seq,version,created FROM history WHERE device_id=? ORDER BY seq DESC LIMIT 1', (device_id,)).fetchone()
        else:
            op, order = ('<', 'DESC') if direction == 'previous' else ('>', 'ASC')
            row = c.execute(f'SELECT seq,version,created FROM history WHERE device_id=? AND seq {op} ? ORDER BY seq {order} LIMIT 1', (device_id,cursor)).fetchone()
            if not row:
                row = c.execute('SELECT seq,version,created FROM history WHERE device_id=? AND seq=?', (device_id,cursor)).fetchone()
    return dict(row) if row else {'version': None}


@app.post('/v1/devices/{device_id}/image', dependencies=[Depends(auth)])
async def upload_image(device_id: str, request: Request):
    check_id(device_id)
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > 15 * 1024 * 1024:
            raise HTTPException(413, 'image exceeds 15 MB')
    try:
        import warnings
        from PIL import ImageOps
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as source:
                if source.width * source.height > 24000000:
                    raise ValueError('image exceeds 24 megapixels')
                source = ImageOps.exif_transpose(source).convert('RGBA')
                source.thumbnail((800,480), Image.Resampling.LANCZOS)
                canvas = Image.new('RGBA',(800,480),'white')
                canvas.alpha_composite(source,((800-source.width)//2,(480-source.height)//2))
                palette = Image.new('P',(1,1))
                # Fill unused entries with white, so every quantized color maps to a wire index.
                palette.putpalette([v for rgb in PALETTE for v in rgb]+[255]*(768-18))
                quantized = canvas.convert('RGB').quantize(palette=palette,dither=Image.Dither.FLOYDSTEINBERG)
                raw = quantized.tobytes()
                indices = [i if i < 6 else 0 for i in raw]
                packed = bytes((indices[i]<<4)|indices[i+1] for i in range(0,len(indices),2))
    except Exception as exc:
        raise HTTPException(400, 'invalid or unsupported image') from exc
    return save_bitmap(device_id, packed)
