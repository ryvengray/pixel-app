"""Deterministic desk letters: no network, credentials or device wakeups."""
from datetime import date, timedelta
from itertools import permutations
from typing import Literal

from pydantic import BaseModel, Field


class Letter(BaseModel):
    day: date
    layout: Literal['orbit', 'postcard', 'notebook']
    theme: Literal['space', 'animal', 'music']
    category: str = Field(max_length=20)
    title: str = Field(max_length=24)
    body: str = Field(max_length=180)
    invitation: str = Field(max_length=40)
    echo_title: str = Field(max_length=30)
    echo: str = Field(max_length=120)


ANIMALS = ('水獭', '小鹿', '狐狸', '熊猫', '小兔', '企鹅', '松鼠', '小猫')
PLACES = ('泡泡星', '纸飞机星', '积木星', '彩虹星', '蘑菇星', '风铃星', '迷宫星', '云朵星')
OBJECTS = ('一卷绳子', '一面小鼓', '一张白纸', '一支画笔', '一把小伞', '一只纸杯')
OBSERVATIONS = (
    ('窗边的探险家', '假装你是一只刚到地球的小动物。看看窗外，找一样你会好奇的东西。', '它会问人类什么问题？'),
    ('动物的新房间', '给一只想象中的小动物设计房间。它要能睡觉、藏东西，还能玩。', '你会把秘密入口放在哪里？'),
    ('脚印侦探', '画出三组自己发明的脚印：慢慢走、飞快跑、偷偷溜过去。', '不画动物，别人能猜出它在做什么吗？'),
    ('一片叶子的旅行', '假装你是一只很小的动物，把一片落叶当成交通工具。', '它会变成船、滑板，还是别的东西？'),
    ('书桌上的森林', '从书桌上选三样东西，想象它们变成了森林里的住所。', '橡皮、笔筒和书，各能住谁？'),
    ('发明一种新动物', '画一只从没见过的动物。给它一种特别的本领，也给它一个小烦恼。', '它的本领能解决自己的烦恼吗？'),
)
MUSIC = (
    ('名字会唱歌', '给名字的每个字配一个动作：拍手、拍腿，或者停一下。把它连起来试试。', '另一个人的名字，节奏会怎样？'),
    ('安静也是音乐', '试试这段节奏：拍手、停一下、拍腿、拍腿。再把停顿换个位置。', '哪一种更像飞船起飞？'),
    ('桌边小乐队', '轻轻敲敲自己的手心，再轻轻敲敲桌面。用两种声音编一段开场音乐。', '让家人接上下一小段吧。'),
    ('给动物写前奏', '想象一只小动物先慢慢走，再突然跑起来。用拍手给它配一小段音乐。', '不用说话，别人能听懂吗？'),
    ('倒着走的节奏', '拍手、拍腿、停一下、拍手。先照着做，再把动作顺序倒过来。', '你能发明一段正反都一样的节奏吗？'),
    ('天气作曲家', '给晴天和雨天各编一小段节奏。可以拍手、轻点桌面，也可以留出安静。', '不说天气名字，请家人猜一猜。'),
    ('一句话的旋律', '把“今天有一封宇宙来信”唱成两种样子：像在问问题，像在讲秘密。', '同一句话，还能唱出什么心情？'),
    ('节奏传话', '编一段只有四个动作的节奏，表演给家人看，让他接着重复。', '每轮只改一个动作，会变成什么？'),
)


