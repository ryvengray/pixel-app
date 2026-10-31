"""Daily E1002 canvas and authenticated upstream bridge."""
import base64
import io
import os
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response, HTMLResponse
from pydantic import BaseModel, Field
from PIL import Image, ImageDraw, ImageFont, ImageOps, ImageFilter

router = APIRouter(prefix='/v1/ink', tags=['ink-screen'])

class Today(BaseModel):
    background: str = Field(default='', max_length=12000000)
    location: str = Field(default='', max_length=40)
    weather: str = Field(default='天气暂未更新', max_length=80)
    low: float | None = None
    high: float | None = None
    clothing: str = Field(default='', max_length=120)
    todos: list[str] = Field(default_factory=list, max_length=8)
    english: str = Field(default='Make today count.', max_length=120)
    translation: str = Field(default='让今天过得有意义。', max_length=100)
    quote: str = Field(default='千里之行，始于足下。——《道德经》', max_length=120)

def render_today(data):
    image=Image.new('RGB',(800,480),'#d8e0d6')
    if data.background:
        try:
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter('error', Image.DecompressionBombWarning)
                raw=base64.b64decode(data.background.split(',')[-1],validate=True)
                with Image.open(io.BytesIO(raw)) as source:
                    if source.width*source.height>24000000:raise ValueError('image too large')
                    source=ImageOps.exif_transpose(source).convert('RGBA')
                    fitted=ImageOps.fit(source,(800,480),method=Image.Resampling.LANCZOS)
                    base=Image.new('RGBA',(800,480),'white');base.alpha_composite(fitted)
                    image=base.convert('RGB')
        except Exception as exc:raise HTTPException(400,'背景图片无效或超过 2400 万像素') from exc
    else:
        # Native palette fallback avoids noisy dithering when no photo is selected.
        image=Image.new('RGB',(800,480),'white')
        d=ImageDraw.Draw(image)
        d.ellipse((72,130,292,350),fill='yellow')
        d.ellipse((188,243,270,325),fill='red')
    def glass(box,opacity=220,radius=20):
        nonlocal image
        mask=Image.new('L',image.size);md=ImageDraw.Draw(mask);md.rounded_rectangle(box,radius,fill=255)
        frosted=image.filter(ImageFilter.GaussianBlur(14)).convert('RGBA')
        tint=Image.new('RGBA',image.size,(255,255,255,opacity))
        frosted=Image.alpha_composite(frosted,tint).convert('RGB')
        image.paste(frosted,(0,0),mask)
        ImageDraw.Draw(image).rounded_rectangle(box,radius,outline='white',width=1)
    # Keep the left half free for the child; content lives in bright frosted cards.
    glass((24,24,352,100),245)
    glass((24,350,352,456),245)
    glass((376,24,776,208),250)
    glass((376,220,776,456),250)
    draw=ImageDraw.Draw(image)
    path=os.getenv('INK_FONT_PATH','/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
    def text(x,y,value,size=20,color='black',width=700,max_lines=2):
        f=ImageFont.truetype(path,size); lines=['']
        for ch in value:
            if ch=='\n' or draw.textlength(lines[-1]+ch,font=f)>width:
                if len(lines)>=max_lines:
                    lines[-1]=lines[-1][:-1]+'…';break
                lines.append('' if ch=='\n' else ch)
            else: lines[-1]+=ch
        for n,line in enumerate(lines):draw.text((x,y+n*(size+8)),line,font=f,fill=color)
    date=datetime.now(ZoneInfo('Asia/Shanghai'))
    ink='black';muted='black'
    text(44,37,date.strftime('%m / %d'),32,ink,width=160,max_lines=1)
    text(218,44,['星期一','星期二','星期三','星期四','星期五','星期六','星期日'][date.weekday()],20,ink,width=115,max_lines=1)
    text(44,365,'TODAY, TOGETHER',12,muted,width=280,max_lines=1)
    text(44,390,data.quote,19,ink,width=285,max_lines=2)
    text(398,39,'今日天气  /  '+(data.location or 'MY DAY'),15,muted,width=355,max_lines=1)
    temps='-- °C' if data.low is None or data.high is None else f'{data.low:g}–{data.high:g}°'
    text(398,65,temps,40,ink,width=350,max_lines=1)
    text(398,119,data.weather,17,ink,width=355,max_lines=1)
    advice=data.clothing or '穿衣建议待天气更新。'
    text(398,151,advice,16,muted,width=355,max_lines=2)
    text(398,234,'今日待办',18,ink,width=260,max_lines=1)
    for i,item in enumerate((data.todos or ['今天暂无待办'])[:3]):
        y=269+i*29
        draw.rounded_rectangle((399,y+4,410,y+15),3,outline=muted,width=1)
        text(422,y,item,17,ink,width=330,max_lines=1)
    draw.line((398,365,754,365),fill='black',width=1)
    text(398,378,data.english,19,ink,width=355,max_lines=2)
    text(398,429,data.translation,14,muted,width=355,max_lines=1)
    # Quantize only the photograph/decoration. Reapply crisp text and outlines
    # after dithering so small glyphs cannot break into colored noise.
    palette=Image.new('P',(1,1))
    colors=[(255,255,255),(0,0,0),(255,0,0),(255,255,0),(0,255,0),(0,0,255)]
    palette.putpalette([v for rgb in colors for v in rgb]+[255]*(768-18))
    quantized=image.quantize(palette=palette,dither=Image.Dither.FLOYDSTEINBERG).convert('RGB')
    # Text is black: retain its full cores, and threshold edge antialiasing.
    # Bright cards are intentionally white in the final six-color rendering.
    original=image
    for box in [(24,24,352,100),(24,350,352,456),(376,24,776,208),(376,220,776,456)]:
        crop=original.crop(box).convert('L').point(lambda v:255 if v>=150 else 0).convert('RGB')
        mask=Image.new('L',crop.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,crop.width-1,crop.height-1),20,fill=255)
        quantized.paste(crop,(box[0],box[1]),mask)
    out=io.BytesIO();quantized.save(out,format='PNG');return out.getvalue()

