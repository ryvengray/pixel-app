"""Host-mounted photo library and persistent Shanghai-time rotation."""
import asyncio
import base64
import hashlib
import io
import logging
import os
import sqlite3
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from fastapi import HTTPException
from PIL import Image, ImageOps, UnidentifiedImageError

log = logging.getLogger(__name__)

def directory():
    path = Path(os.getenv('INK_GALLERY_DIR', '/data/ink-backgrounds'))
    path.mkdir(parents=True, exist_ok=True)
    return path

def state_db():
    db = sqlite3.connect(directory() / '.rotation.sqlite', timeout=10)
    db.execute('CREATE TABLE IF NOT EXISTS rotation (device TEXT PRIMARY KEY, slot TEXT, photo TEXT)')
    return db

def normalize(raw):
    """Downsample phone photos before EXIF transpose and RGBA allocation."""
    import warnings
    if len(raw)>15*1024*1024:raise HTTPException(413,'图片文件超过 15 MB')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as source:
                if source.width*source.height > 80000000:
                    raise HTTPException(413,'图片超过 8000 万像素，请选择较小的照片')
                # JPEG draft decoding is applied by thumbnail before full loading.
                source.thumbnail((2400,2400),Image.Resampling.LANCZOS)
                source = ImageOps.exif_transpose(source).convert('RGBA')
                white = Image.new('RGBA',source.size,'white');white.alpha_composite(source)
                out=io.BytesIO();white.convert('RGB').save(out,format='JPEG',quality=95)
                return out.getvalue()
    except HTTPException:raise
    except (Image.DecompressionBombError,Image.DecompressionBombWarning) as exc:
        raise HTTPException(413,'图片像素过大，请选择 8000 万像素以内的照片') from exc
    except UnidentifiedImageError as exc:
        raise HTTPException(400,'无法识别图片格式，请使用 JPG、PNG 或 WebP；HEIC/HEIF 请先导出为 JPG') from exc
    except (OSError,ValueError) as exc:
        raise HTTPException(400,'图片解码失败，文件可能损坏，请重新上传或导出为 JPG') from exc

def save(raw):
    content=normalize(raw); name=hashlib.sha256(content).hexdigest()+'.jpg'
    path=directory()/name
    if not path.exists():
        import tempfile
        with tempfile.NamedTemporaryFile(dir=directory(),suffix='.tmp',delete=False) as temp:
            temporary=Path(temp.name);temp.write(content)
        try:os.replace(temporary,path)
        finally:temporary.unlink(missing_ok=True)
    return name

def photos():
    return sorted(p for p in directory().iterdir() if not p.is_symlink() and p.is_file() and p.suffix.lower() in {'.jpg','.jpeg','.png','.webp'})

def slot_at(now):
    now=now.astimezone(ZoneInfo('Asia/Shanghai'))
    hours=[hour for hour in (6,14,20) if hour<=now.hour]
    return f'{now:%Y-%m-%d}T{max(hours):02d}:00' if hours else None

async def rotate(now=None, force=False):
    from . import ink
    device=os.getenv('INK_DEVICE_ID','desk-01')
    slot=slot_at(now or datetime.now(ZoneInfo('Asia/Shanghai')))
    if not slot and not force:return {'skipped':'before first slot'}
    if not os.getenv('INK_API_BASE') or not os.getenv('INK_TOKEN'):return {'skipped':'not configured'}
    import fcntl
    with (directory()/'.rotation.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return {'skipped':'rotation running'}
        with state_db() as db:
            previous=db.execute('SELECT slot,photo FROM rotation WHERE device=?',(device,)).fetchone()
        if previous and previous[0]==slot and not force:return {'skipped':'already sent'}
        candidates=photos()
        if previous:
            names=[p.name for p in candidates]
            if previous[1] in names:
                start=(names.index(previous[1])+1)%len(names);candidates=candidates[start:]+candidates[:start]
        for photo in candidates:
            try:
                if photo.stat().st_size>15*1024*1024:continue
                raw=await asyncio.to_thread(normalize,photo.read_bytes())
            except (OSError,HTTPException):
                log.warning('Skipping invalid ink background: %s',photo.name);continue
            data=await ink.today_data();data.background=base64.b64encode(raw).decode()
            rendered=await asyncio.to_thread(ink.render_today,data)
            result=await ink.forward(device,rendered)
            with state_db() as db:
                db.execute('INSERT INTO rotation VALUES(?,?,?) ON CONFLICT(device) DO UPDATE SET slot=excluded.slot,photo=excluded.photo',(device,slot,photo.name))
            return {**result,'background':photo.name}
        return {'skipped':'no valid photos'}

async def loop():
    while True:
        try:await rotate()
        except Exception:log.exception('Ink rotation failed; retrying in 60 seconds')
        await asyncio.sleep(60)
