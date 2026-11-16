-- Kosher-Til management database (Cloudflare D1 / SQLite).

CREATE TABLE IF NOT EXISTS managers (
  username      TEXT PRIMARY KEY,
  salt          TEXT NOT NULL,
  hash          TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token         TEXT PRIMARY KEY,
  username      TEXT NOT NULL,
  expires_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id              TEXT PRIMARY KEY,
  label           TEXT DEFAULT '',
  enabled         INTEGER NOT NULL DEFAULT 1,   -- 1 = filtering on
  unlock_until    INTEGER NOT NULL DEFAULT 0,   -- epoch ms; > now = temporarily released
  manager_contact TEXT DEFAULT '',
  policy_version  INTEGER NOT NULL DEFAULT 1,
  created_at      INTEGER NOT NULL,
  last_seen       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS unlock_requests (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id   TEXT NOT NULL,
  reason      TEXT DEFAULT '',
  ts          INTEGER NOT NULL,
  status      TEXT NOT NULL DEFAULT 'open'      -- open | resolved
);

CREATE INDEX IF NOT EXISTS idx_requests_device ON unlock_requests(device_id);
CREATE INDEX IF NOT EXISTS idx_requests_status ON unlock_requests(status);
CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);
