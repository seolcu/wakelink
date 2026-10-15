-- The receiver's "receiving wake-ups" switch, mirrored from the app so
-- senders can see it and the server can refuse wakes up front instead of
-- letting them ring nowhere.
ALTER TABLE users ADD COLUMN accepting_wakes INTEGER NOT NULL DEFAULT 1;
