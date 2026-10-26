"""Policy and gateway regression tests run without downloading embedding weights."""
import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch
from fastapi.testclient import TestClient
import httpx
from app import main
from app.decision import Decision, DecisionRequest, Router, apply_policy, fallback

class PolicyTests(unittest.TestCase):
    def request(self, text, tools=None):
        return DecisionRequest(text=text, available_tools=tools or ["open_sync", "open_print", "create_reminder", "list_reminders"])

    def test_all_parameter_policies(self):
        for text, tool, kind in [
            ("同步一下笔记", "open_sync", "DIRECT_TOOL"),
            ("打开打印", "open_print", "DIRECT_TOOL"),
            ("打印午饭花了28块这句话", "open_print", "TOOL_WITH_LLM"),
            ("明天八点提醒我吃药", "create_reminder", "TOOL_WITH_LLM"),
            ("同步工作笔记", "open_sync", "LLM_FALLBACK"),
        ]:
            with self.subTest(text=text):
                self.assertEqual(apply_policy(self.request(text), tool, .9).type, kind)

    def test_questions_negation_quotes_and_multiple_actions_never_direct(self):
        for text in ["如何同步笔记", "能同步笔记吗", "你可以打印吗", "同步笔记是什么", "不要同步笔记", "打开打印然后同步笔记", '他说“打开打印”', "假如我同步笔记", "为什么天空是蓝色的", "我的打印机坏了", "明天早上八点要吃药", "今天已经同步笔记了"]:
            for tool in ["open_sync", "open_print", "create_reminder"]:
                with self.subTest(text=text, tool=tool):
                    self.assertEqual(apply_policy(self.request(text), tool, .99).type, "LLM_FALLBACK")

    def test_unregistered_and_unavailable_tools(self):
        self.assertEqual(apply_policy(self.request("打开打印", ["open_sync"]), "open_print", 1).type, "LLM_FALLBACK")
        self.assertEqual(apply_policy(self.request("删除所有文件"), "shell", 1).type, "LLM_FALLBACK")

    def test_embedding_threshold_and_cross_tool_margin(self):
        from app.decision import REGISTRY
        samples = [s for t in REGISTRY for s in [t.description, *t.examples]]
        def encode(texts):
            return [[1., 0.] if text in samples else [0., 1.] for text in texts]
        router = Router(encode)
        self.assertEqual(router.decide(self.request("打开打印")).type, "DIRECT_TOOL")
        self.assertEqual(router.decide(self.request("帮我打印一张纸")).reason, "low_score_or_ambiguous")
        # Even high similarity to all tools must not select the first candidate.
        router.encode = lambda texts: [[1., 0.] for _ in texts]
        self.assertEqual(router.decide(self.request("帮我打印一张纸")).reason, "low_score_or_ambiguous")

    def test_semantic_optional_and_required_paths(self):
        from app.decision import REGISTRY
        for target, text in [("open_print", "打印文字午饭28元"), ("create_reminder", "提醒我明天八点吃药")]:
            samples = {s: t.name for t in REGISTRY for s in [t.description, *t.examples]}
            def encode(texts):
                return [[1., 0.] if samples.get(t, target) == target else [0., 1.] for t in texts]
            self.assertEqual(Router(encode).decide(self.request(text)).type, "TOOL_WITH_LLM")

