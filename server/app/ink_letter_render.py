"""Three six-color editorial layouts for the 800 × 480 desk display."""
import base64
import io
import os

from fastapi import HTTPException
from PIL import Image, ImageDraw, ImageFont, ImageOps

COLORS = [(255,255,255), (0,0,0), (255,0,0), (255,255,0), (0,255,0), (0,0,255)]


def palette():
    result = Image.new('P', (1, 1))
    result.putpalette([v for rgb in COLORS for v in rgb] + [255] * (768 - 18))
    return result


def illustration(draw, box, theme, seed):
    """Draw in native ink colors; silhouettes remain clear without a photograph."""
    x, y, w, h = box
    def rect(coords, **kwargs):
        draw.rounded_rectangle(tuple(int(v) for v in coords), **kwargs)
    accent = 'blue' if theme == 'space' else 'red' if theme == 'music' else 'green'
    for i in range(9):
        sx, sy = x + 12 + (i * 67 + seed * 13) % (w - 24), y + 12 + (i * 43) % (h - 24)
        draw.line((sx-3, sy, sx+3, sy), fill='black', width=2)
        draw.line((sx, sy-3, sx, sy+3), fill='black', width=2)
    cx, cy = x + w // 2, y + h // 2
    draw.ellipse((cx-80, cy-64, cx+80, cy+96), fill='yellow')
    if theme == 'space':
        # An astronaut rabbit in a helmet, with an orbit and a red rocket fin.
        draw.arc((cx-105, cy-45, cx+105, cy+78), 5, 170, fill='black', width=3)
        rect((cx-43, cy+44, cx+43, cy+105), radius=14, fill='white', outline='black', width=3)
        draw.ellipse((cx-66, cy-73, cx+66, cy+59), fill='white', outline='black', width=4)
        rect((cx-31, cy-57, cx-9, cy-8), radius=10, fill='white', outline='black', width=3)
        rect((cx+9, cy-57, cx+31, cy-8), radius=10, fill='white', outline='black', width=3)
        draw.ellipse((cx-42, cy-23, cx+42, cy+45), fill='white', outline='black', width=3)
        rect((cx-19, cy+69, cx+19, cy+87), radius=3, fill=accent)
    elif theme == 'animal':
        draw.polygon([(cx-63,cy+7),(cx-59,cy-76),(cx-10,cy-25)], fill='white', outline='black', width=3)
        draw.polygon([(cx+63,cy+7),(cx+59,cy-76),(cx+10,cy-25)], fill='white', outline='black', width=3)
        draw.ellipse((cx-65,cy-36,cx+65,cy+85), fill='white', outline='black', width=4)
        draw.arc((cx-88,cy+58,cx+88,cy+135), 180, 355, fill=accent, width=9)
    else:
        draw.ellipse((cx-58,cy-55,cx+58,cy+65), fill='white', outline='black', width=3)
        draw.arc((cx-73,cy-75,cx+73,cy+62), 180, 360, fill='black', width=7)
        for dx in (-76, 58):
            rect((cx+dx,cy-15,cx+dx+18,cy+33), radius=7, fill=accent, outline='black', width=3)
        for dx, dy in ((-80,68),(65,80)):
            draw.line((cx+dx,cy+dy,cx+dx,cy+dy-35,cx+dx+18,cy+dy-40), fill='black', width=3)
            draw.ellipse((cx+dx-11,cy+dy-3,cx+dx+1,cy+dy+5), fill='black')
    for dx in (-19, 19):
        draw.ellipse((cx+dx-3,cy+6,cx+dx+3,cy+14), fill='black')
    draw.arc((cx-10,cy+16,cx+10,cy+29), 0, 180, fill='black', width=2)


