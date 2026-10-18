"""Important topics are evidence-backed, user-controlled and separate from reminders."""
import asyncio
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import AsyncMock, patch
from fastapi.testclient import TestClient
from app import main, topics


class TopicTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.old_db = main.DB_PATH
        main.DB_PATH = Path(self.temp.name)/"topics.db"
        main.chat_lock = asyncio.Lock()
        main.setup_db()
        with main.connect() as db:
            db.execute("INSERT INTO device(id,token_hash,fcm_token,updated_at) VALUES(?,?,?,?)", ("phone",main.token_hash("token"),"fcm",main.utc_now()))
        self.client = TestClient(main.app)
        self.headers = {"Authorization":"Bearer token"}

    def tearDown(self):
        self.client.close(); main.DB_PATH = self.old_db; self.temp.cleanup()

    def extract(self, text="明天面试，有点紧张", title="明天的面试", request="topic-request-1", **extra):
        with main.connect() as db:
            topics.enqueue(db, request, [main.Turn(role="user", content=text)], "Asia/Shanghai")
        job = topics.claim_job()
        self.assertIsNotNone(job)
        model = AsyncMock(return_value={"content":json.dumps({"topics":[{"title":title,"source_quote":text,**extra}]},ensure_ascii=False)})
        with patch.object(main,"deepseek",model):
            asyncio.run(topics.extract(job))
        return self.client.get("/v1/topics",headers=self.headers).json()

    def change(self, row, **fields):
        fields.setdefault("operation_id", "change-"+str(row["revision"])+"-"+row["id"])
        return self.client.post("/v1/topics/"+row["id"],json={"base_revision":row["revision"],**fields},headers=self.headers)

    def test_chat_enqueues_once_without_waiting_for_extraction(self):
        model = AsyncMock(return_value={"content":"听起来明天对你很重要。想聊聊吗？"})
        request={"client_message_id":"chat-topic-id", "messages":[{"role":"user","content":"明天面试，有点紧张"}]}
        with patch.object(main,"deepseek",model):
            self.assertEqual(self.client.post("/v1/chat",json=request,headers=self.headers).status_code,200)
            self.client.post("/v1/chat",json=request,headers=self.headers)
        self.assertEqual(model.await_count,1)
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM topic_job").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM topic").fetchone()[0],0)

    def test_extraction_keeps_evidence_without_granting_consent(self):
        row=self.extract(consent="allowed",notify=True,followup_at=main.utc_now())[0]
        self.assertEqual(row["source_quote"],"明天面试，有点紧张")
        self.assertEqual(row["consent"],"none")
        self.assertEqual(row["notify"],0)
        self.assertIsNone(row["followup_at"])
        topics.fire_followups()
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM inbox_message").fetchone()[0],0)
            self.assertEqual(db.execute("SELECT payload FROM topic_job").fetchone()[0],"{}")

    def test_unquoted_evidence_is_rejected(self):
        with main.connect() as db:
            topics.enqueue(db,"false-evidence",[main.Turn(role="user",content="你好")],"Asia/Shanghai")
        job=topics.claim_job()
        with patch.object(main,"deepseek",AsyncMock(return_value={"content":'{"topics":[{"title":"面试","source_quote":"明天面试"}]}' } )):
            asyncio.run(topics.extract(job))
        self.assertEqual(self.client.get("/v1/topics",headers=self.headers).json(),[])

    def test_small_talk_can_produce_no_topic(self):
        with main.connect() as db:
            topics.enqueue(db,"small-talk-request",[main.Turn(role="user",content="今天好累")],"Asia/Shanghai")
        job=topics.claim_job()
        with patch.object(main,"deepseek",AsyncMock(return_value={"content":'{"topics":[]}' } )):
            asyncio.run(topics.extract(job))
        self.assertEqual(self.client.get("/v1/topics",headers=self.headers).json(),[])

    def test_suffix_is_only_new_user_words(self):
        with main.connect() as db:
            topics.enqueue(db,"new-suffix-request",[main.Turn(role="user",content="旧事情"),main.Turn(role="assistant",content="虚构的面试"),main.Turn(role="user",content="新事情"),main.Turn(role="user",content="补充一句")],"Asia/Shanghai")
            source=json.loads(db.execute("SELECT payload FROM topic_job").fetchone()[0])["source"]
        self.assertEqual(source,"新事情\n补充一句")

    def test_repeated_extraction_does_not_duplicate_or_overwrite_edit(self):
        row=self.extract()[0]
        edited=self.change(row,title="我的面试").json()
        rows=self.extract(request="topic-request-2")
        self.assertEqual(len(rows),1)
        self.assertEqual(rows[0]["title"],edited["title"])

    def test_home_followup_fires_once_and_never_pushes_by_default(self):
        row=self.extract()[0]
        due=(datetime.now(timezone.utc)+timedelta(minutes=5)).isoformat()
        scheduled=self.change(row,consent="allowed",followup_at=due).json()
        with main.connect() as db:
            db.execute("UPDATE topic SET followup_at=? WHERE id=?",((datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat(),row["id"]))
        topics.fire_followups();topics.fire_followups()
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM inbox_message").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM push_outbox").fetchone()[0],0)
            self.assertEqual(db.execute("SELECT followup_status FROM topic").fetchone()[0],"sent")
        self.assertEqual(scheduled["consent"],"allowed")

    def test_system_notification_requires_explicit_opt_in(self):
        row=self.extract()[0]
        due=(datetime.now(timezone.utc)+timedelta(minutes=5)).isoformat()
        self.change(row,consent="allowed",followup_at=due,notify=True)
        with main.connect() as db:
            db.execute("UPDATE topic SET followup_at=?",((datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat(),))
        topics.fire_followups()
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM push_outbox").fetchone()[0],1)

    def test_forget_redacts_projection_and_blocks_reextraction_with_new_title(self):
        row=self.extract()[0]
        forgotten=self.change(row,state="forgotten").json()
        self.assertEqual(forgotten["source_quote"],"")
        self.assertEqual(forgotten["title"],"已忘记的话题")
        rows=self.extract(title="面试这件事",request="topic-request-3")
        self.assertEqual(len(rows),1)
        self.assertEqual(rows[0]["state"],"forgotten")
        self.assertNotIn("明天面试",topics.context())

    def test_revoke_withdraws_not_yet_read_followup(self):
        row=self.extract()[0]
        due=(datetime.now(timezone.utc)+timedelta(minutes=5)).isoformat()
        self.change(row,consent="allowed",followup_at=due,notify=True)
        with main.connect() as db:
            db.execute("UPDATE topic SET followup_at=?",((datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat(),))
        topics.fire_followups()
        current=self.client.get("/v1/topics",headers=self.headers).json()[0]
        self.assertEqual(self.change(current,consent="declined").status_code,200)
        self.assertEqual(self.client.get("/v1/inbox?after_sequence=0",headers=self.headers).json(),[])
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT state FROM push_outbox").fetchone()[0],"cancelled")

    def test_revision_conflict_preserves_newer_user_edit(self):
        row=self.extract()[0]
        self.change(row,title="修正的名称")
        response=self.change(row,title="旧设备覆盖",operation_id="different-operation")
        self.assertEqual(response.status_code,409)
        self.assertEqual(response.json()["detail"]["current"]["title"],"修正的名称")

    def test_operation_replay_is_idempotent(self):
        row=self.extract()[0]
        first=self.change(row,title="修正名称",operation_id="same-operation").json()
        replay=self.change(row,title="修正名称",operation_id="same-operation").json()
        self.assertEqual(first,replay)
        self.assertEqual(replay["revision"],2)

    def test_schedule_requires_explicit_future_timezone(self):
        row=self.extract()[0]
        self.assertEqual(self.change(row,consent="allowed").status_code,422)
        self.assertEqual(self.change(row,consent="allowed",followup_at="2030-01-01T12:00:00").status_code,422)

    def test_expired_topic_is_not_followed_up(self):
        row=self.extract()[0]
        with main.connect() as db:
            db.execute("UPDATE topic SET expires_at=?",((datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat(),))
        topics.fire_followups()
        self.assertEqual(self.client.get("/v1/topics",headers=self.headers).json()[0]["state"],"expired")
        self.assertEqual(topics.context(),"[]")

    def test_topic_context_respects_local_suppression(self):
        row=self.extract()[0]
        self.assertIn(row["title"],topics.context())
        self.assertEqual(topics.context([row["id"]]),"[]")

    def test_natural_language_tool_can_end_a_followup(self):
        row=self.extract()[0]
        result=asyncio.run(topics.run_tool("manage_topic",{"id":row["id"],"action":"resolve"},"resolve-operation"))
        self.assertTrue(result["ok"])
        self.assertEqual(result["topic"]["state"],"resolved")
        replay=asyncio.run(topics.run_tool("manage_topic",{"id":row["id"],"action":"resolve"},"resolve-operation"))
        self.assertEqual(result,replay)
        self.assertEqual(topics.context(),"[]")

    def test_extractor_does_not_reopen_resolved_topic(self):
        row=self.extract()[0]
        self.change(row,state="resolved")
        rows=self.extract(request="resolved-new-source")
        self.assertEqual(rows[0]["state"],"resolved")
        self.assertEqual(len(rows),1)

    def test_worker_reclaims_an_interrupted_job(self):
        with main.connect() as db:
            topics.enqueue(db,"interrupted-request",[main.Turn(role="user",content="明天面试")],"Asia/Shanghai")
        first=topics.claim_job()
        self.assertIsNotNone(first)
        self.assertIsNone(topics.claim_job())
        with main.connect() as db:
            db.execute("UPDATE topic_job SET lease_until=?",((datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat(),))
        self.assertEqual(topics.claim_job()["id"],first["id"])

    def test_followup_transaction_rolls_back_with_push_failure(self):
        row=self.extract()[0]
        due=(datetime.now(timezone.utc)+timedelta(minutes=5)).isoformat()
        self.change(row,consent="allowed",followup_at=due,notify=True)
        with main.connect() as db:
            db.execute("UPDATE topic SET followup_at=?",((datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat(),))
        with patch.object(main,"enqueue_push",side_effect=RuntimeError("crash")):
            with self.assertRaises(RuntimeError):topics.fire_followups()
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM inbox_message").fetchone()[0],0)
            self.assertEqual(db.execute("SELECT followup_status FROM topic").fetchone()[0],"pending")
        topics.fire_followups()
        with main.connect() as db:self.assertEqual(db.execute("SELECT COUNT(*) FROM inbox_message").fetchone()[0],1)

    def test_separate_dated_events_are_not_merged(self):
        first=(datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        second=(datetime.now(timezone.utc)+timedelta(days=8)).isoformat()
        self.extract(text="下周还有面试",title="面试",event_at=first)
        rows=self.extract(text="下周还有面试",title="面试",request="different-event",event_at=second)
        self.assertEqual(len(rows),2)

    def test_unpaired_device_cannot_read_or_edit_topics(self):
        self.assertEqual(self.client.get("/v1/topics").status_code,401)
        self.assertEqual(self.client.post("/v1/topics/x",json={}).status_code,401)


if __name__=="__main__":unittest.main()
