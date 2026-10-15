-- What a wake asked for: sound key ("preset:chime", "custom:12") and image
-- ("custom:7"); NULL = none / the receiver's own default. History only.
ALTER TABLE triggers ADD COLUMN sound TEXT;
ALTER TABLE triggers ADD COLUMN image TEXT;

-- Recordings, audio files and images users upload to send with their
-- wake-ups. Small (a few MB, a handful per user), so they live in the
-- database itself and get backed up with it.
CREATE TABLE IF NOT EXISTS custom_media (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id INTEGER NOT NULL REFERENCES users(id),
    kind TEXT NOT NULL, -- 'voice' | 'file' (sounds) or 'image'
    name TEXT NOT NULL,
    mime TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    data BLOB NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_custom_media_owner ON custom_media(owner_user_id);
