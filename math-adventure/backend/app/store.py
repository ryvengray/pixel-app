import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = '''
CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO schema_version VALUES(1);
CREATE TABLE IF NOT EXISTS players(
 id TEXT PRIMARY KEY, nickname TEXT NOT NULL, mode TEXT NOT NULL UNIQUE,
 xp INTEGER NOT NULL DEFAULT 0 CHECK(xp>=0), coins INTEGER NOT NULL DEFAULT 0 CHECK(coins>=0));

CREATE TABLE IF NOT EXISTS questions(
 id TEXT PRIMARY KEY, question_key TEXT NOT NULL, version INTEGER NOT NULL,
 data TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'draft', active INTEGER NOT NULL DEFAULT 0,
 checks TEXT, reviewer TEXT, reviewed_at TEXT, review_comment TEXT, published_at TEXT,
 created_at TEXT NOT NULL, UNIQUE(question_key,version));
CREATE TABLE IF NOT EXISTS devices(
 id TEXT PRIMARY KEY, name TEXT NOT NULL, player_id TEXT NOT NULL REFERENCES players(id),
 token_hash TEXT NOT NULL UNIQUE, revoked INTEGER NOT NULL DEFAULT 0,
 last_seen_at TEXT, firmware_version TEXT);
CREATE TABLE IF NOT EXISTS tasks(
 id TEXT PRIMARY KEY, player_id TEXT NOT NULL REFERENCES players(id), day TEXT NOT NULL,
 question_id TEXT NOT NULL REFERENCES questions(id), ordinal INTEGER NOT NULL,
 state TEXT NOT NULL DEFAULT 'pending', hint_count INTEGER NOT NULL DEFAULT 0,
 created_at TEXT NOT NULL, completed_at TEXT,
 UNIQUE(player_id,day,ordinal), UNIQUE(player_id,day,question_id));
CREATE TABLE IF NOT EXISTS attempts(
 id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES tasks(id),
 answer INTEGER NOT NULL, correct INTEGER NOT NULL, hint_count INTEGER NOT NULL,
 duration_seconds INTEGER NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS rewards(
 id INTEGER PRIMARY KEY AUTOINCREMENT, player_id TEXT NOT NULL REFERENCES players(id),
 business_key TEXT NOT NULL, xp INTEGER NOT NULL, coins INTEGER NOT NULL, created_at TEXT NOT NULL,
 UNIQUE(player_id,business_key));
CREATE TABLE IF NOT EXISTS requests(
 device_id TEXT NOT NULL REFERENCES devices(id), request_id TEXT NOT NULL,
 payload TEXT NOT NULL, response TEXT NOT NULL, created_at TEXT NOT NULL,
 PRIMARY KEY(device_id,request_id));
CREATE TABLE IF NOT EXISTS audit(
 id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, subject TEXT NOT NULL,
 details TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS task_day ON tasks(player_id,day);
CREATE INDEX IF NOT EXISTS question_versions ON questions(question_key,version);
'''

def now():
    return datetime.now(timezone.utc).isoformat()

def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

class Store:
    def __init__(self, path):
        self.path = Path(path)

    @contextmanager
    def connect(self, write=False):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA foreign_keys=ON')
        try:
            if write:
                db.execute('BEGIN IMMEDIATE')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def setup(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            # Idempotent initial migration; future changes add versioned migrations.
            db.executescript(SCHEMA)
            columns = {r['name'] for r in db.execute('PRAGMA table_info(tasks)')}
            if 'archived' not in columns:
                db.execute('ALTER TABLE tasks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0')
            db.execute('INSERT OR IGNORE INTO schema_version VALUES(3)')

    def upgrade_owners(self):
        # Rebuild only the v1 players table to remove its single-family mode UNIQUE constraint.
        with self.connect() as db:
            columns={r['name'] for r in db.execute('PRAGMA table_info(players)')}
            if 'owner' not in columns:
                db.execute('PRAGMA foreign_keys=OFF')
                db.executescript('''BEGIN IMMEDIATE;
                CREATE TABLE players_v2(id TEXT PRIMARY KEY,nickname TEXT NOT NULL,mode TEXT NOT NULL,owner TEXT NOT NULL,
                  xp INTEGER NOT NULL DEFAULT 0 CHECK(xp>=0),coins INTEGER NOT NULL DEFAULT 0 CHECK(coins>=0),UNIQUE(owner,mode));
                INSERT INTO players_v2 SELECT id,nickname,mode,'administrator',xp,coins FROM players;
                DROP TABLE players;
                ALTER TABLE players_v2 RENAME TO players;
                COMMIT;''')
            for table in ('questions','audit'):
                columns={r['name'] for r in db.execute('PRAGMA table_info('+table+')')}
                if 'owner' not in columns:db.execute("ALTER TABLE "+table+" ADD COLUMN owner TEXT NOT NULL DEFAULT 'administrator'")
            db.execute('INSERT OR IGNORE INTO schema_version VALUES(2)')
            if db.execute('PRAGMA foreign_key_check').fetchone():raise RuntimeError('Ownership migration failed foreign-key validation')
