"""At-most-once BLE mouse clicks with Android cursor-layer position feedback."""
import asyncio
import io
import json
import math
import re
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path

import httpx
from fastapi import HTTPException
from PIL import Image, ImageChops, ImageStat


SPRITE = re.compile(r'  Layer \[(\d+)\] \[BBQ\] Sprite[^\n]*\n(?:(?!  Layer ).*\n){0,5}?[^\n]*toDisplayTransform=\{ tx=([\d.\-]+) ty=([\d.\-]+)')


def sprite_position(text):
    matches = SPRITE.findall(text)
    if len(matches) != 1:
        raise HTTPException(409, '无法唯一确定蓝牙鼠标光标的位置，已停止点击')
    layer, x, y = matches[0]
    point = (float(x), float(y))
    if not all(math.isfinite(v) for v in point):
        raise HTTPException(409, '光标坐标无效，已停止点击')
    return layer, point


def window_focus(text):
    match = re.search(r'mCurrentFocus=Window\{[^\n]*? u\d+ ([^}\n]+)\}', text)
    if not match:
        raise HTTPException(409, '无法确认当前手机页面，请解锁手机后重试')
    return match[1]


def target_difference(before, after, bounds):
    """Compare the target before moving the pointer over it; return normalized MAD."""
    with Image.open(io.BytesIO(before)) as old, Image.open(io.BytesIO(after)) as new:
        if old.size != new.size:
            raise HTTPException(409, '屏幕尺寸或方向已变化，请重新识别')
        left, top, right, bottom = bounds
        if not 0 <= left < right <= old.width or not 0 <= top < bottom <= old.height:
            raise HTTPException(409, '目标框无效，请重新识别')
        a = old.convert('RGB').crop(bounds).resize((48, 48))
        b = new.convert('RGB').crop(bounds).resize((48, 48))
        return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / (3 * 255)