def puzzle(day: date):
    """Return a question and checked unique answer; difficulty stays age-appropriate."""
    week = (day.toordinal() - 1) // 7
    kind = (week + (day.weekday() == 3)) % 3
    if kind == 0:
        labels = ('星星', '月亮', '太阳', '火箭', '云朵', '音符')
        picks = list(permutations(labels, 3))
        a, b, c = picks[(week // 3) % len(picks)]
        solutions = [p for p in permutations((a, b, c))
                     if abs(p.index(a) - p.index(b)) != 1
                     and abs(p.index(c) - p.index(a)) == 1]
        middles = {p[1] for p in solutions}
        assert middles == {c}
        return ('月球邮局的贴纸', f'{a}、{b}、{c}三张贴纸排成一行。\n{a}不能挨着{b}，{c}必须挨着{a}。谁一定在中间？',
                f'{c}。{a}和{b}不能相邻，只能放在两端，中间就是{c}。')
    if kind == 1:
        # Each animal has a different positive whole number of tickets.
        total = 9 + week % 5
        difference = 1 + week % 3
        if (total - difference) % 2:
            total += 1
        a, b = ANIMALS[week % 8], ANIMALS[(week + 3) % 8]
        solutions = [(x, total - x) for x in range(1, total)
                     if x - (total - x) == difference]
        assert len(solutions) == 1
        x, y = solutions[0]
        return ('两位乘客的船票', f'{a}和{b}一共有{total}张船票。\n{a}比{b}多{difference}张。它们各有几张？可以画小圆点试试。',
                f'{a}{x}张，{b}{y}张。先留出多的{difference}张，剩下的平分，再把留出的还给{a}。')
    start, jump, back, turns = week % 4, 3 + week % 3, 1 + week % 2, 3 + week % 2
    answer = start + (turns - 1) * (jump - back) + jump
    return ('机器人的最后一步', f'机器人站在第{start}格。它每轮先前进{jump}格，再后退{back}格。\n第{turns}次前进刚结束，还没后退时，它在第几格？',
            f'第{answer}格。前{turns - 1}轮每轮前进{jump - back}格，最后再前进{jump}格，这次还不用后退。')


def entry(day: date):
    week = (day.toordinal() - 1) // 7
    weekday = day.weekday()
    if weekday in (0, 3):
        title, body, answer = puzzle(day)
        return dict(theme='space', category='星际谜题', title=title, body=body,
                    invitation='想一想，明天的来信揭晓。', answer=answer)
    if weekday == 1:
        title, body, invitation = OBSERVATIONS[week % len(OBSERVATIONS)]
        return dict(theme='animal', category='动物想象所', title=title, body=body,
                    invitation=invitation, answer='这封信没有标准答案。你的小动物，会怎样介绍自己的发现？')
    if weekday == 2:
        title, body, invitation = MUSIC[week % len(MUSIC)]
        return dict(theme='music', category='声音实验室', title=title, body=body,
                    invitation=invitation, answer='停顿和动作都可以成为节奏。昨天你最喜欢自己编的哪一小段？')
    animal, place = ANIMALS[week % 8], PLACES[(week // 8) % 8]
    item = OBJECTS[(week // 64) % len(OBJECTS)]
    if weekday == 4:
        return dict(theme='space', category='周五故事 · 抵达', title=f'{animal}船长的新来信',
                    body=f'{animal}船长来到{place}。入口没有门铃，只有一块牌子：“请用一种特别的方式打招呼。”\n船长带着{item}，还带着好奇心。',
                    invitation='如果是你，会怎么打招呼？', answer='打招呼可以有好多种办法。你的办法，会让星球上的居民怎样回应？')
    if weekday == 5:
        return dict(theme='animal', category='周末故事 · 回信', title=f'{place}的回应',
                    body=f'{animal}船长试着轻敲飞船，停了一下，又敲两下。地面忽然亮起同样的节奏！\n原来，{place}正在用自己的方式打招呼。',
                    invitation='用一幅画，给这个星球回封信吧。', answer='一幅画也能是一封信。你想把昨天的画送给谁看？')
    return dict(theme='animal', category='周日 · 家里的展览', title='今天，你是来信人',
                body=f'这一周，{animal}船长去了{place}。现在轮到你：选一幅画、一件积木作品，或一张喜欢的照片，讲讲它的故事。',
                invitation='可以请家人把作品放进下一封来信。', answer='你的作品不需要标准答案。谢谢你，让这封宇宙来信有了新的故事。')


def daily_letter(day: date | None = None) -> Letter:
    if day is None:
        from datetime import datetime
        from zoneinfo import ZoneInfo
        day = datetime.now(ZoneInfo('Asia/Shanghai')).date()
    current = entry(day)
    yesterday = day - timedelta(days=1)
    previous = entry(yesterday)
    return Letter(day=day, layout=('orbit', 'postcard', 'notebook')[day.toordinal() % 3],
                  **{k: v for k, v in current.items() if k != 'answer'},
                  echo_title=('昨日谜底' if yesterday.weekday() in (0, 3) else '昨日回声') + f' · {yesterday.month}/{yesterday.day}',
                  echo=previous['answer'])
