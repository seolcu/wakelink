-- A temporary pause of the "receiving wake-ups" switch: when set, wakes
-- are accepted again from this moment on (unix seconds) even if the phone
-- never comes back online to switch itself on. NULL = no timer.
ALTER TABLE users ADD COLUMN accepting_resumes_at INTEGER;