async def forward(device_id, content):
    import re
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',device_id):raise HTTPException(400,'Invalid device id')
    base=os.getenv('INK_API_BASE','').rstrip('/');token=os.getenv('INK_TOKEN','')
    if not base or not token:raise HTTPException(503,'Configure INK_API_BASE and INK_TOKEN')
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            result=await client.post(f'{base}/v1/devices/{device_id}/image',content=content,headers={'Authorization':f'Bearer {token}','Content-Type':'image/png'})
        result.raise_for_status();return result.json()
    except (httpx.HTTPError,ValueError) as exc:raise HTTPException(502,'Ink-screen service unavailable or rejected image') from exc

@router.post('/devices/{device_id}/image')
async def image(device_id: str, request: Request):
    data=bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data)>15*1024*1024:raise HTTPException(413,'Image exceeds 15 MB')
    return await forward(device_id,bytes(data))

@router.post('/today/preview.png')
def preview(data: Today):
    return Response(render_today(data),media_type='image/png',headers={'Cache-Control':'no-store'})

@router.post('/devices/{device_id}/today')
async def today(device_id: str, data: Today):
    if data.background:
        import asyncio
        from . import ink_gallery
        try:raw=base64.b64decode(data.background.split(',')[-1],validate=True)
        except ValueError as exc:raise HTTPException(400,'背景图片无效') from exc
        await asyncio.to_thread(ink_gallery.save,raw)
    return await forward(device_id,render_today(data))

# Public shell contains no credentials or personal data; all data calls require auth.