class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.old_db = main.DB_PATH
        main.DB_PATH = Path(self.temp.name) / "test.db"
        main.chat_lock = asyncio.Lock()
        main.setup_db()
        with main.connect() as db:
            db.execute("INSERT INTO device(id,token_hash,updated_at) VALUES(?,?,?)", ("phone", main.token_hash("token"), main.utc_now()))
        self.client = TestClient(main.app)
        self.headers = {"Authorization": "Bearer token"}
        self.payload = {"messages": [{"role": "user", "content": "打开打印"}], "client_message_id": "decision-test-1", "decision_enabled": True, "client_tools": ["open_sync", "open_print", "open_topics"]}

    def tearDown(self):
        self.client.close()
        main.DB_PATH = self.old_db
        self.temp.cleanup()

    def send(self):
        return self.client.post("/v1/chat", json=self.payload, headers=self.headers)

    def route(self, kind="DIRECT_TOOL", tool="open_print"):
        return patch.object(main, "route_request", AsyncMock(return_value=Decision(type=kind, tool=tool, score=.95, reason="test")))

    def call(self, name, args):
        return {"role": "assistant", "tool_calls": [{"id": "c1", "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}]}

    def test_device_auth_is_required(self):
        self.assertEqual(self.client.post("/v1/chat", json=self.payload).status_code, 401)

    def test_direct_ui_skips_model_and_retry_uses_same_action(self):
        model=AsyncMock()
        with self.route(), patch.object(main, "deepseek", model):
            first=self.send(); second=self.send()
        self.assertEqual(first.status_code,200)
        self.assertEqual(first.json(),second.json())
        self.assertEqual(first.json()["client_action"]["name"],"open_print")
        model.assert_not_awaited()

    def test_direct_read_uses_existing_executor(self):
        with self.route(tool="list_reminders"), patch.object(main,"deepseek",AsyncMock()) as model:
            reply=self.send().json()
        self.assertEqual(reply["tool_results"][0]["result"]["reminders"],[])
        model.assert_not_awaited()

    def test_optional_explicit_text_is_preserved_as_draft(self):
        self.payload["messages"][0]["content"]="打印这句话：午饭花了28元"
        with self.route("TOOL_WITH_LLM"), patch.object(main,"deepseek",AsyncMock(return_value=self.call("open_print",{"text":"午饭花了28元"}))) as model:
            reply=self.send().json()
        self.assertEqual(reply["client_action"]["arguments"],{"text":"午饭花了28元"})
        self.assertEqual([t["function"]["name"] for t in model.call_args.args[1]],["open_print"])

    def test_invalid_or_missing_optional_parameters_do_not_open_ui(self):
        for args in [{}, {"text":123}, {"text":"abc", "copies":3}]:
            self.payload["client_message_id"] += "x"
            with self.route("TOOL_WITH_LLM"), patch.object(main,"deepseek",AsyncMock(side_effect=[self.call("open_print",args),{"content":"请提供要打印的文字。"}])):
                self.assertNotIn("client_action",self.send().json())

    def test_required_missing_fields_and_foreign_tool_are_rejected(self):
        for name,args in [("create_reminder",{"title":"吃药"}), ("save_memory",{"category":"other","fact":"x","confidence":.9})]:
            self.payload["client_message_id"] += "x"
            with self.route("TOOL_WITH_LLM","create_reminder"), patch.object(main,"deepseek",AsyncMock(side_effect=[self.call(name,args),{"content":"请补充时间。"}])):
                reply=self.send().json()
            self.assertFalse(reply["tool_results"][0]["result"]["ok"])
            with main.connect() as db:
                self.assertEqual(db.execute("SELECT count(*) FROM reminder").fetchone()[0],0)
                self.assertEqual(db.execute("SELECT count(*) FROM memory").fetchone()[0],0)

    def test_non_action_disables_and_enforces_no_model_tools(self):
        with patch.object(main,"route_request",AsyncMock(return_value=fallback("non_action"))), patch.object(main,"deepseek",AsyncMock(side_effect=[self.call("save_memory",{"category":"other","fact":"x","confidence":.9}),{"content":"这是同步功能。"}])) as model:
            reply=self.send().json()
        self.assertEqual(model.call_args.args[1],[])
        self.assertFalse(reply["tool_results"][0]["result"]["ok"])

    def test_disabled_and_unavailable_preserve_original_llm_tools(self):
        for reason in ["disabled", "unavailable", "low_score_or_ambiguous"]:
            self.payload["client_message_id"] += "x"
            with patch.object(main,"route_request",AsyncMock(return_value=fallback(reason))), patch.object(main,"deepseek",AsyncMock(return_value={"content":"原有回复"})) as model:
                self.assertEqual(self.send().json()["content"],"原有回复")
            self.assertEqual(model.call_args.args[1],[*main.TOOLS,*[t for t in main.CLIENT_TOOLS if t["function"]["name"] in self.payload["client_tools"]]])

    def test_gateway_rejects_forged_direct_policy_and_handles_timeout(self):
        request=main.ChatRequest(**self.payload)
        response=httpx.Response(200,json={"type":"DIRECT_TOOL","tool":"create_reminder","score":.99,"reason":"x"})
        response.request=httpx.Request("POST","http://router/decide")
        client=AsyncMock();client.__aenter__.return_value=client;client.post.return_value=response
        with patch.dict(os.environ,{"DECISION_URL":"http://router"}),patch.object(main.httpx,"AsyncClient",return_value=client):
            self.assertEqual(asyncio.run(main.route_request(request)).reason,"invalid_router_response")
            client.post.side_effect=httpx.ReadTimeout("timeout")
            self.assertEqual(asyncio.run(main.route_request(request)).reason,"unavailable")

    def test_actual_gateway_question_and_cancel_take_different_llm_paths(self):
        with patch.dict(os.environ,{"DECISION_URL":"http://router"}):
            for text, reason in [("如何同步笔记", "non_action"),("取消明天的提醒", "conversation_context"),("我的打印机坏了", "conversation_context")]:
                self.payload["messages"][0]["content"]=text
                self.assertEqual(asyncio.run(main.route_request(main.ChatRequest(**self.payload))).reason, reason)

    def test_routed_retry_resumes_plan_and_does_not_create_again(self):
        from datetime import datetime, timedelta, timezone
        due=(datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        model=AsyncMock(side_effect=[self.call("create_reminder",{"title":"吃药","due_at":due}),main.HTTPException(502,"temporary failure"),{"content":"提醒已创建"}])
        with self.route("TOOL_WITH_LLM","create_reminder") as route,patch.object(main,"deepseek",model):
            self.assertEqual(self.send().status_code,502)
            self.assertEqual(self.send().status_code,200)
        route.assert_awaited_once()
        self.assertEqual(model.call_args.args[1],[])
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM reminder").fetchone()[0],1)

    def test_routed_model_cannot_create_twice(self):
        from datetime import datetime, timedelta, timezone
        due=(datetime.now(timezone.utc)+timedelta(days=1)).isoformat()
        call=self.call("create_reminder",{"title":"吃药","due_at":due})
        with self.route("TOOL_WITH_LLM","create_reminder"),patch.object(main,"deepseek",AsyncMock(side_effect=[call,call,{"content":"提醒已创建"}])):
            reply=self.send().json()
        self.assertTrue(reply["tool_results"][0]["result"]["ok"])
        self.assertFalse(reply["tool_results"][1]["result"]["ok"])
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM reminder").fetchone()[0],1)

    def test_llm_fallback_opens_sync_without_bge(self):
        self.payload["messages"][0]["content"]="同步笔记"
        self.payload["decision_enabled"]=False
        model=AsyncMock(return_value=self.call("open_sync",{}))
        with patch.object(main,"deepseek",model):
            first=self.send();retry=self.send()
        self.assertEqual(first.status_code,200)
        self.assertEqual(first.json()["client_action"],{"name":"open_sync","arguments":{},"id":self.payload["client_message_id"]})
        self.assertEqual(first.json(),retry.json())
        model.assert_awaited_once()
        with main.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM reminder").fetchone()[0],0)

    def test_local_tool_rejected_for_question_or_unavailable_client(self):
        for text,capabilities,args in [("如何同步笔记",["open_sync"],{}),("同步笔记",[],{}),("同步笔记",["open_sync"],{"command":"sync"})]:
            self.payload.update(messages=[{"role":"user","content":text}],client_tools=capabilities,decision_enabled=False)
            self.payload["client_message_id"] += "x"
            with patch.object(main,"deepseek",AsyncMock(side_effect=[self.call("open_sync",args),{"content":"请确认要打开的功能。"}])):
                reply=self.send().json()
            self.assertNotIn("client_action",reply)
            self.assertFalse(reply["tool_results"][0]["result"]["ok"])

    def test_legacy_client_has_original_tools_only(self):
        self.payload["client_tools"]=[]
        self.payload["decision_enabled"]=False
        with patch.object(main,"deepseek",AsyncMock(return_value={"content":"正常回复"})) as model:
            self.send()
        self.assertEqual(model.call_args.args[1],main.TOOLS)

    def test_llm_opens_print_without_bge_and_preserves_draft(self):
        self.payload.update(messages=[{"role":"user","content":"打印文字午饭28元"}],decision_enabled=False)
        with patch.object(main,"deepseek",AsyncMock(return_value=self.call("open_print",{"text":"午饭28元"}))):
            reply=self.send().json()
        self.assertEqual(reply["client_action"]["arguments"],{"text":"午饭28元"})

    def test_executor_rejects_types_unknown_keys_and_unknown_tool(self):
        for name,args in [("create_reminder",{"title":42,"due_at":False}), ("list_reminders",{"shell":"ls"}), ("unknown",{})]:
            self.assertFalse(asyncio.run(main.run_tool(name,args))["ok"])

    def test_life_record_returns_pending_phone_action_with_multiple_amounts(self):
        self.payload.update(decision_enabled=False,client_tools=["create_life_record"])
        self.payload["messages"][0]["content"]="记一下我今天加油400元、买水5元"
        args={"content":"今天加油、买水","items":[{"amount":"400","currency":"CNY","kind":"expense","category":"加油"},{"amount":"5","currency":"CNY","kind":"expense","category":"饮料"}]}
        with patch.object(main,"deepseek",AsyncMock(return_value=self.call("create_life_record",args))) as model:
            first=self.send();retry=self.send()
        self.assertEqual(first.status_code,200)
        self.assertEqual(first.json()["client_action"]["arguments"],args)
        self.assertNotIn("已记录",first.json()["content"])
        self.assertEqual(first.json(),retry.json());model.assert_awaited_once()

    def test_diary_question_cannot_write_or_use_unadvertised_tool(self):
        for text,capability in [("可以记一下我今天的支出吗",["create_life_record"]),("记一下今天很开心",[]),("同步笔记",["create_life_record"])]:
            self.payload["client_message_id"] += "x"
            self.payload.update(decision_enabled=False,client_tools=capability)
            self.payload["messages"][0]["content"]=text
            with patch.object(main,"deepseek",AsyncMock(side_effect=[self.call("create_life_record",{"content":"今天很开心","items":[]}),{"content":"请说明要记录的内容"}])):
                self.assertNotIn("client_action",self.send().json())

    def test_confirmed_life_record_reaches_phone_with_context_and_retry(self):
        source = "今天加了340块钱的油，现在的里程是 61978 公里"
        offer = "要我把这笔记进生活记录吗？\n\n- 油费：340元\n- 当前里程：61978公里"
        args = {"content": "今天加油340元，当前里程61978公里", "items": [{"amount": "340", "currency": "CNY", "kind": "expense", "category": "油费"}]}
        for index, answer in enumerate(["好", "好的。", "可以", "确认"]):
            with self.subTest(answer=answer):
                self.payload.update(messages=[{"role": "user", "content": source}, {"role": "assistant", "content": offer}, {"role": "user", "content": answer}], client_message_id=f"confirmed-life-record-{index}", client_tools=["create_life_record"])
                model = AsyncMock(return_value=self.call("create_life_record", args))
                # The non-action router must still expose this context-authorized tool.
                with patch.object(main, "route_request", AsyncMock(return_value=fallback("non_action"))), patch.object(main, "deepseek", model):
                    first = self.send(); retry = self.send()
                self.assertEqual(first.status_code, 200)
                self.assertEqual(first.json()["client_action"]["arguments"], args)
                self.assertEqual(first.json(), retry.json())
                model.assert_awaited_once()
                self.assertIn("create_life_record", {tool["function"]["name"] for tool in model.call_args.args[1]})
                self.assertIn("61978", str(model.call_args.args[0]))
                self.assertNotIn("已记录", first.json()["content"])

    def test_record_confirmation_does_not_authorize_other_actions_or_stale_offers(self):
        from app.decision import record_requested
        source = main.Turn(role="user", content="今天加了340块钱的油，现在的里程是61978公里")
        offer = main.Turn(role="assistant", content="要我把这笔记进生活记录吗？")
        for answer in ["不要", "不用了", "先不记", "可以吗？", "好，但别保存", "好，340改成400", "好，打印一下"]:
            self.assertFalse(record_requested([source, offer, main.Turn(role="user", content=answer)]), answer)
        for question in ["要我提醒你吗？", "要记入生活记录还是打印？", "已经记进生活记录了。要我打印吗？", "要我把生日加到重要日期吗？"]:
            self.assertFalse(record_requested([source, main.Turn(role="assistant", content=question), main.Turn(role="user", content="好")]), question)
        self.assertFalse(record_requested([main.Turn(role="user", content="好")]))
        self.assertFalse(record_requested([source, offer, main.Turn(role="user", content="换个话题"), main.Turn(role="assistant", content="今天过得好吗？"), main.Turn(role="user", content="好")]))
        self.assertFalse(record_requested([main.Turn(role="user", content="记一下今天加油340元"), main.Turn(role="user", content="不要记了")]))

    def test_record_required_policy_never_direct(self):
        request=DecisionRequest(text="记一下我今天加了油400块",available_tools=["create_life_record"])
        self.assertEqual(apply_policy(request,"create_life_record",1).type,"TOOL_WITH_LLM")

if __name__ == "__main__":
    unittest.main()