class BluetoothClicker:
    def __init__(self, adb, serial, token, capture, prepare_image, db_path=None):
        self.adb, self.serial, self.token = adb, serial, token
        self.capture, self.prepare_image = capture, prepare_image
        self.path = Path(db_path or Path.home()/'.local/share/pixel-vision/clicks.db')
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.active = False
        self.tasks = set()
        self.calibration = None
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS click_job (id TEXT PRIMARY KEY, result_id TEXT NOT NULL, state TEXT NOT NULL, message TEXT NOT NULL, created REAL NOT NULL, updated REAL NOT NULL, result TEXT)')
            db.execute('CREATE UNIQUE INDEX IF NOT EXISTS click_result_once ON click_job(result_id)')
            db.execute("UPDATE click_job SET state='unknown',message='视觉服务曾重启，请检查手机；不会自动重复点击',updated=? WHERE state='running'", (time.time(),))

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def job(self, job_id):
        with self.db() as db:
            row = db.execute('SELECT * FROM click_job WHERE id=?', (job_id,)).fetchone()
        if not row:
            raise HTTPException(404, '未找到这次点击任务')
        data = dict(row)
        data['result'] = json.loads(data['result']) if data['result'] else None
        return data

    def update(self, job_id, message, state='running', result=None):
        with self.db() as db:
            db.execute('UPDATE click_job SET state=?,message=?,updated=?,result=? WHERE id=?',
                       (state, message, time.time(), json.dumps(result, ensure_ascii=False) if result is not None else None, job_id))

    def submit(self, job_id, result_id, record):
        with self.db() as db:
            old = db.execute('SELECT * FROM click_job WHERE id=?', (job_id,)).fetchone()
            if old:
                if old['result_id'] != result_id:
                    raise HTTPException(409, '点击任务 ID 已用于另一个目标')
                return self.job(job_id)
            if db.execute('SELECT 1 FROM click_job WHERE result_id=?', (result_id,)).fetchone():
                raise HTTPException(409, '这个定位结果已用于一次点击任务，请重新识别')
            if self.active:
                raise HTTPException(409, '正在执行另一次蓝牙点击，请等待完成')
            if not record or time.time()-record['ready_at'] > 90 or time.time()-record['captured_at'] > 180:
                raise HTTPException(409, '定位结果已过期，请重新识别后点击')
            if not record['found']:
                raise HTTPException(409, '没有可点击的识别目标')
            now = time.time()
            db.execute('INSERT INTO click_job VALUES(?,?,?,?,?,?,NULL)',
                       (job_id, result_id, 'running', '正在检查手机及蓝牙连接…', now, now))
        self.active = True
        task = asyncio.create_task(self.run(job_id, record))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return self.job(job_id)

    async def adb_text(self, *args):
        proc = await asyncio.create_subprocess_exec(self.adb, '-s', self.serial, *args,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            out, _ = await asyncio.wait_for(proc.communicate(), 12)
        except BaseException:
            if proc.returncode is None:
                proc.kill()
            await proc.wait()
            raise
        if proc.returncode:
            raise HTTPException(503, '无线调试读取失败，未继续点击')
        return out.decode(errors='replace')

    async def focus(self):
        # On this Android release the "windows" subcommand omits current focus.
        return window_focus(await self.adb_text('shell', 'dumpsys', 'window'))

    async def pointer(self):
        text = await self.adb_text('shell', 'dumpsys', 'input')
        types = re.findall(r'resolvedPointerType: (\d+)', text)
        if types != ['1000']:
            self.calibration = None
            raise HTTPException(409, '鼠标光标样式已变化，请将手机停留在普通页面后重新识别')
        return sprite_position(await self.adb_text('shell', 'dumpsys', 'SurfaceFlinger'))

    async def api(self, client, method, path, payload=None):
        response = await client.request(method, 'http://127.0.0.1:8787/v1/phone-control'+path,
            headers={'Authorization': 'Bearer '+self.token}, json=payload)
        if response.status_code != 200:
            try:
                detail = response.json().get('detail', '蓝牙控制请求失败')
            except ValueError:
                detail = '蓝牙控制请求失败'
            raise HTTPException(response.status_code, detail)
        return response.json()

    async def ready(self, client, job_id, boot=None):
        status = await self.api(client, 'GET', '/status')
        device = status.get('device') or {}
        if not status.get('online') or not device.get('armed') or not device.get('ble_connected'):
            raise HTTPException(409, 'ESP32 已离线、暂停或蓝牙断开，已停止点击')
        if boot is not None and device.get('boot_id') != boot:
            self.calibration = None
            raise HTTPException(409, 'ESP32 已重启，请重新识别后再点击')
        if any(c['state'] in ('queued', 'dispatched') and not c['id'].startswith(job_id+'_') for c in status.get('commands', [])):
            raise HTTPException(409, '存在其他键鼠指令，请等待结束后重新识别')
        return device['boot_id']

    async def command(self, client, job_id, boot, step, action):
        await self.ready(client, job_id, boot)
        command_id = job_id+'_'+str(step)
        # The same ID is never replayed. A lost response stops this job.
        await self.api(client, 'POST', '/commands', {'id': command_id, 'action': action, 'ttl_seconds': 8})
        deadline = time.monotonic()+10
        while time.monotonic() < deadline:
            data = await self.api(client, 'GET', '/commands/'+command_id)
            if data['state'] == 'succeeded':
                return
            if data['state'] not in ('queued', 'dispatched'):
                raise HTTPException(409, '蓝牙指令未确认成功：'+data['state']+'；请检查手机，不会自动重试')
            await asyncio.sleep(.15)
        raise HTTPException(409, '蓝牙指令结果未知，请检查手机；不会自动重试')

    async def run(self, job_id, record):
        clicking = False
        step = 0
        try:
            async with httpx.AsyncClient(timeout=12, trust_env=False) as client:
                boot = await self.ready(client, job_id)
                if await self.focus() != record['focus']:
                    raise HTTPException(409, '前台页面已变化，请重新识别')
                fresh = await self.capture()
                difference = target_difference(record['raw'], fresh, record['bounds'])
                if difference > .22:
                    raise HTTPException(409, '目标区域画面已变化，请重新识别后点击')
                width, height = record['width'], record['height']
                if width >= height:
                    raise HTTPException(409, '当前蓝牙坐标点击仅验证过竖屏，请切回竖屏后重试')
                async def move(dx, dy):
                    nonlocal step
                    step += 1
                    await self.command(client, job_id, boot, step, {'type': 'move', 'dx': dx, 'dy': dy})
                    return await self.pointer()

                # Wake the pointer without clicking. Calibration is keyed to layer/boot/size.
                layer, origin = await move(1, 0)
                cache = self.calibration
                if not cache or cache['key'] != (boot, width, height, layer) or time.time()-cache['time'] > 300:
                    self.update(job_id, '首次点击：正在校准蓝牙鼠标，不会点击屏幕…')
                    previous = None
                    for _ in range(24):
                        layer, origin = await move(-127, -127)
                        if previous is not None and max(abs(origin[i]-previous[i]) for i in range(2)) < .1:
                            break
                        previous = origin
                    else:
                        raise HTTPException(409, '鼠标校准未收敛，已停止点击')
                    hotspot = (-origin[0], -origin[1])
                    if not all(0 <= v <= 128 for v in hotspot):
                        raise HTTPException(409, '无法验证光标热点，已停止点击')
                    cache = {'key': (boot, width, height, layer), 'time': time.time(), 'hotspot': hotspot}
                    self.calibration = cache
                hotspot = cache['hotspot']
                point = record['point']
                position = tuple(origin[i]+hotspot[i] for i in range(2))
                gain = [2.3, 2.3]
                for _ in range(24):
                    error = (point['x']-position[0], point['y']-position[1])
                    if max(abs(v) for v in error) <= 3:
                        break
                    self.update(job_id, '正在移动蓝牙鼠标到目标 ('+str(point['x'])+', '+str(point['y'])+')…')
                    delta = [0 if abs(error[i]) <= 3 else max(-127, min(127, round(error[i]/gain[i]) or (1 if error[i]>0 else -1))) for i in range(2)]
                    new_layer, new_origin = await move(*delta)
                    if new_layer != layer:
                        self.calibration = None
                        raise HTTPException(409, '光标已重建，已停止点击，请重新识别')
                    new_position = tuple(new_origin[i]+hotspot[i] for i in range(2))
                    for i in range(2):
                        moved = new_position[i]-position[i]
                        if delta[i] and moved*delta[i] > 0:
                            gain[i] = max(.5, min(5, abs(moved/delta[i])))
                    if max(abs(new_position[i]-position[i]) for i in range(2)) < .1:
                        raise HTTPException(409, '光标未移动，已停止点击')
                    position = new_position
                else:
                    raise HTTPException(409, '光标未能到达目标，已停止点击')
                if time.time()-record['captured_at'] > 180 or await self.focus() != record['focus']:
                    raise HTTPException(409, '页面或定位有效期已变化，请重新识别')
                # Recheck actual position immediately before submitting the single click.
                current_layer, current_origin = await self.pointer()
                actual = tuple(current_origin[i]+hotspot[i] for i in range(2))
                if current_layer != layer or max(abs(actual[i]-(point['x'],point['y'])[i]) for i in range(2)) > 3:
                    raise HTTPException(409, '点击前光标位置已变化，已停止点击')
                self.update(job_id, '光标已到达目标，正在发送一次蓝牙点击…')
                clicking = True
                step += 1
                await self.command(client, job_id, boot, step, {'type': 'click', 'button': 1})
                self.update(job_id, '蓝牙点击报告已发送，正在获取操作后的画面…')
                await asyncio.sleep(.5)
                result = {'point': point, 'actual_pointer': {'x': round(actual[0],2), 'y': round(actual[1],2)},
                          'transport': 'esp32_ble', 'report_sent': True, 'business_confirmed': False,
                          'width': width, 'height': height}
                try:
                    after = await self.capture()
                    (w,h), image = self.prepare_image(after)
                    result.update(image_base64=image, width=w, height=h)
                except Exception:
                    result['preview_unavailable'] = True
                self.update(job_id, '蓝牙点击报告已发送，请查看新画面确认手机上的实际结果。', 'succeeded', result)
        except asyncio.CancelledError:
            self.update(job_id, '任务被中断，请检查手机；不会自动重复点击', 'unknown' if clicking else 'failed')
            raise
        except Exception as exc:
            message = exc.detail if isinstance(exc, HTTPException) else '控制链路中断，请检查手机；不会自动重复点击'
            self.update(job_id, str(message), 'unknown' if clicking else 'failed')
            self.calibration = None
        finally:
            self.active = False
