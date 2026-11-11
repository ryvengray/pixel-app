"""Model-controlled phone tasks; no real LLM, ADB or HID traffic."""
import asyncio
import json
import time
from unittest.mock import AsyncMock

import pytest

from server.app import phone_agent as agent
from server.app.phone_input import typing_units

ELEMENT = {'element_id': 'b'*32, 'result_id': 'b'*32, 'label': '可见按钮', 'expires_at': time.time()+3600}

ORIGINAL_POLL = agent.PhoneExecutor.poll


def call(name, **args):
    return {'role': 'assistant', 'content': None, 'tool_calls': [
        {'id': 'call-'+name, 'type': 'function',
         'function': {'name': name, 'arguments': json.dumps(args, ensure_ascii=False)}}]}


@pytest.fixture
def configured(monkeypatch, tmp_path):
    monkeypatch.setenv('PHONE_CONTROL_DB_PATH', str(tmp_path/'phone.db'))
    monkeypatch.setenv('PHONE_CONTROL_ADMIN_TOKEN', 'a'*32)
    monkeypatch.setattr(agent.pc, 'get_status', lambda: {
        'online': True, 'device': {'boot_id': 'boot1', 'armed': True, 'ble_connected': True}, 'commands': []})
    async def vision(method, path, payload=None):
        if path == '/observe':
            return {'description': '当前有弹窗，关闭后才能继续', 'visible_text': ['关闭'], 'controls': ['右上角关闭'],
                    'keyboard_visible': False, 'focus': 'any.app/Page', 'elements': [ELEMENT]}
        if path == '/locate':
            return {'found': True, 'result_id': 'a'*32}
        if path == '/click':
            return {'state': 'running'}
        if path.startswith('/click/'):
            return {'state': 'succeeded'}
        if path == '/verify':
            return {'matched': True, 'reason': '可见证据'}
        raise AssertionError(path)
    mock = AsyncMock(side_effect=vision)
    monkeypatch.setattr(agent.pc, 'vision_request', mock)
    async def poll(self, query, active, timeout, action_id=None):
        result = await query()
        if result.get('state') != 'succeeded':
            raise agent.Stopped('回执未知')
        return result
    monkeypatch.setattr(agent.PhoneExecutor, 'poll', poll)
    return mock


async def await_worker(task_id):
    task = agent.WORKERS.get(task_id)
    if task:
        await task
    return agent.status(task_id)


def test_model_chooses_next_action_from_observation(configured):
    async def run():
        decisions = []
        async def model(messages, tools):
            assert 'click_element' in {t['function']['name'] for t in tools}
            last = messages[-1]
            if last['role'] == 'user':
                answer = call('observe_screen', objective='查看实际页面')
            else:
                data = json.loads(last['content'])
                if 'description' in data and '弹窗' in data['description']:
                    answer = call('click_element', element_id=ELEMENT['element_id'], expected_after='弹窗已关闭')
                elif data.get('postcondition_matched'):
                    answer = call('observe_screen', objective='检查关闭结果')
                    # Vary the observed page; the model's next action must depend on it.
                    configured.side_effect = lambda method, path, payload=None: (
                        {'description': '目标设置已经打开', 'controls': [], 'visible_text': []} if path == '/observe'
                        else {'matched': True, 'reason': '设置页可见'})
                else:
                    answer = call('finish_task', evidence='目标设置页面可见', summary='已打开设置')
            decisions.append(answer['tool_calls'][0]['function']['name'])
            return answer
        started = await agent.start('打开指定设置页面', 'a'*64, '用户原始目标', model)
        assert started['state'] == 'running'
        result = await await_worker(started['task_id'])
        assert result['state'] == 'succeeded'
        assert decisions == ['observe_screen', 'click_element', 'observe_screen', 'finish_task']
        assert result['business_confirmed'] is True
        assert not any('微信' in str(c) for c in configured.call_args_list)
    asyncio.run(run())


def test_idempotent_start_and_restart_never_replays(configured):
    async def run():
        blocker = asyncio.Event()
        async def model(*args):
            await blocker.wait()
            return call('stop_task', reason='测试停止')
        first = await agent.start('发消息', 'b'*64, '原始消息', model)
        second = await agent.start('发消息', 'b'*64, '原始消息', model)
        assert second['task_id'] == first['task_id']
        assert len(agent.WORKERS) == 1
        other = await agent.start('其他任务', 'c'*64, '其他任务', model)
        assert other['ok'] is False
        await agent.shutdown()
        agent.recover()
        before = len(configured.call_args_list)
        result = await agent.start('发消息', 'b'*64, '原始消息', model)
        assert result['state'] == 'unknown'
        assert not agent.WORKERS
        assert len(configured.call_args_list) == before
    asyncio.run(run())


