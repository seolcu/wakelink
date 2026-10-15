-- Per-pairing inbound consent: whether each side currently accepts wakes
-- from the other. Unlike a_can_wake_b (controlled by a, the waker), these
-- are controlled by the person being woken: a_accepts_from_b by a,
-- b_accepts_from_a by b.
ALTER TABLE pairings ADD COLUMN a_accepts_from_b INTEGER NOT NULL DEFAULT 1;
ALTER TABLE pairings ADD COLUMN b_accepts_from_a INTEGER NOT NULL DEFAULT 1;
