-- When the person woken stopped the alarm themselves (not the 5-minute
-- auto-stop): the sender's "they're up" signal. NULL = not (yet) dismissed.
ALTER TABLE triggers ADD COLUMN dismissed_at INTEGER;
