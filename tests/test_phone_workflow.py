"""Model-controlled phone tasks; no real LLM, ADB or HID traffic."""
import asyncio
import json
from unittest.mock import AsyncMock

import pytest

from server.app import phone_agent as agent
from server.app.phone_input import typing_units

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
                    'keyboard_visible': False, 'focus': 'any.app/Page'}
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
                    answer = call('click_element', target='右上角关闭弹窗按钮', expected_after='弹窗已关闭')
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
                                   call('click_element', target='发送', expected_after='新消息气泡可见')])
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
        model = AsyncMock(return_value=call('click_element', target='按钮', expected_after='下一页'))
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
        call('click_element', target='关闭按钮', expected_after='弹窗消失', decision_summary='弹窗遮挡入口，先关闭'),
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