def test_unknown_click_stops_without_another_model_decision(configured):
    original = configured.side_effect
    async def vision(method, path, payload=None):
        if path.startswith('/click/'):
            return {'state': 'unknown'}
        return await original(method, path, payload)
    configured.side_effect = vision
    model = AsyncMock(side_effect=[call('observe_screen', objective='查看界面'),
                                   call('click_element', element_id=ELEMENT['element_id'], expected_after='新消息气泡可见')])
    async def run():
        started = await agent.start('发送指定消息', 'd'*64, '原始目标', model)
        result = await await_worker(started['task_id'])
        assert result['state'] == 'stopped'
        assert model.await_count == 2
        assert sum(c.args[1] == '/click' for c in configured.call_args_list) == 1
        assert any(e['kind'] == 'click_intent' for e in result['events'])
    asyncio.run(run())


def test_chinese_typing_primitive_is_independent_of_app(configured, monkeypatch):
    keys = []
    monkeypatch.setattr(agent.pc, 'enqueue', lambda request: keys.append(request.action.model_dump()))
    monkeypatch.setattr(agent.pc, 'command_status', lambda _: {'state': 'succeeded'})
    async def run():
        finished = asyncio.Event()
        async def model(*args):
            await finished.wait()
            return call('stop_task', reason='测试结束')
        # Create a persisted task without allowing the background worker to run yet.
        started = await agent.start('输入文字', 'e'*64, '原文', model)
        executor = agent.PhoneExecutor(started['task_id'])
        executor.observed_at = __import__('time').monotonic()
        result = await executor.execute('type_text', {'text': '明天', 'field': '某应用输入框'})
        assert result['message_sent'] is False
        assert keys == [{'type': 'text', 'text': 'ming'}, {'type': 'text', 'text': 'tian'}]
        finished.set()
        await await_worker(started['task_id'])
    asyncio.run(run())


def test_missing_observation_blocks_input(configured):
    async def run():
        model = AsyncMock(return_value=call('click_element', element_id=ELEMENT['element_id'], expected_after='下一页'))
        started = await agent.start('操作手机', 'f'*64, '原始任务', model)
        result = await await_worker(started['task_id'])
        assert result['state'] == 'stopped'
        assert not configured.called
    asyncio.run(run())


def test_text_validation_and_ime_units():
    assert typing_units('张三') == [('张', 'zhang'), ('三', 'san')]
    assert typing_units('hello world') == [('hello world', None)]
    for text in ['你好🙂', 'hello\n', '', '明天。']:
        with pytest.raises(ValueError):
            typing_units(text)


def test_finish_requires_visual_evidence(configured):
    async def reject(*args, **kwargs):
        return {'matched': False, 'reason': '没有新消息气泡'}
    configured.side_effect = reject
    model = AsyncMock(return_value=call('finish_task', evidence='发送成功', summary='已发送'))
    async def run():
        started = await agent.start('发送消息', '1'*64, '原始请求', model)
        result = await await_worker(started['task_id'])
        assert result['state'] == 'stopped'
        assert not result['business_confirmed']
    asyncio.run(run())


def test_complete_trace_has_step_spans_evidence_and_timings(configured):
    model = AsyncMock(side_effect=[
        call('observe_screen', objective='检查当前页面', decision_summary='先确认实际页面'),
        call('click_element', element_id=ELEMENT['element_id'], expected_after='弹窗消失', decision_summary='弹窗遮挡入口，先关闭'),
        call('finish_task', evidence='目标页面可见', summary='已完成'),
    ])
    async def run():
        started = await agent.start('打开页面', '2'*64, '用户原文', model)
        await await_worker(started['task_id'])
        exported = agent.trace(started['task_id'], limit=1000)
        events = exported['events']
        assert len(events) > 12
        assert events[0]['kind'] == 'task_created'
        assert any(e['kind'] == 'llm_request' and e['data']['new_messages'][0]['role'] == 'system' for e in events)
        decisions = [e for e in events if e['kind'] == 'agent_decision']
        assert [e['data']['step'] for e in decisions] == [0, 1, 2]
        assert decisions[1]['data']['arguments']['decision_summary'] == '弹窗遮挡入口，先关闭'
        stages = [e for e in events if e['kind'] == 'stage_succeeded']
        assert all(e['data']['elapsed_seconds'] >= 0 for e in stages)
        assert any(e['data']['stage'] == 'vision.observe' and e['data']['parent_span_id'] for e in stages)
        assert any(e['kind'] == 'task_finished' and e['data']['state'] == 'succeeded' for e in events)
        assert any(e['kind'] == 'click_intent' and e['data']['action_id'] for e in events)
        assert all(e['data'].get('tool_call_id') == 'call-click_element' for e in events
                   if e['data'].get('step') == 1 and e['kind'] != 'llm_request'
                   and e['data'].get('stage') != 'planner')
    asyncio.run(run())