def page():
    return r'''<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>今日水墨屏</title>
<style>*{box-sizing:border-box}body{font:15px system-ui;background:#f3f2ed;color:#243b30;max-width:1040px;margin:40px auto;padding:24px}h1{font-size:32px;letter-spacing:-1px;margin-bottom:8px}input,textarea,button{font:inherit;padding:12px;margin:5px 0;border:1px solid #d6ddd4;border-radius:12px;background:#fff}input{margin-right:8px}textarea{width:100%;height:220px;line-height:1.6}img{width:100%;border-radius:20px;box-shadow:0 16px 44px #273c3020}button{cursor:pointer;padding:12px 22px;margin-right:8px}#send{background:#294c3b;color:white;border:0}p{color:#607166;line-height:1.8}.upload{display:block;background:white;border:1px dashed #a1b6a5;border-radius:16px;padding:20px;margin:24px 0}.upload small{display:block;color:#607166;margin-top:8px}details{margin:20px 0}#status{min-height:28px}</style>
<p>FAMILY DAILY · 家里的今日看板</p><h1>把每一天，放进喜欢的照片里。</h1><p>为六色电子纸定制：照片抖动还原，纯白卡片、清晰黑字，让天气与小事一眼可读。</p>
<input id="token" type="password" placeholder="Pixel 设备令牌"><input id="device" placeholder="水墨屏设备 ID" value="desk-01">
<label class="upload">选择小朋友的照片作为背景<input id="background" type="file" accept="image/*"><small>建议横向照片，人物靠左；右侧显示信息。预览为实际六色转换结果；发送后照片也会加入背景图库，每日 6、14、20 点轮换。</small></label><details><summary>编辑今日内容</summary>
<textarea id="data">{"location":"","weather":"天气暂未更新","todos":[],"english":"Make today count.","translation":"让今天过得有意义。","quote":"千里之行，始于足下。——《道德经》"}</textarea></details>
<p>背景图库每天 06:00、14:00、20:00（上海时间）自动轮换。可直接往宿主机图库目录放入 JPG、PNG、WebP 图片。</p><button id="save-photo">只加入图库</button><button id="rotate">立即轮换</button><button id="load">加载今日内容</button><button id="preview">预览今日画面</button><button id="send">发送到水墨屏</button><p id="status" role="status"></p><img id="screen" alt="今日水墨屏预览">
<script>const apiPrefix=location.pathname.replace(/\/ink-screen\/?$/,'');let previous,background='';document.getElementById('background').onchange=async(e)=>{const file=e.target.files[0];if(!file){background='';return;}if(file.size>8*1024*1024){statusEl.textContent='请选择 8 MB 以内的图片';e.target.value='';background='';return;}try{background=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=reject;reader.readAsDataURL(file);});run(false);}catch(e){statusEl.textContent='图片读取失败';}};async function run(send){try{statusEl.textContent='处理中…';const payload=JSON.parse(document.getElementById('data').value);payload.background=background;const body=JSON.stringify(payload);const r=await fetch(apiPrefix+(send?'/v1/ink/devices/'+encodeURIComponent(document.getElementById('device').value)+'/today':'/v1/ink/today/preview.png'),{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+document.getElementById('token').value},body});if(!r.ok)throw Error(await r.text());if(send){const result=await r.json();statusEl.textContent='已提交，等待屏幕刷新 · '+result.version;}else{if(previous)URL.revokeObjectURL(previous);previous=URL.createObjectURL(await r.blob());document.getElementById('screen').src=previous;statusEl.textContent='预览已更新';}}catch(e){statusEl.textContent=e.message;}}const statusEl=document.getElementById('status');document.getElementById('load').onclick=async()=>{try{const r=await fetch(apiPrefix+'/v1/ink/today/data',{headers:{Authorization:'Bearer '+document.getElementById('token').value}});if(!r.ok)throw Error(await r.text());document.getElementById('data').value=JSON.stringify(await r.json(),null,2);}catch(e){statusEl.textContent=e.message;}};document.getElementById('save-photo').onclick=async()=>{try{const file=document.getElementById('background').files[0];if(!file)throw Error('请先选择照片');const r=await fetch(apiPrefix+'/v1/ink/backgrounds',{method:'POST',headers:{Authorization:'Bearer '+document.getElementById('token').value},body:file});if(!r.ok)throw Error(await r.text());statusEl.textContent='已加入图库，将参与定时轮换';}catch(e){statusEl.textContent=e.message;}};document.getElementById('rotate').onclick=async()=>{try{statusEl.textContent='正在轮换…';const r=await fetch(apiPrefix+'/v1/ink/rotate',{method:'POST',headers:{Authorization:'Bearer '+document.getElementById('token').value}});if(!r.ok)throw Error(await r.text());const result=await r.json();statusEl.textContent=result.skipped?'本次未发送：'+result.skipped:'已轮换并提交，等待屏幕刷新';}catch(e){statusEl.textContent=e.message;}};document.getElementById('preview').onclick=()=>run(false);document.getElementById('send').onclick=()=>run(true);</script></html>'''