def render_letter(data):
    letter = data.letter
    image = Image.new('RGB', (800,480), 'white')
    draw = ImageDraw.Draw(image)
    font_path = os.getenv('INK_FONT_PATH', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
    fonts = {}

    def text(x, y, value, size, width, lines=1):
        font = fonts.setdefault(size, ImageFont.truetype(font_path, size))
        wrapped = ['']
        for char in value:
            if char == '\n' or draw.textlength(wrapped[-1] + char, font=font) > width:
                if len(wrapped) == lines:
                    while wrapped[-1] and draw.textlength(wrapped[-1] + '…', font=font) > width:
                        wrapped[-1] = wrapped[-1][:-1]
                    wrapped[-1] += '…'
                    break
                wrapped.append('' if char == '\n' else char)
            else:
                wrapped[-1] += char
        for index, line in enumerate(wrapped):
            draw.text((x, y + index * (size+8)), line, fill='black', font=font)

    # Shared unobtrusive date/weather header. No live clock or stale countdowns.
    day = letter.day
    weekday = '一二三四五六日'[day.weekday()]
    text(28, 14, f'{day.month:02d}.{day.day:02d}  周{weekday}  /  宇宙来信', 17, 345)
    weather = '天气暂未更新'
    if data.low is not None and data.high is not None:
        weather = f'{data.location} {data.low:g}–{data.high:g}° {data.weather}'
    text(390, 17, weather.strip(), 14, 380)
    draw.line((28,48,772,48), fill='black', width=2)

    if letter.layout == 'orbit':
        art = (24, 82, 256, 258)
        tx, width, title_y, body_y = 312, 456, 103, 151
        text(312, 70, letter.category + ' / 01', 15, width)
        draw.ellipse((26,65,90,129), fill='yellow')
    elif letter.layout == 'postcard':
        art = (528, 78, 248, 264)
        tx, width, title_y, body_y = 36, 460, 103, 151
        text(36, 70, '来自好奇心号 / ' + letter.category, 15, width)
        draw.rectangle((544,62,766,348), outline='black', width=2)
    else:
        art = (24, 90, 248, 246)
        tx, width, title_y, body_y = 304, 464, 103, 151
        text(304, 70, letter.category + ' / 灵感手记', 15, width)
        for y in (320,332,344):
            draw.line((40,y,258,y), fill='black', width=1)

    if data.background:
        from .ink_gallery import normalize
        try:
            raw = base64.b64decode(data.background.split(',')[-1], validate=True)
        except ValueError as exc:
            raise HTTPException(400, '背景图片编码无效，请重新上传') from exc
        with Image.open(io.BytesIO(normalize(raw))) as source:
            # Preserve the user's complete 5:3 crop; never crop it a second time.
            photo = ImageOps.contain(source.convert('RGB'), (art[2], art[3]), Image.Resampling.LANCZOS)
            photo = photo.quantize(palette=palette(), dither=Image.Dither.FLOYDSTEINBERG).convert('RGB')
        image.paste(photo, (art[0]+(art[2]-photo.width)//2, art[1]+(art[3]-photo.height)//2))
        text(art[0]+8, 332, '家里的小小展览', 14, art[2]-16)
    else:
        illustration(draw, art, letter.theme, day.toordinal())
    text(tx, title_y, letter.title, 27, width)
    text(tx, body_y, letter.body, 22, width, lines=5)
    text(tx, 314, letter.invitation, 17, width, lines=2)

    draw.line((28,365,772,365), fill='black', width=2)
    text(30, 376, letter.echo_title, 15, 700)
    text(30, 402, letter.echo, 18, 738, lines=2)
    # A reminder is optional and capped at one; never displace the letter.
    if data.todos:
        text(30, 458, '小提醒 · ' + data.todos[0], 13, 738)
    else:
        text(30, 458, '好奇就很好。今天也可以只看看。', 13, 738)
    # Decorations use native colors; text is quantized without colored edge noise.
    image = image.quantize(palette=palette(), dither=Image.Dither.NONE).convert('RGB')
    output = io.BytesIO()
    image.save(output, format='PNG')
    return output.getvalue()