def test_trace_pagination_is_stable_and_redacts_credentials(configured, monkeypatch):
    secret = 'never-log-this-token-value'
    monkeypatch.setenv('DEEPSEEK_API_KEY', secret)
    async def run():
        blocker = asyncio.Event()
        async def model(*args):
            await blocker.wait()
            return call('stop_task', reason='结束')
        task = await agent.start('观察手机', '3'*64, '用户原文 '+secret, model)
        for index in range(25):
            agent.record(task['task_id'], 'test_event', index=index, nested={'api_key': secret},
                         text='error mentions '+secret, image_base64='raw-image-content',
                         reasoning_content='hidden-reasoning')
        first = agent.trace(task['task_id'], limit=7)
        agent.record(task['task_id'], 'after_snapshot', text='不属于这次导出')
        combined = list(first['events'])
        page = first
        while page['has_more']:
            page = agent.trace(task['task_id'], after_seq=page['next_after_seq'], limit=7,
                               through_seq=first['through_seq'])
            combined.extend(page['events'])
        assert len(combined) == 26
        assert len({e['seq'] for e in combined}) == 26
        assert not any(e['kind'] == 'after_snapshot' for e in combined)
        serialized = json.dumps({'task': first['task'], 'events': combined})
        assert secret not in serialized
        assert 'raw-image-content' not in serialized
        assert 'hidden-reasoning' not in serialized
        assert '[redacted]' in serialized and '[omitted]' in serialized
        blocker.set()
        await await_worker(task['task_id'])
    asyncio.run(run())


def test_failure_has_original_exception_and_stage_in_trace(configured, caplog):
    async def broken_model(*args):
        raise RuntimeError('planner connection failed')
    async def run():
        task = await agent.start('观察手机', '4'*64, '原文', broken_model)
        await await_worker(task['task_id'])
        exported = agent.trace(task['task_id'], limit=1000)
        errors = [e for e in exported['events'] if e['kind'] == 'stage_failed']
        assert errors[0]['data']['stage'] == 'planner'
        assert errors[0]['data']['step'] == 0
        assert errors[0]['data']['error']['type'] == 'RuntimeError'
        assert 'broken_model' in errors[0]['data']['error']['traceback']
        assert 'planner connection failed' in errors[0]['data']['error']['message']
        assert any(e['kind'] == 'task_error' for e in exported['events'])
        assert task['task_id'] in caplog.text
    asyncio.run(run())


def test_notification_failure_is_logged_without_changing_success(configured):
    model = AsyncMock(return_value=call('finish_task', evidence='完成证据', summary='完成'))
    def broken_notification(result):
        raise RuntimeError('notification transport failed')
    async def run():
        task = await agent.start('查看手机', '5'*64, '原文', model, broken_notification)
        result = await await_worker(task['task_id'])
        assert result['state'] == 'succeeded'
        events = agent.trace(task['task_id'], limit=1000)['events']
        assert any(e['kind'] == 'notification_failed' and
                   e['data']['error']['message'] == 'notification transport failed' for e in events)
    asyncio.run(run())


def test_receipt_logs_progress_changes_without_repeating_identical_polls(configured, monkeypatch):
    task_id = '6'*64
    with agent.db() as connection:
        connection.execute('INSERT INTO phone_agent_task VALUES(?,?,?,?,?,?,?)',
                           (task_id, '测试', '原文', 'running', '', 1, 1))
    responses = iter([
        {'state': 'running', 'message': '正在校准鼠标'},
        {'state': 'running', 'message': '正在校准鼠标'},
        {'state': 'running', 'message': '正在移动到目标'},
        {'state': 'succeeded', 'result': {'report_sent': True}},
    ])
    async def query():
        return next(responses)
    monkeypatch.setattr(agent.asyncio, 'sleep', AsyncMock())
    executor = agent.PhoneExecutor(task_id)
    asyncio.run(ORIGINAL_POLL(executor, query, {'running'}, 5, action_id='click-123'))
    receipts = [e for e in agent.trace(task_id)['events'] if e['kind'] == 'action_receipt']
    assert len(receipts) == 3
    assert all(e['data']['action_id'] == 'click-123' for e in receipts)
    assert receipts[1]['data']['result']['message'] == '正在移动到目标'


