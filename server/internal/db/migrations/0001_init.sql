CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    display_name TEXT NOT NULL,
    fcm_token TEXT,
    api_key_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pairing_invites (
    code TEXT PRIMARY KEY,
    inviter_user_id INTEGER NOT NULL REFERENCES users(id),
    expires_at INTEGER NOT NULL,
    used INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

-- user_a_id is always the smaller of the two user ids (canonical order),
-- so UNIQUE(user_a_id, user_b_id) rejects a duplicate pairing regardless of
-- which side initiated it. Permission direction is still tracked per side
-- (a_can_wake_b / b_can_wake_a), independent of who is "a" or "b".
CREATE TABLE IF NOT EXISTS pairings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_a_id INTEGER NOT NULL REFERENCES users(id),
    user_b_id INTEGER NOT NULL REFERENCES users(id),
    a_can_wake_b INTEGER NOT NULL DEFAULT 1,
    b_can_wake_a INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    UNIQUE(user_a_id, user_b_id)
);

CREATE TABLE IF NOT EXISTS triggers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pairing_id INTEGER NOT NULL REFERENCES pairings(id),
    from_user_id INTEGER NOT NULL REFERENCES users(id),
    to_user_id INTEGER NOT NULL REFERENCES users(id),
    created_at INTEGER NOT NULL,
    fcm_message_id TEXT,
    send_status TEXT NOT NULL,
    send_error TEXT,
    ack_received_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_pairings_user_a ON pairings(user_a_id);
CREATE INDEX IF NOT EXISTS idx_pairings_user_b ON pairings(user_b_id);
CREATE INDEX IF NOT EXISTS idx_triggers_pairing_created ON triggers(pairing_id, created_at);
CREATE INDEX IF NOT EXISTS idx_invites_expires ON pairing_invites(expires_at);
