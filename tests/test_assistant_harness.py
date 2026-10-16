"""Run with: PYTHONPATH=server python -m unittest discover -s tests -p 'test_*.py'."""
import asyncio
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient
from app import main


class HarnessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.original_db = main.DB_PATH
        main.DB_PATH = Path(self.temp.name) / "test.db"
        main.chat_lock = asyncio.Lock()
        main.setup_db()
        with main.connect() as db:
            db.execute("INSERT INTO device(id,name,token_hash,fcm_token,updated_at) VALUES(?,?,?,?,?)",
                       ("test-device", "test", main.token_hash("test-token"), "fake-fcm", main.utc_now()))
        self.client = TestClient(main.app)
        self.headers = {"Authorization": "Bearer test-token"}

    def tearDown(self):
        self.client.close()
        main.DB_PATH = self.original_db
        self.temp.cleanup()

    def due_reminder(self):
        with main.connect() as db:
            db.execute("INSERT INTO reminder(id,title,due_at,created_at) VALUES(?,?,?,?)",
                       ("test-reminder", "带上材料", (datetime.now(timezone.utc)-timedelta(minutes=1)).isoformat(), main.utc_now()))

    def test_repeated_scheduler_persists_one_message_and_push(self):
        self.due_reminder()
        main.fire_due_reminders()
        main.fire_due_reminders()
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM inbox_message").fetchone()[0], 1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM push_outbox").fetchone()[0], 1)
            self.assertEqual(db.execute("SELECT state FROM reminder").fetchone()[0], "fired")

    def test_transaction_rolls_back_if_delivery_persistence_fails(self):
        self.due_reminder()
        with patch.object(main, "enqueue_push", side_effect=RuntimeError("simulated crash")):
            with self.assertRaises(RuntimeError):
                main.fire_due_reminders()
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT state FROM reminder").fetchone()[0], "pending")
            self.assertEqual(db.execute("SELECT COUNT(*) FROM inbox_message").fetchone()[0], 0)
        main.fire_due_reminders()
        self.assertEqual(len(self.client.get("/v1/inbox", headers=self.headers).json()), 1)

    def test_naive_and_past_dates_are_rejected(self):
        self.assertEqual(main.create_reminder("带材料", "2030-01-01T15:00:00")["ok"], "false")
        self.assertEqual(main.create_reminder("带材料", "2020-01-01T15:00:00+08:00")["ok"], "false")

    def test_operation_id_prevents_duplicate_tool_side_effect(self):
        due = (datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        first = main.create_reminder("带材料", due, "stable-operation")
        second = main.create_reminder("重复请求", due, "stable-operation")
        self.assertEqual(first["id"], second["id"])
        self.assertEqual(second["title"], "带材料")
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM reminder").fetchone()[0], 1)

    def test_chat_retry_reuses_response_without_reexecuting_tools(self):
        due = (datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        model = AsyncMock(side_effect=[
            {"role":"assistant", "content":None, "tool_calls":[{"id":"call1", "type":"function", "function":{"name":"create_reminder", "arguments":json.dumps({"title":"带材料", "due_at":due})}}]},
            {"role":"assistant", "content":"记好了。"},
        ])
        payload = {"messages":[{"role":"user", "content":"明天提醒我带材料"}], "client_message_id":"stable-request-1"}
        with patch.object(main, "deepseek", model):
            first = self.client.post("/v1/chat", json=payload, headers=self.headers)
            retry = self.client.post("/v1/chat", json=payload, headers=self.headers)
        self.assertEqual(first.status_code, 200)
        self.assertEqual(first.json(), retry.json())
        self.assertEqual(model.await_count, 2)
        self.assertIn("提醒已创建", first.json()["tool_results"][0]["summary"])
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM reminder").fetchone()[0], 1)

    def test_reminder_snooze_and_complete_cancel_old_push(self):
        self.due_reminder()
        main.fire_due_reminders()
        response = self.client.post("/v1/reminders/test-reminder/action", json={"action":"snooze"}, headers=self.headers)
        self.assertEqual(response.status_code, 200)
        with main.connect() as db:
            row = db.execute("SELECT * FROM reminder").fetchone()
            self.assertEqual(row["state"], "pending")
            self.assertGreater(datetime.fromisoformat(row["due_at"]), datetime.now(timezone.utc))
            self.assertEqual(db.execute("SELECT state FROM push_outbox").fetchone()[0], "cancelled")
        self.client.post("/v1/reminders/test-reminder/action", json={"action":"complete"}, headers=self.headers)
        self.assertEqual(self.client.get("/v1/reminders", headers=self.headers).json(), [])

    def test_proactive_can_stay_silent_and_has_no_tools(self):
        model = AsyncMock(return_value={"content":'{"content":""}'})
        with patch.object(main, "deepseek", model):
            response = self.client.post("/v1/home", json={"messages":[]}, headers=self.headers)
        self.assertEqual(response.json(), {"content":""})
        self.assertEqual(len(model.call_args.args), 1)
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM reminder").fetchone()[0], 0)

    def test_failed_final_reply_resumes_saved_tool_plan(self):
        due = (datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        model = AsyncMock(side_effect=[
            {"role":"assistant", "content":None, "tool_calls":[{"id":"call1", "type":"function", "function":{"name":"create_reminder", "arguments":json.dumps({"title":"带材料", "due_at":due})}}]},
            main.HTTPException(502, "model failed after tool completed"),
            {"role":"assistant", "content":"记好了。"},
        ])
        payload = {"messages":[{"role":"user", "content":"明天提醒我带材料"}], "client_message_id":"resume-request-1"}
        with patch.object(main, "deepseek", model):
            first = self.client.post("/v1/chat", json=payload, headers=self.headers)
            retry = self.client.post("/v1/chat", json=payload, headers=self.headers)
        self.assertEqual(first.status_code, 502)
        self.assertEqual(retry.status_code, 200)
        self.assertEqual(model.await_count, 3)
        # The resumed model context already includes the successful tool result.
        self.assertEqual(model.call_args.args[0][-1]["role"], "tool")
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM reminder").fetchone()[0], 1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM chat_checkpoint").fetchone()[0], 0)

    def test_inbox_cursor_does_not_lose_more_than_one_page(self):
        with main.connect() as db:
            for i in range(125):
                db.execute("INSERT INTO inbox_message(id,title,body,created_at) VALUES(?,?,?,?)", (f"message-{i}", "提醒", str(i), main.utc_now()))
        first = self.client.get("/v1/inbox?after_sequence=0", headers=self.headers).json()
        second = self.client.get(f"/v1/inbox?after_sequence={first[-1]['sequence']}", headers=self.headers).json()
        self.assertEqual(len(first), 100)
        self.assertEqual(len(second), 25)
        self.assertEqual(len({m["id"] for m in first+second}), 125)

    def test_tool_correction_updates_existing_reminder(self):
        due = (datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        created = main.create_reminder("带材料", due, "original")
        new_due = (datetime.now(timezone.utc)+timedelta(days=2)).isoformat()
        result = asyncio.run(main.run_tool("manage_reminder", {"id":created["id"], "action":"update", "due_at":new_due}))
        self.assertTrue(result["ok"])
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM reminder").fetchone()[0], 1)
            self.assertEqual(db.execute("SELECT due_at FROM reminder").fetchone()[0], new_due)

    def test_unpaired_device_cannot_read_or_change_reminders(self):
        self.assertEqual(self.client.get("/v1/reminders").status_code, 401)
        self.assertEqual(self.client.post("/v1/home", json={}).status_code, 401)


if __name__ == "__main__":
    unittest.main()
