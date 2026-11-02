import asyncio
import hashlib
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timezone
from unittest.mock import AsyncMock
from fastapi.testclient import TestClient
from app import main, x_notifications as xn


class XNotificationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = main.DB_PATH
        main.DB_PATH = Path(self.tmp.name)/'notifications.db'
        main.setup_db()
        self.now = datetime.now(timezone.utc)
        self.zone = f'Etc/GMT{(self.now.hour-12):+d}'
        with main.connect() as db:
            for device in ('phone','other'):
                db.execute('INSERT INTO device(id,token_hash,fcm_token,updated_at) VALUES(?,?,?,?)',(device,main.token_hash(device),device+'-fcm',main.utc_now()))
        self.client = TestClient(main.app)
        self.headers = {'Authorization':'Bearer phone'}
        self.path = '/v1/news/x-notifications/'

    def tearDown(self):
        self.client.close()
        main.DB_PATH = self.old
        self.tmp.cleanup()

    def config(self, **changes):
        return {'enabled':True,'revision':100,'hour':8,'timezone':self.zone,'authors':['karpathy'],**changes}

    def item(self, text='A new model', **changes):
        return {'id':hashlib.sha256(text.encode()).hexdigest(),'author':'karpathy','text':text,'posted_at':self.now.isoformat(),**changes}

    def save(self, **changes):
        return self.client.post(self.path+'settings',headers=self.headers,json=self.config(**changes))

    def ingest(self, items=None, revision=100):
        return self.client.post(self.path+'batch',headers=self.headers,json={'revision':revision,'items':items if items is not None else [self.item()]})

    def test_requires_auth_opt_in_and_validates_input(self):
        self.assertEqual(self.client.post(self.path+'settings',json=self.config()).status_code,401)
        self.assertEqual(self.ingest().status_code,409)
        self.assertEqual(self.save(timezone='invalid').status_code,422)
        self.assertEqual(self.save(authors=['not a handle']).status_code,422)
        self.assertEqual(self.save(hour=1).status_code,422)
        self.assertEqual(self.save().status_code,200)
        self.assertEqual(self.ingest([self.item(id='bad')]).status_code,422)
        self.assertEqual(self.ingest([self.item(posted_at='2026-01-01T00:00:00')]).status_code,422)
        self.assertEqual(self.ingest([self.item()]*51).status_code,422)

    def test_dedup_author_filter_age_and_device_isolation(self):
        self.save()
        self.assertEqual(self.ingest([self.item(),self.item()]).json()['accepted'],[self.item()['id']]*2)
        self.assertEqual(self.ingest([self.item(author='elonmusk')]).json()['accepted'],[])
        self.assertEqual(self.ingest([self.item(posted_at='2000-01-01T00:00:00Z')]).json()['accepted'],[])
        other = self.client.post(self.path+'batch',headers={'Authorization':'Bearer other'},json={'revision':100,'items':[self.item()]})
        self.assertEqual(other.status_code,409)
        with main.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM x_notification_excerpt').fetchone()[0],1)

    def test_daily_summary_uses_model_once_and_pushes_origin_only(self):
        self.save();self.ingest()
        model=AsyncMock(return_value={'content':'## Karpathy\n模型进展'})
        asyncio.run(xn.tick(main.connect,model))
        asyncio.run(xn.tick(main.connect,model))
        self.assertEqual(model.await_count,1)
        with main.connect() as db:
            message=db.execute("SELECT * FROM inbox_message WHERE id LIKE 'x-notifications:%'").fetchone()
            self.assertIn('通知片段',message['body'])
            self.assertEqual(db.execute('SELECT device_id FROM push_outbox').fetchone()[0],'phone')
            self.assertEqual(db.execute('SELECT consumed FROM x_notification_excerpt').fetchone()[0],1)
        self.ingest([self.item(text='another post')])
        asyncio.run(xn.tick(main.connect,model))
        self.assertEqual(model.await_count,1)

    def test_disable_blocks_stale_uploads_and_erases_raw_excerpts(self):
        self.save();self.ingest()
        self.save(enabled=False,revision=101)
        self.assertEqual(self.ingest().status_code,409)
        self.assertFalse(self.save().json()['ok'])
        model=AsyncMock()
        asyncio.run(xn.tick(main.connect,model))
        model.assert_not_called()
        with main.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM x_notification_excerpt').fetchone()[0],0)
            self.assertEqual(db.execute('SELECT enabled FROM x_notification_settings').fetchone()[0],0)

    def test_disable_during_generation_does_not_publish_or_cache(self):
        self.save();self.ingest()
        async def generate(*args):
            xn.save_settings(main.connect,'phone',xn.Settings(**self.config(enabled=False,revision=101)))
            return {'content':'must not publish'}
        asyncio.run(xn.tick(main.connect,generate))
        with main.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM inbox_message').fetchone()[0],0)
            self.assertEqual(db.execute('SELECT count(*) FROM x_notification_digest_cache').fetchone()[0],0)

    def test_clear_and_author_changes_remove_pending_raw_data(self):
        self.save();self.ingest()
        self.save(clear_pending=True,revision=101)
        with main.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM x_notification_excerpt').fetchone()[0],0)
        self.ingest(revision=101)
        self.save(authors=['elonmusk'],revision=102)
        with main.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM x_notification_excerpt').fetchone()[0],0)

    def test_no_content_or_model_failure_does_not_mark_day_complete(self):
        self.save()
        model=AsyncMock(side_effect=RuntimeError('model offline'))
        asyncio.run(xn.tick(main.connect,model))
        model.assert_not_called()
        self.ingest()
        asyncio.run(xn.tick(main.connect,model))
        with main.connect() as db:
            row=db.execute('SELECT * FROM x_notification_settings').fetchone()
            self.assertIsNone(row['last_day'])
            self.assertIsNotNone(row['retry_at'])
            self.assertEqual(db.execute('SELECT consumed FROM x_notification_excerpt').fetchone()[0],0)
        asyncio.run(xn.tick(main.connect,model))
        self.assertEqual(model.await_count,1)
