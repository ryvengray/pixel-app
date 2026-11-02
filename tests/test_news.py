import asyncio
import unittest
import tempfile
from pathlib import Path
from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch, MagicMock
from app import main, news
from fastapi.testclient import TestClient


class NewsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = main.DB_PATH
        main.DB_PATH = Path(self.tmp.name) / 'news.db'
        main.setup_db()

    def tearDown(self):
        main.DB_PATH = self.old
        self.tmp.cleanup()

    def test_subscription_page_api_auth_validation_and_persistence(self):
        client = TestClient(main.app)
        with main.connect() as db:
            db.execute("INSERT INTO device(id,token_hash,updated_at) VALUES(?,?,?)", ("news-phone",main.token_hash("news-token"),main.utc_now()))
        headers = {"Authorization": "Bearer news-token"}
        path = "/v1/news/subscriptions"
        self.assertEqual(client.get(path).status_code, 401)
        args = {"topic": "AI 新进展", "hour": 9, "timezone": "Asia/Shanghai"}
        result = client.post(path, headers=headers, json=args)
        self.assertEqual(result.status_code, 200)
        sid = result.json()["id"]
        self.assertEqual(client.get(path, headers=headers).json()[0]["hour"], 9)
        self.assertEqual(client.post(path, headers=headers, json={**args, "hour": 7}).status_code, 422)
        self.assertEqual(client.post(path, headers=headers, json={**args, "timezone": "bad"}).status_code, 422)
        self.assertEqual(client.post(path, headers=headers, json={**args, "topic": "   "}).status_code, 422)
        client.post(path, headers=headers, json={**args, "hour": 18})
        rows = client.get(path, headers=headers).json()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["hour"], 18)
        for _ in range(2):
            self.assertEqual(client.post(path+"/"+sid+"/cancel", headers=headers, json={}).status_code, 200)
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

    def test_feed_rejects_stale_unsafe_and_duplicate(self):
        data = b'''<rss><channel><item><title>fresh</title><link>https://example.com/a</link><pubDate>Mon, 02 Nov 2026 08:00:00 GMT</pubDate></item><item><title>duplicate</title><link>https://example.com/a</link><pubDate>Mon, 02 Nov 2026 08:00:00 GMT</pubDate></item><item><title>old</title><link>https://example.com/b</link><pubDate>Mon, 02 Nov 2025 08:00:00 GMT</pubDate></item><item><title>unsafe</title><link>javascript:alert(1)</link><pubDate>Mon, 02 Nov 2026 08:00:00 GMT</pubDate></item></channel></rss>'''
        self.assertEqual(len(news.parse_feed(data, datetime(2026, 11, 2, 10, tzinfo=timezone.utc))), 1)
        with self.assertRaises(ValueError):
            news.parse_feed(b'<!DOCTYPE rss><rss/>', datetime.now(timezone.utc))

    def test_daily_delivery_is_atomic_and_deduplicated(self):
        now = datetime.now(timezone.utc)
        sid = news.run(main.connect, 'subscribe_news', {'topic':'AI','hour':8,'timezone':'Asia/Shanghai'}, None)['id']
        # Choose a zone with an eligible local delivery hour at any test execution time.
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
        model = AsyncMock(return_value={'content':'## 今日简报\n测试'})
        with patch.object(news.httpx, 'AsyncClient', return_value=client), patch.object(news, 'parse_feed', return_value=[{'title':'news','url':'https://example.com/a','published':'today'}]):
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
            asyncio.run(news.tick(main.connect, model, main.enqueue_push))
        self.assertEqual(model.await_count, 1)
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM inbox_message WHERE id LIKE 'news:%'").fetchone()[0], 1)
