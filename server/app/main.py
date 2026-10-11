from __future__ import annotations

import asyncio
import hashlib
import json
import os
import secrets
import sqlite3
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Literal

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
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
DB_PATH = Path(os.getenv("DATABASE_PATH", str(ROOT / "data" / "gray.db"))).expanduser()
PUBLIC_BASE_URL = os.getenv("GRAY_PUBLIC_BASE_URL", "").rstrip("/")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def token_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
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
        """)
        columns = {row[1] for row in db.execute("PRAGMA table_info(device)").fetchall()}
        if "name" not in columns:
            db.execute("ALTER TABLE device ADD COLUMN name TEXT")
        if "token_hash" not in columns:
            db.execute("ALTER TABLE device ADD COLUMN token_hash TEXT")


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
    timezone: str = "Asia/Shanghai"


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
]


def system_prompt(user_timezone: str) -> str:
    memories = active_memories()
    memory_text = "\n".join(f"- [{m['category']}] {m['fact']}" for m in memories) or "(none yet)"
    return f"""You are Gray, a warm concise personal assistant. The user time zone is {user_timezone}.
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


def create_reminder(title: str, due_at: str) -> dict[str, str]:
    try:
        due = datetime.fromisoformat(due_at.replace("Z", "+00:00"))
    except ValueError as exc:
        return {"ok": "false", "error": "due_at is not a valid ISO-8601 timestamp"}
    reminder_id = secrets.token_urlsafe(12)
    with connect() as db:
        db.execute("INSERT INTO reminder(id,title,due_at,created_at) VALUES(?,?,?,?)", (reminder_id, title, due.astimezone(timezone.utc).isoformat(), utc_now()))
    return {"ok": "true", "id": reminder_id, "due_at": due.isoformat()}


async def run_tool(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    if name == "save_memory":
        record = {"id": secrets.token_urlsafe(12), "category": arguments["category"], "fact": arguments["fact"], "confidence": arguments["confidence"], "source": "assistant extraction", "revision": 1, "deleted": 0, "updated_at": utc_now()}
        upsert_memory(record)
        return {"ok": True, "memory": record}
    if name == "create_reminder":
        return create_reminder(arguments["title"], arguments["due_at"])
    return {"ok": False, "error": "unknown tool"}


async def agent_reply(request: ChatRequest) -> dict[str, Any]:
    messages: list[dict[str, Any]] = [{"role": "system", "content": system_prompt(request.timezone)}]
    messages.extend(turn.model_dump() for turn in request.messages)
    changes: list[dict[str, Any]] = []
    for _ in range(4):
        assistant = await deepseek(messages, TOOLS)
        calls = assistant.get("tool_calls") or []
        if not calls:
            return {"content": assistant.get("content") or "", "memory_changes": changes}
        messages.append(assistant)
        for call in calls:
            try:
                args = json.loads(call["function"]["arguments"])
                result = await run_tool(call["function"]["name"], args)
                if call["function"]["name"] == "save_memory" and result.get("ok"):
                    changes.append(result["memory"])
            except (KeyError, json.JSONDecodeError, ValueError) as exc:
                result = {"ok": False, "error": str(exc)}
            messages.append({"role": "tool", "tool_call_id": call["id"], "content": json.dumps(result, ensure_ascii=False)})
    return {"content": "我这次处理得有些久了，请再试一次。", "memory_changes": changes}


def put_inbox(title: str, body: str) -> str:
    message_id = secrets.token_urlsafe(12)
    with connect() as db:
        db.execute("INSERT INTO inbox_message(id,title,body,created_at) VALUES(?,?,?,?)", (message_id, title, body, utc_now()))
    return message_id


def notify_devices(title: str, body: str, message_id: str) -> None:
    service_file = os.getenv("FIREBASE_SERVICE_ACCOUNT_FILE", "")
    if not service_file:
        return
    try:
        import firebase_admin
        from firebase_admin import credentials, messaging
        if not firebase_admin._apps:
            firebase_admin.initialize_app(credentials.Certificate(service_file))
        with connect() as db:
            tokens = [row["fcm_token"] for row in db.execute("SELECT fcm_token FROM device WHERE fcm_token IS NOT NULL").fetchall()]
        for token in tokens:
            messaging.send(messaging.Message(notification=messaging.Notification(title=title, body=body), data={"message_id": message_id}, token=token))
    except Exception:
        # Inbox persistence is the reliable delivery path; a push failure must not rerun a reminder.
        return


async def reminder_loop() -> None:
    while True:
        now = utc_now()
        with connect() as db:
            due = db.execute("SELECT * FROM reminder WHERE state='pending' AND due_at <= ?", (now,)).fetchall()
            for row in due:
                db.execute("UPDATE reminder SET state='fired', fired_at=? WHERE id=?", (now, row["id"]))
        for reminder in due:
            body = reminder["title"]
            message_id = put_inbox("Gray 提醒你", body)
            notify_devices("Gray 提醒你", body, message_id)
        await asyncio.sleep(15)


@asynccontextmanager
async def lifespan(_: FastAPI):
    setup_db()
    task = asyncio.create_task(reminder_loop())
    yield
    task.cancel()


app = FastAPI(title="Gray private service", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/chat", dependencies=[Depends(require_device)])
async def chat(request: ChatRequest) -> dict[str, Any]:
    return await agent_reply(request)


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


@app.get("/v1/inbox", dependencies=[Depends(require_device)])
async def inbox() -> list[dict[str, Any]]:
    with connect() as db:
        return [dict(row) for row in db.execute("SELECT * FROM inbox_message ORDER BY created_at DESC LIMIT 100").fetchall()]