def test_read_only_observation_failure_can_recover(configured):
    from fastapi import HTTPException
    async def run():
        configured.side_effect = [HTTPException(502, '模型没有返回有效画面描述'),
                                  {'description': '桌面可见微信', 'controls': ['微信']},
                                  {'matched': True, 'reason': '桌面可见'}]
        model = AsyncMock(side_effect=[call('observe_screen', objective='查看当前页面'),
                                       call('observe_screen', objective='描述可见图标'),
                                       call('finish_task', evidence='桌面可见', summary='已查看桌面')])
        started = await agent.start('查看桌面', 'f'*64, '查看桌面', model)
        result = await await_worker(started['task_id'])
        assert result['state'] == 'succeeded'
        responses = [e['data']['result'] for e in agent.trace(started['task_id'], limit=1000)['events']
                     if e['kind'] == 'tool_result']
        assert responses[0]['ok'] is False and responses[0]['retryable'] is True
        assert not any(e['kind'] in ('click_intent', 'keyboard_intent')
                       for e in agent.trace(started['task_id'], limit=1000)['events'])
    asyncio.run(run())


def test_observation_retries_are_bounded_and_invalidate_evidence(configured):
    from fastapi import HTTPException
    async def run():
        with agent.db() as db:
            db.execute('INSERT INTO phone_agent_task VALUES(?,?,?,?,?,?,?)',
                       ('e'*64, '查看页面', '', 'running', '', 1, 1))
        executor = agent.PhoneExecutor('e'*64)
        executor.observed_at = __import__('time').monotonic()
        configured.side_effect = HTTPException(502, 'invalid observation')
        for _ in range(2):
            assert (await executor.observe('查看页面'))['ok'] is False
            with pytest.raises(ValueError):
                executor.require_observation()
        with pytest.raises(agent.Stopped, match='连续3次'):
            await executor.observe('查看页面')
    asyncio.run(run())


def test_find_app_from_another_app_via_launcher(configured, monkeypatch):
    observations = iter([
        {'description': '设置页面', 'visible_text': ['设置'], 'controls': []},
        {'description': '桌面', 'visible_text': ['微信'], 'controls': ['微信图标'], 'elements': [ELEMENT]},
        {'description': '微信首页', 'visible_text': ['微信', '通讯录'], 'controls': ['通讯录']},
    ])
    original = configured.side_effect
    async def vision(method, path, payload=None):
        if path == '/observe':
            return next(observations)
        return await original(method, path, payload)
    configured.side_effect = vision
    keys = []
    monkeypatch.setattr(agent.pc, 'enqueue', lambda request: keys.append(request.action.model_dump()))
    monkeypatch.setattr(agent.pc, 'command_status', lambda _: {'state': 'succeeded'})
    async def model(messages, tools):
        data = json.loads(messages[-1]['content']) if messages[-1]['role'] == 'tool' else {}
        if data.get('description') == '设置页面':
            return call('press_key', key='HOME', expected_after='桌面可见')
        if data.get('description') == '桌面':
            return call('click_element', element_id=ELEMENT['element_id'], expected_after='微信首页可见')
        if data.get('description') == '微信首页':
            return call('finish_task', evidence='微信首页可见', summary='已打开微信')
        return call('observe_screen', objective='查看当前页面与应用图标')
    async def run():
        started = await agent.start('打开微信', 'c'*64, '打开微信', model)
        result = await await_worker(started['task_id'])
        assert result['state'] == 'succeeded'
        assert keys == [{'type': 'key', 'usage': 40, 'modifiers': 8}]
        assert sum(c.args[1] == '/click' for c in configured.call_args_list) == 1
    asyncio.run(run())


def test_click_uses_observed_element_and_invalidates_map(configured):
    async def run():
        with agent.db() as db:
            db.execute('INSERT INTO phone_agent_task VALUES(?,?,?,?,?,?,?)',
                       ('9'*64, '点击按钮', '', 'running', '', 1, 1))
        executor = agent.PhoneExecutor('9'*64)
        await executor.observe('查看按钮')
        for element_id in ['0'*32, ELEMENT['element_id']]:
            if element_id == ELEMENT['element_id']:
                executor.elements[element_id] = {**ELEMENT, 'expires_at': 0}
            with pytest.raises(ValueError, match='最新画面或已过期'):
                await executor.click_observed(element_id)
        assert not any(c.args[1] in ('/locate', '/click') for c in configured.call_args_list)
        await executor.observe('重新查看按钮')
        await executor.click_observed(ELEMENT['element_id'])
        assert not any(c.args[1] == '/locate' for c in configured.call_args_list)
        post = next(c for c in configured.call_args_list if c.args[1] == '/click')
        assert post.args[2]['result_id'] == ELEMENT['result_id']
        assert executor.elements == {}
        with pytest.raises(ValueError, match='必须先调用observe_screen'):
            await executor.click_observed(ELEMENT['element_id'])
    asyncio.run(run())
