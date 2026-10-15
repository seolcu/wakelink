-- Each device's public end-to-end encryption key (base64 Tink public
-- keyset), shared with that user's contacts so they can seal wake details
-- and media keys that only this device can open. NULL for apps too old to
-- have one.
ALTER TABLE users ADD COLUMN public_key TEXT;
