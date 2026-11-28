import asyncio
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi.testclient import TestClient

from app import main, news


class NewsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = main.DB_PATH
        main.DB_PATH = Path(self.tmp.name) / 'news.db'
        main.setup_db()

    def tearDown(self):
        main.DB_PATH = self.old
        self.tmp.cleanup()

    def test_push_uses_plain_text_preview_and_strips_markdown_and_html(self):
        markdown = (
            '## 今日重点\n- **模型更新**：[官方公告](https://example.com/release)<br>\n'
            '- 支持 `Python`，~~旧接口~~已弃用 &amp; <b>加速</b>\n\n## 来源与发布时间\n- https://example.com'
        )
        message_id = 'news:ai:today'
        with main.connect() as db:
            db.execute("INSERT INTO device(id,fcm_token,updated_at) VALUES(?,?,?)",
                       ('news-phone', 'fake-token', main.utc_now()))
            db.execute("INSERT INTO inbox_message(id,title,body,created_at) VALUES(?,?,?,?)",
                       (message_id, 'AI 新进展 · 今日简报', markdown, main.utc_now()))
            main.enqueue_push(db, message_id)
        firebase = SimpleNamespace(_apps=True, credentials=None, messaging=MagicMock())
        with patch.dict(sys.modules, {'firebase_admin': firebase}), patch.dict(
            'os.environ', {'FIREBASE_SERVICE_ACCOUNT_FILE': 'unused.json'}
        ):
            main.deliver_pushes()

        preview = '• 模型更新：官方公告\n• 支持 Python，旧接口已弃用 & 加速'
        firebase.messaging.Notification.assert_called_once_with(
            title='📰 AI 新进展 · 今日简报', body=preview)
        self.assertEqual(firebase.messaging.Message.call_args.kwargs['data'],
                         {'message_id': message_id, 'body': preview})
        firebase.messaging.send.assert_called_once()
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT body FROM inbox_message").fetchone()[0], markdown)
            self.assertEqual(db.execute("SELECT state FROM push_outbox").fetchone()[0], 'sent')

    def test_legacy_presets_are_retired_and_morning_ai_aliases_unify(self):
        for retired in ('社会热点', '国际大事'):
            res = news.run(main.connect, 'subscribe_news', {'topic': retired, 'hour': 9, 'timezone': 'Asia/Shanghai'}, None)
            self.assertFalse(res['ok'])
        a = news.run(main.connect, 'subscribe_news', {'topic': 'AI 新进展', 'hour': 9, 'timezone': 'Asia/Shanghai'}, None)
        b = news.run(main.connect, 'subscribe_news', {'topic': 'AI 与技术动态', 'hour': 10, 'timezone': 'Asia/Shanghai'}, None)
        self.assertEqual(a['id'], b['id'])
        subs = news.run(main.connect, 'list_news', {}, None)['subscriptions']
        self.assertEqual(len(subs), 1)
        self.assertEqual(subs[0]['hour'], 10)

    def test_subscription_page_api_auth_validation_and_persistence(self):
        client = TestClient(main.app)
        with main.connect() as db:
            db.execute("INSERT INTO device(id,token_hash,updated_at) VALUES(?,?,?)", ("news-phone", main.token_hash("news-token"), main.utc_now()))
        headers = {"Authorization": "Bearer news-token"}
        path = "/v1/news/subscriptions"
        self.assertEqual(client.get(path).status_code, 401)
        args = {"topic": "个人晨报", "hour": 8, "timezone": "Asia/Shanghai"}
        result = client.post(path, headers=headers, json=args)
        self.assertEqual(result.status_code, 200)
        sid = result.json()["id"]
        self.assertEqual(client.get(path, headers=headers).json()[0]["hour"], 8)
        self.assertEqual(client.post(path, headers=headers, json={**args, "hour": 7}).status_code, 422)
        self.assertEqual(client.post(path, headers=headers, json={**args, "timezone": "bad"}).status_code, 422)
        self.assertEqual(client.post(path, headers=headers, json={**args, "topic": "   "}).status_code, 422)
        client.post(path, headers=headers, json={**args, "hour": 18})
        rows = client.get(path, headers=headers).json()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["hour"], 18)
        for _ in range(2):
            self.assertEqual(client.post(path + "/" + sid + "/cancel", headers=headers, json={}).status_code, 200)
        self.assertEqual(client.get(path, headers=headers).json(), [])
        client.close()

    def test_subscription_validation_and_cancel(self):
        args = {'topic': 'AI', 'hour': 9, 'timezone': 'Asia/Shanghai'}
        one = asyncio.run(main.run_tool('subscribe_news', args, 'first'))
        two = asyncio.run(main.run_tool('subscribe_news', args, 'retry'))
        self.assertEqual(one['id'], two['id'])
        self.assertEqual(len(news.run(main.connect, 'list_news', {}, None)['subscriptions']), 1)
        self.assertFalse(asyncio.run(main.run_tool('subscribe_news', {**args, 'hour': 2}))['ok'])
        self.assertFalse(news.run(main.connect, 'subscribe_news', {**args, 'timezone': 'missing'}, None)['ok'])
        self.assertTrue(news.run(main.connect, 'cancel_news', {'id': one['id']}, None)['ok'])
        self.assertEqual(news.run(main.connect, 'list_news', {}, None)['subscriptions'], [])

    def test_feed_extracts_clean_excerpt_and_rejects_stale_unsafe_and_duplicate(self):
        data = b'''<rss><channel>
            <item><title>Fresh &amp; Fast</title><link>https://example.com/a</link><description>&lt;p&gt;Open-source &lt;b&gt;agent&lt;/b&gt; runtime.&lt;/p&gt;</description><pubDate>Mon, 02 Nov 2026 08:00:00 GMT</pubDate></item>
            <item><title>duplicate</title><link>https://example.com/a</link><pubDate>Mon, 02 Nov 2026 08:00:00 GMT</pubDate></item>
            <item><title>old</title><link>https://example.com/b</link><pubDate>Mon, 02 Nov 2025 08:00:00 GMT</pubDate></item>
            <item><title>unsafe</title><link>javascript:alert(1)</link><pubDate>Mon, 02 Nov 2026 08:00:00 GMT</pubDate></item>
        </channel></rss>'''
        parsed = news.parse_feed(data, datetime(2026, 11, 2, 10, tzinfo=timezone.utc))
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]['title'], 'Fresh & Fast')
        self.assertEqual(parsed[0]['excerpt'], 'Open-source agent runtime.')
        with self.assertRaises(ValueError):
            news.parse_feed(b'<!DOCTYPE rss><rss/>', datetime.now(timezone.utc))

    def test_daily_delivery_is_atomic_and_deduplicated(self):
        now = datetime.now(timezone.utc)
        news.run(main.connect, 'subscribe_news', {'topic': 'AI', 'hour': 8, 'timezone': 'Asia/Shanghai'}, None)
        with main.connect() as db:
            db.execute('UPDATE news_subscription SET timezone=?,hour=8', (f'Etc/GMT{(now.hour-12):+d}',))
        response = MagicMock()
        response.__aenter__ = AsyncMock(return_value=response)
        response.__aexit__ = AsyncMock(return_value=False)

        async def chunks():
            yield b'<rss/>'
        response.aiter_bytes = chunks
        client = MagicMock()
        client.__aenter__ = AsyncMock(return_value=client)
        client.__aexit__ = AsyncMock(return_value=False)
        client.stream.return_value = response
        model = AsyncMock(return_value={'content': '## 今日简报\n测试'})
        with patch.object(news.httpx, 'AsyncClient', return_value=client), patch.object(
            news, 'parse_feed', return_value=[{'title': 'news', 'url': 'https://example.com/a', 'published': 'today'}]
        ):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
        self.assertEqual(model.await_count, 1)
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM inbox_message WHERE id LIKE 'news:%'").fetchone()[0], 1)

    def test_ai_can_use_official_feed_when_other_sources_fail(self):
        now = datetime(2026, 11, 6, 10, tzinfo=timezone.utc)
        data = b"<rss><channel><item><title>Model update</title><link>https://openai.com/news/example</link><pubDate>Thu, 05 Nov 2026 08:00:00 GMT</pubDate></item></channel></rss>"
        effects = [data] + [ValueError('offline') for _ in range(len(news.AI_SOURCES) - 1)]
        feed = AsyncMock(side_effect=effects)
        with patch.object(news, 'fetch_provider_feed', feed):
            items = asyncio.run(news.fetch_items(main.connect, {'topic': 'AI 新进展'}, now))
        self.assertEqual([item['source'] for item in items], ['OpenAI 官方动态'])

    def test_ai_breaking_pushes_immediately_and_digest_waits_for_multi_day_window(self):
        now = datetime.now(timezone.utc)
        zone = f'Etc/GMT{(now.hour-14):+d}'  # local hour == 14:00 (within 08:00-22:00)
        sid = news.run(main.connect, 'subscribe_news', {'topic': 'AI 与技术动态', 'hour': 9, 'timezone': zone}, None)['id']
        # Pretend last digest was sent yesterday so regular digest should accumulate rather than push daily.
        local_today = now.astimezone(news.ZoneInfo(zone)).date()
        yesterday = (local_today - timedelta(days=1)).isoformat()
        with main.connect() as db:
            db.execute("UPDATE news_subscription SET last_day=? WHERE id=?", (yesterday, sid))
            db.execute("INSERT OR REPLACE INTO news_meta(key,value) VALUES('ai_pool_last_digest_day', ?)", (yesterday,))

        candidates = [
            {'title': 'Major Flagship Model', 'url': 'https://openai.com/news/flagship', 'published': now.isoformat(), 'source': 'OpenAI 官方动态'},
            {'title': 'Useful CLI Tool', 'url': 'https://example.com/cli-tool', 'published': now.isoformat(), 'source': 'Show HN · 热门开源工具'},
        ]
        triage_reply = json.dumps({
            'breaking': [{'category': '大模型', 'headline': '新一代旗舰模型开源', 'summary': '推理与工具调用能力显著提升。', 'urls': ['https://openai.com/news/flagship']}],
            'digest': [{'category': '开源工具', 'headline': '轻量 Agent 调试 CLI', 'summary': '支持本地回放工具调用链路。', 'urls': ['https://example.com/cli-tool']}],
        }, ensure_ascii=False)
        model = AsyncMock(return_value={'content': triage_reply})
        with patch.object(news, 'fetch_items', AsyncMock(return_value=candidates)):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))

        with main.connect() as db:
            breaking_msgs = db.execute("SELECT id, title, body FROM inbox_message WHERE id LIKE 'news-breaking:%'").fetchall()
            digest_msgs = db.execute("SELECT id FROM inbox_message WHERE id LIKE 'news:%'").fetchall()
            pending_pool = db.execute("SELECT headline FROM news_ai_pool WHERE sent_at IS NULL").fetchall()
        self.assertEqual(len(breaking_msgs), 1)
        self.assertIn('AI 速递', breaking_msgs[0]['title'])
        self.assertEqual(len(digest_msgs), 0)  # Only 1 day elapsed, so digest waits!
        self.assertEqual([r['headline'] for r in pending_pool], ['轻量 Agent 调试 CLI'])

        # Advance last digest date to 3 days ago -> next tick flushes the accumulated digest!
        three_days_ago = (local_today - timedelta(days=3)).isoformat()
        with main.connect() as db:
            db.execute("UPDATE news_subscription SET last_day=? WHERE id=?", (three_days_ago, sid))
            db.execute("INSERT OR REPLACE INTO news_meta(key,value) VALUES('ai_pool_last_digest_day', ?)", (three_days_ago,))
        with patch.object(news, 'fetch_items', AsyncMock(return_value=candidates)):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
        with main.connect() as db:
            digest_msgs = db.execute("SELECT title, body FROM inbox_message WHERE id LIKE 'news:%'").fetchall()
        self.assertEqual(len(digest_msgs), 1)
        self.assertIn('轻量 Agent 调试 CLI', digest_msgs[0]['body'])

    def test_morning_briefing_combines_agenda_and_falls_back_to_history_story_when_empty(self):
        now = datetime.now(timezone.utc)
        zone = f'Etc/GMT{(now.hour-9):+d}'  # local hour == 09:00
        local_today = now.astimezone(news.ZoneInfo(zone)).date()
        news.run(main.connect, 'subscribe_news', {'topic': '个人晨报', 'hour': 8, 'timezone': zone}, None)

        # Case 1: completely empty day -> delivers a historical story with clean Markdown (no HTML tags)
        story_json = json.dumps({'title': '洛阳纸贵 · 左思十年赋三都', 'story': '西晋左思构思十年写成 **《三都赋》**，洛阳竞相传抄。'}, ensure_ascii=False)
        model = AsyncMock(return_value={'content': story_json})
        with patch.object(news, 'fetch_items', AsyncMock(return_value=[])):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
        with main.connect() as db:
            msg = db.execute("SELECT id, title, body FROM inbox_message WHERE id LIKE 'morning:%'").fetchone()
            self.assertIsNotNone(msg)
            self.assertIn('洛阳纸贵', msg['title'])
            self.assertIn('## 洛阳纸贵', msg['body'])
            self.assertNotIn('<', msg['body'])
            # Reset morning delivery to test Case 2 with calendar & reminder agenda
            db.execute("DELETE FROM inbox_message")
            db.execute("UPDATE news_subscription SET last_day=NULL")

        news.save_calendar_snapshot(main.connect, 'phone-1', zone, [
            {'title': '评审 Agent 架构', 'occurrence': local_today.isoformat(), 'event_type': 'todo', 'completed': False, 'days_until': 0},
        ])
        with patch.object(news, 'fetch_items', AsyncMock(return_value=[])):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
        with main.connect() as db:
            msg = db.execute("SELECT title, body FROM inbox_message WHERE id LIKE 'morning:%'").fetchone()
        self.assertIn('今日日程提醒', msg['title'])
        self.assertIn('评审 Agent 架构', msg['body'])

    def test_side_project_idea_card_synthesizes_top_opportunity_every_few_days(self):
        now = datetime.now(timezone.utc)
        zone = f'Etc/GMT{(now.hour-20):+d}'  # local hour == 20:00
        local_today = now.astimezone(news.ZoneInfo(zone)).date()
        one = news.run(main.connect, 'subscribe_news', {'topic': '副业痛点灵感', 'hour': 20, 'timezone': zone}, None)
        two = news.run(main.connect, 'subscribe_news', {'topic': '副业痛点周报', 'hour': 20, 'timezone': zone}, None)
        self.assertEqual(one['id'], two['id'])
        sid = two['id']

        yesterday = (local_today - timedelta(days=1)).isoformat()
        with main.connect() as db:
            db.execute("UPDATE news_subscription SET last_day=? WHERE id=?", (yesterday, sid))

        posts = [
            {
                'title': 'Tired of manually reconciling Shopify supplier PDFs into CSV',
                'excerpt': 'Every week our team spends 4 hours copying supplier invoice tables.',
                'url': 'https://www.reddit.com/r/shopify/comments/abc123',
                'published': now.isoformat(),
                'source': 'Reddit r/shopify · 独立站与电商工具缺口',
            },
            {
                'title': 'Ask HN: Lightweight local log redaction before sending to vendors?',
                'excerpt': 'Enterprise tools cost $2k/mo just to scrub PII from support bundles.',
                'url': 'https://news.ycombinator.com/item?id=999',
                'published': now.isoformat(),
                'source': 'Ask HN · 真实痛点与求工具',
            },
        ]
        model_json = json.dumps({
            'ideas': [
                {
                    'title': '供应商发票 PDF 转独立站库存单',
                    'score': 9,
                    'audience': '中小型 Shopify 独立站卖家（聚集于 r/shopify 与跨境卖家社群）',
                    'pain_quote': '每周都要花 4 小时手工把不同供应商 PDF 发票抄进库存表格，现有 ERP 太贵太重。',
                    'workaround': '人工对照 PDF 逐行复制粘贴到 Excel',
                    'monetization': '做成 $19/月 的轻量邮件转发/上传自动解析插件，支持一键导出 Shopify 标准库存 CSV。',
                    'urls': ['https://www.reddit.com/r/shopify/comments/abc123'],
                },
                {
                    'title': '工单日志本地脱敏小工具',
                    'score': 8,
                    'audience': '中小型 SaaS 运维与技术支持团队',
                    'pain_quote': '每次给外部厂商发排查包都要手动删密钥和客户邮箱，企业合规软件动辄两千美金一月。',
                    'workaround': '手工正则替换或购买昂贵安全套件',
                    'monetization': '单机 CLI + 桌面拖入脱敏工具，按席位买断或 $9/月 订阅。',
                    'urls': ['https://news.ycombinator.com/item?id=999'],
                },
            ]
        }, ensure_ascii=False)
        model = AsyncMock(return_value={'content': model_json})

        # Day 1 after previous card: collects into pool, does NOT push prematurely
        with patch.object(news, 'fetch_items', AsyncMock(return_value=posts)):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM inbox_message WHERE id LIKE 'idea:%'").fetchone()[0], 0)
            self.assertEqual(db.execute("SELECT count(*) FROM news_idea_pool WHERE sent_at IS NULL").fetchone()[0], 2)

        # 2 days elapsed with 2 strong candidates -> pushes ONE concise synthesized inspiration card!
        two_days_ago = (local_today - timedelta(days=2)).isoformat()
        with main.connect() as db:
            db.execute("UPDATE news_subscription SET last_day=? WHERE id=?", (two_days_ago, sid))
            db.execute("INSERT INTO device(id,fcm_token,updated_at) VALUES(?,?,?)", ('idea-phone', 'tok', main.utc_now()))
        with patch.object(news, 'fetch_items', AsyncMock(return_value=posts)):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))

        with main.connect() as db:
            card = db.execute("SELECT id, title, body FROM inbox_message WHERE id LIKE 'idea:%'").fetchone()
            self.assertIsNotNone(card)
            self.assertIn('供应商发票 PDF 转独立站库存单', card['title'])
            self.assertIn('**目标人群**', card['body'])
            self.assertIn('**商业化思维**', card['body'])
            self.assertIn('> “每周都要花 4 小时', card['body'])
            self.assertIn('## 🔍 备选痛点信号', card['body'])
            self.assertEqual(db.execute("SELECT count(*) FROM news_idea_pool WHERE sent_at IS NULL").fetchone()[0], 0)
            push_title, push_body = main.format_push(card['id'], card['title'], card['body'])
            self.assertTrue(push_title.startswith('💡 '))
            self.assertNotIn('reddit.com', push_body)
            self.assertNotIn('**', push_body)

    def test_rss_and_x_sources_api(self):
        client = TestClient(main.app)
        with main.connect() as db:
            db.execute('INSERT INTO device(id,token_hash,updated_at) VALUES(?,?,?)', ('source-phone', main.token_hash('token'), main.utc_now()))
        headers = {'Authorization': 'Bearer token'}
        path = '/v1/news/subscriptions'
        args = {'topic': 'Karpathy', 'kind': 'x', 'locator': '@Karpathy', 'hour': 9, 'timezone': 'Asia/Shanghai'}
        self.assertEqual(client.get('/v1/news/sources').status_code, 401)
        with patch.dict(news.os.environ, {'X_BEARER_TOKEN': '', 'X_RSS_BASE_URL': ''}):
            config = client.get('/v1/news/sources', headers=headers).json()
            self.assertFalse(config['x_ready'])
            self.assertIn('elonmusk', [s['locator'] for s in config['catalog']])
        result = client.post(path, headers=headers, json=args)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(client.get(path, headers=headers).json()[0]['locator'], 'karpathy')
        self.assertEqual(client.post(path, headers=headers, json={**args, 'locator': 'karpathy'}).json()['id'], result.json()['id'])
        for locator in ('http://example.com/feed', 'https://127.0.0.1/feed', 'https://[::1]/rss', 'https://user:pass@example.com/rss', 'https://example.com:8787/feed', 'https://x.local/rss'):
            self.assertEqual(client.post(path, headers=headers, json={**args, 'kind': 'rss', 'locator': locator}).status_code, 422, locator)
        self.assertEqual(client.post(path, headers=headers, json={**args, 'locator': 'https://x.com/karpathy'}).status_code, 422)
        self.assertEqual(client.post(path, headers=headers, json={**args, 'kind': 'rss', 'locator': 'https://example.com/feed'}).status_code, 200)
        snap = client.post('/v1/briefing/calendar', headers=headers, json={
            'timezone': 'Asia/Shanghai',
            'events': [{'title': '交周报', 'occurrence': '2026-11-28', 'event_type': 'todo', 'completed': False, 'days_until': 0}],
        })
        self.assertEqual(snap.status_code, 200)
        self.assertEqual(snap.json()['count'], 1)
        client.close()

    def test_atom_feed(self):
        data = b'''<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>A new essay</title><summary>Deep dive into agents.</summary><link rel="self" href="https://example.com/atom"/><link rel="alternate" href="https://example.com/essay"/><updated>2026-11-02T08:00:00Z</updated></entry></feed>'''
        items = news.parse_feed(data, datetime(2026, 11, 2, 10, tzinfo=timezone.utc))
        self.assertEqual(items[0]['url'], 'https://example.com/essay')
        self.assertEqual(items[0]['excerpt'], 'Deep dive into agents.')

    def test_custom_feed_pins_public_address_and_blocks_redirect_to_private(self):
        import socket
        import httpx
        original = httpx.AsyncClient
        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(302, headers={'location': 'https://127.0.0.1/internal'})

        def client(**kwargs):
            return original(transport=httpx.MockTransport(respond), **kwargs)

        with patch.object(news.socket, 'getaddrinfo', return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('93.184.216.34', 443))]), patch.object(news.httpx, 'AsyncClient', side_effect=client):
            with self.assertRaises(ValueError):
                asyncio.run(news.fetch_feed('https://example.com/feed'))
        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0].url.host, '93.184.216.34')
        self.assertEqual(requests[0].headers['host'], 'example.com')
        self.assertEqual(requests[0].extensions['sni_hostname'], 'example.com')
        with patch.object(news.socket, 'getaddrinfo', return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('10.0.0.2', 443))]):
            with self.assertRaises(ValueError):
                asyncio.run(news.fetch_feed('https://example.com/feed'))

    def test_x_requires_connection_and_caches_user_lookup(self):
        import httpx
        original = httpx.AsyncClient
        sub = {'kind': 'x', 'locator': 'karpathy'}
        now = datetime.now(timezone.utc)
        with patch.dict(news.os.environ, {'X_BEARER_TOKEN': '', 'X_RSS_BASE_URL': ''}), patch.object(news.httpx, 'AsyncClient') as client:
            with self.assertRaises(ValueError):
                asyncio.run(news.fetch_items(main.connect, sub, now))
            client.assert_not_called()
        requests = []

        def respond(request):
            requests.append(request)
            if '/by/username/' in request.url.path:
                return httpx.Response(200, json={'data': {'id': '123'}})
            return httpx.Response(200, json={'data': [{'id': '456', 'text': 'new model', 'created_at': now.isoformat()}]})

        def client(**kwargs):
            return original(transport=httpx.MockTransport(respond), **kwargs)

        with patch.dict(news.os.environ, {'X_BEARER_TOKEN': 'test-token', 'X_RSS_BASE_URL': ''}), patch.object(news.httpx, 'AsyncClient', side_effect=client):
            first = asyncio.run(news.fetch_items(main.connect, sub, now))
            asyncio.run(news.fetch_items(main.connect, sub, now))
        self.assertEqual(len(requests), 3)
        self.assertEqual(first[0]['url'], 'https://x.com/karpathy/status/456')
        self.assertEqual(requests[1].url.params['max_results'], '10')
        self.assertEqual(requests[1].url.params['exclude'], 'retweets,replies')

    def test_model_retry_reuses_fetched_posts(self):
        now = datetime.now(timezone.utc)
        sid = news.run(main.connect, 'subscribe_news', {'topic': 'X', 'kind': 'x', 'locator': 'karpathy', 'hour': 8, 'timezone': f'Etc/GMT{(now.hour-12):+d}'}, None)['id']
        fetch = AsyncMock(return_value=[{'title': 'post', 'url': 'https://x.com/karpathy/status/1', 'published': now.isoformat()}])
        model = AsyncMock(side_effect=[RuntimeError('model unavailable'), {'content': 'summary'}])
        with patch.object(news, 'fetch_items', fetch), patch.dict(news.os.environ, {'X_BEARER_TOKEN': 'test', 'X_RSS_BASE_URL': ''}):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
            with main.connect() as db:
                self.assertTrue(db.execute('SELECT last_error FROM news_subscription WHERE id=?', (sid,)).fetchone()[0])
                db.execute('UPDATE news_subscription SET retry_at=NULL')
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
        self.assertEqual(fetch.await_count, 1)
        with main.connect() as db:
            self.assertIsNone(db.execute('SELECT last_error FROM news_subscription WHERE id=?', (sid,)).fetchone()[0])