@router.get('/today/data')
async def today_data():
    from .main import connect
    data=Today()
    day=datetime.now(ZoneInfo('Asia/Shanghai')).date()
    with connect() as db:
        rows=db.execute("SELECT title,due_at FROM reminder WHERE state='pending' ORDER BY due_at").fetchall()
    data.todos=[r['title'][:160] for r in rows if datetime.fromisoformat(r['due_at']).astimezone(ZoneInfo('Asia/Shanghai')).date()==day][:8]
    sayings=[('Make today count.','让今天过得有意义。'),('One step at a time.','一步一步来。'),('Every day is a new beginning.','每天都是新的开始。')]
    data.english,data.translation=sayings[day.toordinal()%len(sayings)]
    data.quote=['千里之行，始于足下。——《道德经》','学而时习之，不亦说乎？——《论语》','知之者不如好之者。——《论语》'][day.toordinal()%3]
    lat=os.getenv('INK_LATITUDE');lon=os.getenv('INK_LONGITUDE');data.location=os.getenv('INK_LOCATION','')
    if lat and lon:
        try:
            async with httpx.AsyncClient(timeout=15) as client:
                r=await client.get('https://api.open-meteo.com/v1/forecast',params={'latitude':float(lat),'longitude':float(lon),'daily':'weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max','timezone':'Asia/Shanghai','forecast_days':1})
            r.raise_for_status();daily=r.json()['daily'];data.low=daily['temperature_2m_min'][0];data.high=daily['temperature_2m_max'][0]
            rain=daily['precipitation_probability_max'][0]
            code=daily['weather_code'][0]
            description=('晴' if code==0 else '多云' if code in (1,2,3) else '雾' if code in (45,48) else '雷雨' if code in (95,96,99) else '雪' if code in (71,73,75,77,85,86) else '雨' if code in (51,53,55,56,57,61,63,65,66,67,80,81,82) else '天气暂无描述')
            data.weather=description+(f' · 降水概率 {rain}%' if rain is not None else '')
            low=data.low
            if low is None:raise ValueError('missing temperature')
            data.clothing=('建议厚外套，注意保暖。' if low<10 else '建议薄外套，早晚分层穿衣。' if low<20 else '建议轻薄透气衣物。')+(' 出门带伞。' if rain is not None and rain>=40 else '')
        except (httpx.HTTPError,ValueError,KeyError,TypeError,IndexError):
            data.weather='天气获取失败，请稍后重试'
    return data


@router.get('/backgrounds')
def backgrounds():
    from . import ink_gallery
    return {'photos':[p.name for p in ink_gallery.photos()], 'schedule':['06:00','14:00','20:00'], 'timezone':'Asia/Shanghai'}


@router.post('/backgrounds')
async def upload_background(request: Request, display: bool = False):
    import asyncio
    from . import ink_gallery
    raw=bytearray()
    async for chunk in request.stream():
        raw.extend(chunk)
        if len(raw)>15*1024*1024:raise HTTPException(413,'图片超过 15 MB')
    name=await asyncio.to_thread(ink_gallery.save,bytes(raw))
    if not display:return {'background':name,'saved':True}
    data=await today_data()
    data.background=base64.b64encode((ink_gallery.directory()/name).read_bytes()).decode()
    result=await forward(os.getenv('INK_DEVICE_ID','desk-01'),await asyncio.to_thread(render_today,data))
    return {**result,'background':name,'saved':True}


@router.post('/rotate')
async def rotate_now():
    from . import ink_gallery
    return await ink_gallery.rotate(force=True)
