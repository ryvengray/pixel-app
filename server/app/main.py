from __future__ import annotations

import asyncio
import hashlib
import json
import os
import secrets
import sqlite3
import tempfile
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx
from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field


ROOT = Path(__file__).resolve().parent.parent


def load_env() -> None:
    """Small .env reader so secrets never need a Python dotenv dependency."""
    path = ROOT / ".env"
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


load_env()
DB_PATH = Path(os.getenv("DATABASE_PATH", str(ROOT / "data" / "greta.db"))).expanduser()
PUBLIC_BASE_URL = os.getenv("GRAY_PUBLIC_BASE_URL", "").rstrip("/")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def token_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class DatabaseConnection(sqlite3.Connection):
    def __exit__(self, exc_type, exc_value, traceback):
        try:
            return super().__exit__(exc_type, exc_value, traceback)
        finally:
            self.close()


def connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, factory=DatabaseConnection)
    conn.row_factory = sqlite3.Row
    return conn


def setup_db() -> None:
    with connect() as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS device (
            id TEXT PRIMARY KEY, name TEXT, token_hash TEXT UNIQUE, fcm_token TEXT, updated_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS pairing (
            id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, expires_at TEXT NOT NULL,
            claimed_at TEXT, device_id TEXT
        );
        CREATE TABLE IF NOT EXISTS memory (
            id TEXT PRIMARY KEY, category TEXT NOT NULL, fact TEXT NOT NULL,
            confidence REAL NOT NULL, source TEXT, revision INTEGER NOT NULL DEFAULT 1,
            deleted INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS reminder (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, due_at TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'pending', created_at TEXT NOT NULL, fired_at TEXT
        );
        CREATE TABLE IF NOT EXISTS inbox_message (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL,
            created_at TEXT NOT NULL, read_at TEXT
        );
        CREATE TABLE IF NOT EXISTS chat_response (
            id TEXT PRIMARY KEY, response TEXT NOT NULL, created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS chat_checkpoint (
            id TEXT PRIMARY KEY, state TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS push_outbox (
            message_id TEXT NOT NULL, device_id TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            next_attempt_at TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
            PRIMARY KEY(message_id,device_id)
        );
        CREATE TABLE IF NOT EXISTS printer (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, host TEXT NOT NULL UNIQUE,
            port INTEGER NOT NULL DEFAULT 631, protocol TEXT NOT NULL DEFAULT 'ipp',
            model TEXT, location TEXT, last_seen TEXT, added_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS print_job (
            id TEXT PRIMARY KEY, printer_id TEXT NOT NULL, file_name TEXT NOT NULL,
            file_type TEXT NOT NULL, copies INTEGER NOT NULL DEFAULT 1,
            status TEXT NOT NULL DEFAULT 'pending', error TEXT,
            created_at TEXT NOT NULL, completed_at TEXT
        );
        """)
        columns = {row[1] for row in db.execute("PRAGMA table_info(device)").fetchall()}
        if "name" not in columns:
            db.execute("ALTER TABLE device ADD COLUMN name TEXT")
        if "token_hash" not in columns:
            db.execute("ALTER TABLE device ADD COLUMN token_hash TEXT")
        printer_columns = {row[1] for row in db.execute("PRAGMA table_info(printer)").fetchall()}
        if "formats" not in printer_columns:
            db.execute("ALTER TABLE printer ADD COLUMN formats TEXT")


async def require_device(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    supplied = authorization.removeprefix("Bearer ") if authorization else ""
    if not supplied:
        raise HTTPException(401, "Device token is required")
    with connect() as db:
        device = db.execute("SELECT * FROM device WHERE token_hash = ?", (token_hash(supplied),)).fetchone()
    if not device:
        raise HTTPException(401, "Unknown or revoked device")
    return dict(device)


class Turn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=16000)


class ChatRequest(BaseModel):
    messages: list[Turn] = Field(min_length=1, max_length=30)
    timezone: str = Field(default="Asia/Shanghai", max_length=80)
    client_message_id: str | None = Field(default=None, min_length=8, max_length=100)


class DeviceRequest(BaseModel):
    fcm_token: str | None = Field(default=None, max_length=4096)


class PairingClaim(BaseModel):
    secret: str = Field(min_length=32, max_length=200)
    device_name: str = Field(min_length=1, max_length=100)


class MemoryRecord(BaseModel):
    id: str = Field(min_length=8, max_length=100)
    category: Literal["preference", "profile", "goal", "project", "relationship", "other"]
    fact: str = Field(min_length=1, max_length=1000)
    confidence: float = Field(ge=0, le=1)
    source: str | None = Field(default=None, max_length=1000)
    revision: int = Field(default=1, ge=1)
    deleted: bool = False
    updated_at: str


class ReminderRequest(BaseModel):
    title: str = Field(min_length=1, max_length=300)
    due_at: str = Field(min_length=16, max_length=64)


def active_memories() -> list[dict[str, Any]]:
    with connect() as db:
        rows = db.execute("SELECT * FROM memory WHERE deleted = 0 ORDER BY updated_at DESC LIMIT 40").fetchall()
    return [dict(row) for row in rows]


def upsert_memory(record: dict[str, Any]) -> None:
    with connect() as db:
        current = db.execute("SELECT revision FROM memory WHERE id = ?", (record["id"],)).fetchone()
        if current and current["revision"] > record.get("revision", 1):
            return
        db.execute("""INSERT INTO memory(id, category, fact, confidence, source, revision, deleted, updated_at)
        VALUES(:id,:category,:fact,:confidence,:source,:revision,:deleted,:updated_at)
        ON CONFLICT(id) DO UPDATE SET category=excluded.category, fact=excluded.fact,
        confidence=excluded.confidence, source=excluded.source, revision=excluded.revision,
        deleted=excluded.deleted, updated_at=excluded.updated_at""", record)


def create_pairing() -> dict[str, str]:
    if not PUBLIC_BASE_URL.startswith("https://"):
        raise RuntimeError("GRAY_PUBLIC_BASE_URL must be a public HTTPS URL")
    pairing_id = secrets.token_urlsafe(12)
    secret = secrets.token_urlsafe(32)
    expires_at = (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat()
    with connect() as db:
        db.execute("INSERT INTO pairing(id,secret_hash,expires_at) VALUES(?,?,?)", (pairing_id, token_hash(secret), expires_at))
    return {"id": pairing_id, "secret": secret, "endpoint": f"{PUBLIC_BASE_URL}/v1/pairings/{pairing_id}/claim", "expires_at": expires_at}


def claim_pairing(pairing_id: str, claim: PairingClaim) -> dict[str, str]:
    now = datetime.now(timezone.utc)
    with connect() as db:
        pairing = db.execute("SELECT * FROM pairing WHERE id = ?", (pairing_id,)).fetchone()
        if not pairing or pairing["claimed_at"]:
            raise HTTPException(410, "This pairing code is no longer available")
        expires_at = datetime.fromisoformat(pairing["expires_at"])
        if now >= expires_at or not secrets.compare_digest(token_hash(claim.secret), pairing["secret_hash"]):
            raise HTTPException(410, "This pairing code is invalid or expired")
        device_id = secrets.token_urlsafe(16)
        device_token = secrets.token_urlsafe(32)
        changed = db.execute("UPDATE pairing SET claimed_at=?, device_id=? WHERE id=? AND claimed_at IS NULL", (now.isoformat(), device_id, pairing_id)).rowcount
        if changed != 1:
            raise HTTPException(410, "This pairing code was already used")
        db.execute("INSERT INTO device(id,name,token_hash,updated_at) VALUES(?,?,?,?)", (device_id, claim.device_name, token_hash(device_token), now.isoformat()))
    return {"device_id": device_id, "device_token": device_token}


TOOLS = [
    {"type": "function", "function": {"name": "save_memory", "description": "Save one stable, explicit user preference or fact. Never save a fleeting mood, sensitive data, or a guess.", "parameters": {"type": "object", "properties": {"category": {"type": "string", "enum": ["preference", "profile", "goal", "project", "relationship", "other"]}, "fact": {"type": "string"}, "confidence": {"type": "number"}}, "required": ["category", "fact", "confidence"], "additionalProperties": False}}},
    {"type": "function", "function": {"name": "create_reminder", "description": "Create a reminder only when the user asks and the due time is explicit. due_at must be ISO-8601 with timezone.", "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "due_at": {"type": "string"}}, "required": ["title", "due_at"], "additionalProperties": False}}},
    {"type": "function", "function": {"name": "list_reminders", "description": "Read active reminders to identify the exact reminder the user wants to change or complete.", "parameters": {"type": "object", "properties": {}, "additionalProperties": False}}},
    {"type": "function", "function": {"name": "manage_reminder", "description": "Change, complete or cancel an existing reminder only at the user's request. Use list_reminders to obtain its id. Ask if the target is ambiguous. For update pass an explicit timezone-aware due_at and/or title.", "parameters": {"type": "object", "properties": {"id": {"type": "string"}, "action": {"type": "string", "enum": ["update", "complete", "cancel"]}, "title": {"type": "string"}, "due_at": {"type": "string"}}, "required": ["id", "action"], "additionalProperties": False}}},
]


def system_prompt(user_timezone: str) -> str:
    memories = active_memories()
    memory_text = "\n".join(f"- [{m['category']}] {m['fact']}" for m in memories) or "(none yet)"
    try:
        current = datetime.now(ZoneInfo(user_timezone)).isoformat()
    except (ZoneInfoNotFoundError, ValueError):
        current = utc_now()
    return f"""You are Greta, a warm concise personal assistant. The user time zone is {user_timezone}. Current date and time: {current}.
The user talks to you through a pet's speech bubble on the home screen. Keep replies natural and concise; put tool results in tools rather than repeating long output. Consecutive user messages can be supplements or corrections; consider them together before taking action.
Only claim an action succeeded when its tool result confirms success. Never claim an unavailable tool ran.
Use memories only when relevant; they may be outdated and the user's correction wins. Do not mention hidden memory unless useful.
For a stable, explicit preference or fact, call save_memory. Do not infer personality from one event and do not save sensitive information.
For a requested reminder with an unambiguous date/time, call create_reminder. If the time is unclear, ask a short follow-up.
Current synchronized memories:\n{memory_text}"""


async def deepseek(messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    key = os.getenv("DEEPSEEK_API_KEY", "")
    if not key:
        raise HTTPException(503, "DEEPSEEK_API_KEY is not configured")
    body: dict[str, Any] = {"model": DEEPSEEK_MODEL, "messages": messages, "temperature": 0.7}
    if tools:
        body["tools"] = tools
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            response = await client.post(f"{DEEPSEEK_BASE_URL}/chat/completions", headers={"Authorization": f"Bearer {key}"}, json=body)
            response.raise_for_status()
            return response.json()["choices"][0]["message"]
    except httpx.HTTPError as exc:
        raise HTTPException(502, f"DeepSeek request failed: {exc}") from exc


def create_reminder(title: str, due_at: str, operation_id: str | None = None) -> dict[str, str]:
    try:
        due = datetime.fromisoformat(due_at.replace("Z", "+00:00"))
    except ValueError as exc:
        return {"ok": "false", "error": "due_at is not a valid ISO-8601 timestamp"}
    if due.tzinfo is None:
        return {"ok": "false", "error": "due_at must include a timezone"}
    if not title.strip() or len(title) > 300:
        return {"ok": "false", "error": "title must contain 1 to 300 characters"}
    if due <= datetime.now(timezone.utc):
        return {"ok": "false", "error": "due_at must be in the future"}
    reminder_id = operation_id or secrets.token_urlsafe(12)
    with connect() as db:
        existing = db.execute("SELECT title,due_at FROM reminder WHERE id=?", (reminder_id,)).fetchone()
        if existing:
            return {"ok": "true", "id": reminder_id, "title": existing["title"], "due_at": existing["due_at"]}
        db.execute("INSERT INTO reminder(id,title,due_at,created_at) VALUES(?,?,?,?)", (reminder_id, title, due.astimezone(timezone.utc).isoformat(), utc_now()))
    return {"ok": "true", "id": reminder_id, "title": title, "due_at": due.isoformat()}


async def run_tool(name: str, arguments: dict[str, Any], operation_id: str | None = None) -> dict[str, Any]:
    if name == "save_memory":
        record = {"id": operation_id or secrets.token_urlsafe(12), "category": arguments["category"], "fact": arguments["fact"], "confidence": arguments["confidence"], "source": "assistant extraction", "revision": 1, "deleted": 0, "updated_at": utc_now()}
        upsert_memory(record)
        return {"ok": True, "memory": record}
    if name == "create_reminder":
        return create_reminder(arguments["title"], arguments["due_at"], operation_id)
    if name == "list_reminders":
        with connect() as db:
            return {"ok": True, "reminders": [dict(row) for row in db.execute("SELECT id,title,due_at,state FROM reminder WHERE state IN ('pending','fired') ORDER BY due_at LIMIT 100").fetchall()]}
    if name == "manage_reminder":
        with connect() as db:
            row = db.execute("SELECT * FROM reminder WHERE id=?", (arguments["id"],)).fetchone()
            if not row:
                return {"ok": False, "error": "Reminder not found"}
            action = arguments["action"]
            if action in ("complete", "cancel"):
                state = "completed" if action == "complete" else "cancelled"
                db.execute("UPDATE reminder SET state=? WHERE id=?", (state, row["id"]))
            elif action == "update":
                title = arguments.get("title", row["title"])
                if not isinstance(title, str) or not title.strip() or len(title) > 300:
                    return {"ok": False, "error": "Invalid reminder title"}
                due = datetime.fromisoformat(arguments.get("due_at", row["due_at"]).replace("Z", "+00:00"))
                if due.tzinfo is None or due <= datetime.now(timezone.utc):
                    return {"ok": False, "error": "A future timezone-aware time is required"}
                db.execute("UPDATE reminder SET title=?,due_at=?,state='pending',fired_at=NULL WHERE id=?", (title, due.astimezone(timezone.utc).isoformat(), row["id"]))
            else:
                return {"ok": False, "error": "Unknown action"}
            db.execute("UPDATE push_outbox SET state='cancelled' WHERE message_id IN (SELECT id FROM inbox_message WHERE id LIKE ?)", (f"reminder:{row['id']}:%",))
            updated = dict(db.execute("SELECT * FROM reminder WHERE id=?", (row["id"],)).fetchone())
            return {"ok": True, "reminder": updated}
    return {"ok": False, "error": "unknown tool"}


async def agent_reply(request: ChatRequest) -> dict[str, Any]:
    state: dict[str, Any] = {
        "messages": [{"role": "system", "content": system_prompt(request.timezone)}, *[turn.model_dump() for turn in request.messages]],
        "changes": [], "tool_results": [], "step": 0, "pending": [], "call_index": 0,
    }
    if request.client_message_id:
        with connect() as db:
            checkpoint = db.execute("SELECT state FROM chat_checkpoint WHERE id=?", (request.client_message_id,)).fetchone()
        if checkpoint:
            state = json.loads(checkpoint["state"])

    def checkpoint() -> None:
        if request.client_message_id:
            with connect() as db:
                db.execute("INSERT OR REPLACE INTO chat_checkpoint(id,state) VALUES(?,?)", (request.client_message_id, json.dumps(state, ensure_ascii=False)))

    while state["step"] < 4:
        if not state["pending"]:
            assistant = await deepseek(state["messages"], TOOLS)
            calls = assistant.get("tool_calls") or []
            if not calls:
                return {"content": assistant.get("content") or "", "memory_changes": state["changes"], "tool_results": state["tool_results"]}
            state["messages"].append(assistant)
            state["pending"] = calls
            state["call_index"] = 0
            # Persist the exact tool plan before its first side effect. Retry resumes this plan.
            checkpoint()
        calls = state["pending"]
        for index in range(state["call_index"], len(calls)):
            call = calls[index]
            name = call["function"]["name"]
            try:
                args = json.loads(call["function"]["arguments"])
                operation = hashlib.sha256(f"{request.client_message_id}:{state['step']}:{index}:{name}".encode()).hexdigest() if request.client_message_id else None
                result = await run_tool(name, args, operation)
                success = result.get("ok") in (True, "true")
                if not success:
                    summary = f"操作未完成 · {result.get('error', '请重试')}"
                elif name == "create_reminder":
                    summary = f"提醒已创建 · {result.get('title', '')} · {result.get('due_at', '')}"
                elif name == "save_memory":
                    summary = "已更新一条记忆"
                elif name == "list_reminders":
                    summary = "已读取待办提醒"
                else:
                    reminder = result.get("reminder", {})
                    labels = {"completed": "已完成", "cancelled": "已取消", "pending": "待提醒", "fired": "已到期"}
                    summary = f"提醒已更新 · {reminder.get('title', '')} · {labels.get(reminder.get('state'), '')}"
                state["tool_results"].append({"name": name, "summary": summary, "result": result})
                if name == "save_memory" and success:
                    state["changes"].append(result["memory"])
            except (KeyError, json.JSONDecodeError, ValueError, TypeError) as exc:
                result = {"ok": False, "error": str(exc)}
                state["tool_results"].append({"name": name, "summary": "操作未完成，请检查请求", "result": result})
            state["messages"].append({"role": "tool", "tool_call_id": call["id"], "content": json.dumps(result, ensure_ascii=False)})
            state["call_index"] = index + 1
            checkpoint()
        state["step"] += 1
        state["pending"] = []
        checkpoint()
    return {"content": "已完成的操作请查看结果，我暂时没能整理好回复。", "memory_changes": state["changes"], "tool_results": state["tool_results"]}


def put_inbox(title: str, body: str) -> str:
    message_id = secrets.token_urlsafe(12)
    with connect() as db:
        db.execute("INSERT INTO inbox_message(id,title,body,created_at) VALUES(?,?,?,?)", (message_id, title, body, utc_now()))
    return message_id


def enqueue_push(db: sqlite3.Connection, message_id: str) -> None:
    db.execute("""INSERT OR IGNORE INTO push_outbox(message_id,device_id,next_attempt_at)
        SELECT ?,id,? FROM device WHERE fcm_token IS NOT NULL""", (message_id, utc_now()))


def notify_devices(title: str, body: str, message_id: str) -> None:
    # Keep compatibility with other callers; delivery is persisted before any network I/O.
    with connect() as db:
        enqueue_push(db, message_id)


def fire_due_reminders() -> None:
    now = utc_now()
    with connect() as db:
        db.execute("BEGIN IMMEDIATE")
        due = db.execute("SELECT * FROM reminder WHERE state='pending' AND due_at <= ?", (now,)).fetchall()
        for row in due:
            message_id = f"reminder:{row['id']}:{row['due_at']}"
            db.execute("INSERT OR IGNORE INTO inbox_message(id,title,body,created_at) VALUES(?,?,?,?)",
                       (message_id, "Greta 提醒你", row["title"], now))
            enqueue_push(db, message_id)
            db.execute("UPDATE reminder SET state='fired',fired_at=? WHERE id=?", (now, row["id"]))


def deliver_pushes() -> None:
    service_file = os.getenv("FIREBASE_SERVICE_ACCOUNT_FILE", "")
    if not service_file:
        return
    try:
        import firebase_admin
        from firebase_admin import credentials, messaging
        if not firebase_admin._apps:
            firebase_admin.initialize_app(credentials.Certificate(service_file))
        with connect() as db:
            deliveries = db.execute("""SELECT p.*,d.fcm_token,m.title,m.body FROM push_outbox p
                JOIN device d ON d.id=p.device_id JOIN inbox_message m ON m.id=p.message_id
                WHERE p.state='pending' AND p.next_attempt_at <= ? LIMIT 30""", (utc_now(),)).fetchall()
        for row in deliveries:
            try:
                messaging.send(messaging.Message(
                    notification=messaging.Notification(title=row["title"], body=row["body"]),
                    data={"message_id": row["message_id"], "body": row["body"]}, token=row["fcm_token"]))
                with connect() as db:
                    db.execute("UPDATE push_outbox SET state='sent' WHERE message_id=? AND device_id=?", (row["message_id"], row["device_id"]))
            except Exception:
                attempts = row["attempts"] + 1
                retry_at = (datetime.now(timezone.utc) + timedelta(seconds=min(3600, 15 * 2 ** min(attempts, 8)))).isoformat()
                with connect() as db:
                    db.execute("UPDATE push_outbox SET attempts=?,next_attempt_at=? WHERE message_id=? AND device_id=?", (attempts, retry_at, row["message_id"], row["device_id"]))
    except Exception:
        return


async def reminder_loop() -> None:
    while True:
        try:
            await asyncio.to_thread(fire_due_reminders)
            await asyncio.to_thread(deliver_pushes)
        except sqlite3.Error:
            # A transient database lock must not permanently kill the scheduler.
            pass
        await asyncio.sleep(15)


@asynccontextmanager
async def lifespan(_: FastAPI):
    setup_db()
    with connect() as db:
        db.execute(
            "UPDATE print_job SET status='failed', error='服务重启，任务未完成', completed_at=? "
            "WHERE status IN ('pending', 'printing')",
            (utc_now(),),
        )
    task = asyncio.create_task(reminder_loop())
    yield
    task.cancel()


app = FastAPI(title="Greta private service", lifespan=lifespan)
chat_lock = asyncio.Lock()


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/chat", dependencies=[Depends(require_device)])
async def chat(request: ChatRequest) -> dict[str, Any]:
    if not request.client_message_id:
        return await agent_reply(request)
    # A stable request key survives retries; never replay a successfully completed turn.
    async with chat_lock:
        with connect() as db:
            cached = db.execute("SELECT response FROM chat_response WHERE id=?", (request.client_message_id,)).fetchone()
        if cached:
            return json.loads(cached["response"])
        response = await agent_reply(request)
        with connect() as db:
            db.execute("INSERT INTO chat_response(id,response,created_at) VALUES(?,?,?)", (request.client_message_id, json.dumps(response, ensure_ascii=False), utc_now()))
            db.execute("DELETE FROM chat_checkpoint WHERE id=?", (request.client_message_id,))
        return response


class HomeRequest(BaseModel):
    messages: list[Turn] = Field(default_factory=list, max_length=12)
    timezone: str = Field(default="Asia/Shanghai", max_length=80)


@app.post("/v1/home", dependencies=[Depends(require_device)])
async def home_opening(request: HomeRequest) -> dict[str, str]:
    context = system_prompt(request.timezone)
    prompt = """Choose whether to start a short conversation on the pet home screen.
Return JSON only: {"content":"one short Chinese sentence"} or {"content":""} to stay quiet.
Use only a concrete topic in the supplied conversation or explicit saved memories. Do not invent unfinished tasks, deadlines, emotions, or user activity. Do not repeat your last reply or make promises to execute tools. If there is no useful grounded topic, return empty content. No tools are available in this evaluation."""
    try:
        reply = await deepseek([{"role": "system", "content": context + "\n" + prompt}, *[m.model_dump() for m in request.messages]])
        raw = (reply.get("content") or "").strip()
        if raw.startswith("```"):
            raw = raw.split("\n", 1)[-1].rsplit("```", 1)[0]
        content = json.loads(raw).get("content", "")
        return {"content": content.strip()[:120] if isinstance(content, str) else ""}
    except (HTTPException, ValueError, TypeError, AttributeError):
        return {"content": ""}


@app.post("/v1/devices")
async def register_device(request: DeviceRequest, device: dict[str, Any] = Depends(require_device)) -> dict[str, bool]:
    with connect() as db:
        db.execute("UPDATE device SET fcm_token=?,updated_at=? WHERE id=?", (request.fcm_token, utc_now(), device["id"]))
    return {"ok": True}


@app.post("/v1/pairings/{pairing_id}/claim")
async def claim_device(pairing_id: str, claim: PairingClaim) -> dict[str, str]:
    return claim_pairing(pairing_id, claim)


@app.get("/v1/memories", dependencies=[Depends(require_device)])
async def list_memories() -> list[dict[str, Any]]:
    with connect() as db:
        return [dict(row) for row in db.execute("SELECT * FROM memory ORDER BY updated_at DESC").fetchall()]


@app.put("/v1/memories/{memory_id}", dependencies=[Depends(require_device)])
async def sync_memory(memory_id: str, memory: MemoryRecord) -> dict[str, bool]:
    if memory.id != memory_id:
        raise HTTPException(400, "Memory id does not match path")
    upsert_memory({**memory.model_dump(), "deleted": int(memory.deleted)})
    return {"ok": True}


@app.post("/v1/reminders", dependencies=[Depends(require_device)])
async def add_reminder(reminder: ReminderRequest) -> dict[str, str]:
    result = create_reminder(reminder.title, reminder.due_at)
    if result.get("ok") != "true":
        raise HTTPException(400, result["error"])
    return result


class ReminderAction(BaseModel):
    action: Literal["complete", "snooze"]


@app.get("/v1/reminders", dependencies=[Depends(require_device)])
async def list_reminders() -> list[dict[str, Any]]:
    with connect() as db:
        return [dict(row) for row in db.execute("SELECT * FROM reminder WHERE state IN ('pending','fired') ORDER BY due_at LIMIT 100").fetchall()]


@app.post("/v1/reminders/{reminder_id}/action", dependencies=[Depends(require_device)])
async def reminder_action(reminder_id: str, action: ReminderAction) -> dict[str, Any]:
    with connect() as db:
        row = db.execute("SELECT * FROM reminder WHERE id=?", (reminder_id,)).fetchone()
        if not row:
            raise HTTPException(404, "Reminder not found")
        if action.action == "complete":
            db.execute("UPDATE reminder SET state='completed' WHERE id=?", (reminder_id,))
        elif row["state"] != "completed":
            due = (datetime.now(timezone.utc) + timedelta(minutes=15)).isoformat()
            db.execute("UPDATE reminder SET state='pending',due_at=?,fired_at=NULL WHERE id=?", (due, reminder_id))
        # Do not deliver old retries after the user has acted on this reminder.
        db.execute("UPDATE push_outbox SET state='cancelled' WHERE message_id IN (SELECT id FROM inbox_message WHERE id LIKE ?)", (f"reminder:{reminder_id}:%",))
    return {"ok": True}


@app.get("/v1/inbox", dependencies=[Depends(require_device)])
async def inbox(after_sequence: int | None = None) -> list[dict[str, Any]]:
    with connect() as db:
        if after_sequence is not None:
            return [dict(row) for row in db.execute("SELECT rowid AS sequence,* FROM inbox_message WHERE rowid>? ORDER BY rowid LIMIT 100", (max(0, after_sequence),)).fetchall()]
        return [dict(row) for row in db.execute("SELECT rowid AS sequence,* FROM inbox_message ORDER BY created_at DESC LIMIT 100").fetchall()]


# ---------------------------------------------------------------------------
# Printer routes
# ---------------------------------------------------------------------------

from . import printer as _printer  # noqa: E402


async def _check_print_route(printer: dict) -> tuple[bool, str]:
    if _printer.cups_printer_name():
        return await _printer.check_cups_printer()
    return await _printer.check_online(printer["host"], printer["port"], printer.get("protocol", "ipp"))


@app.get("/v1/printers", dependencies=[Depends(require_device)])
async def get_printers() -> list[dict[str, Any]]:
    printers = _printer.list_printers()
    async def enrich(p: dict) -> dict:
        online, state = await _check_print_route(p)
        return {**p, "online": online, "state": state}
    return list(await asyncio.gather(*(enrich(p) for p in printers)))


@app.get("/v1/print-jobs", dependencies=[Depends(require_device)])
async def get_print_jobs() -> list[dict[str, Any]]:
    return _printer.list_print_jobs(limit=50)


@app.get("/v1/print-jobs/{job_id}", dependencies=[Depends(require_device)])
async def get_print_job(job_id: str) -> dict[str, Any]:
    job = _printer.get_print_job(job_id)
    if not job:
        raise HTTPException(404, "Print job not found")
    return job


@app.post("/v1/printers/discover", dependencies=[Depends(require_device)])
async def discover() -> dict[str, Any]:
    found = await _printer.discover_printers()
    saved = []
    for p in found:
        pid = _printer.save_printer(p)
        saved.append({"id": pid, **p})
    return {"ok": True, "printers": saved}


@app.get("/v1/printers/{printer_id}/status", dependencies=[Depends(require_device)])
async def printer_status(printer_id: str) -> dict[str, Any]:
    p = _printer.get_printer(printer_id)
    if not p:
        raise HTTPException(404, "Printer not found")
    online, state = await _check_print_route(p)
    if online:
        _printer.touch_printer(printer_id)
    return {"id": printer_id, "online": online, "state": state}


@app.delete("/v1/printers/{printer_id}", dependencies=[Depends(require_device)])
async def remove_printer(printer_id: str) -> dict[str, bool]:
    if not _printer.delete_printer(printer_id):
        raise HTTPException(404, "Printer not found")
    return {"ok": True}


@app.post("/v1/printers/{printer_id}/print", dependencies=[Depends(require_device)])
async def print_document(
    printer_id: str,
    background_tasks: BackgroundTasks,
    file: UploadFile | None = File(None),
    text: str | None = Form(None),
    copies: int = Form(1),
    paper_size: Literal["a4", "a5", "letter"] = Form("a4"),
    color_mode: Literal["monochrome", "color"] = Form("monochrome"),
) -> dict[str, Any]:
    p = _printer.get_printer(printer_id)
    if not p:
        raise HTTPException(404, "Printer not found")
    if not file and not text:
        raise HTTPException(400, "Provide a file or text")
    copies = max(1, min(copies, 99))

    online, _ = await _check_print_route(p)
    if not online:
        raise HTTPException(503, "Printer is offline")

    job_id = secrets.token_urlsafe(12)
    job_name = f"文字打印_{job_id[:6]}" if text else "document"
    file_type = "text"
    file_bytes: bytes | None = None
    suffix: str | None = None
    if file:
        raw_name = file.filename or "document"
        suffix = Path(raw_name).suffix.lower()
        if suffix not in _printer.ALLOWED_SUFFIXES:
            raise HTTPException(400, f"Unsupported file type: {suffix}")
        job_name = Path(raw_name).stem
        file_type = suffix.lstrip(".")
        file_bytes = await file.read()

    _printer.create_print_job(job_id, printer_id, job_name, file_type, copies)
    background_tasks.add_task(
        _printer.process_print_job, job_id, p,
        text=text, file_bytes=file_bytes, suffix=suffix, job_name=job_name,
        copies=copies, paper_size=paper_size, color_mode=color_mode,
    )
    return {"ok": True, "job_id": job_id, "status": "pending"}
