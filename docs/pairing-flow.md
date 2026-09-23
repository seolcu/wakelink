# Pairing flow

WakeLink pairs two accounts through a short-lived invite code, requiring
an explicit action from both sides -- this is the mechanism that makes
pairing mutual-consent rather than something one side can impose on the
other. See `server/internal/api/pairing.go` for the implementation and
`server/internal/db/migrations/0001_init.sql` for the schema.

```
   User A (inviter)                      User B (accepter)
   ------------------                    ------------------
   POST /v1/invites
     -> { pairing_code, expires_at }
   (shows code on screen / shares it
    via any channel of A's choosing)
                                          types in the code
                                          POST /v1/invites/accept
                                            { pairing_code }
                                            -> creates the pairing,
                                               both directions default
                                               to "can wake" = true
```

Notes:

- The invite code is single-use and expires after `INVITE_TTL_SECONDS`
  (default 10 minutes) -- see `server/config.example.env`.
- A cannot pair with themselves, and a duplicate pairing between the same
  two users is rejected (`pairings` has a `UNIQUE(user_a_id, user_b_id)`
  constraint, with ids stored in canonical order).
- After pairing, each direction's wake permission
  (`a_can_wake_b` / `b_can_wake_a`) can be independently toggled or
  revoked by the user who owns that direction (`PATCH /v1/pairings/{id}`),
  and either side can delete the pairing outright
  (`DELETE /v1/pairings/{id}`) at any time.
- Nothing here lets one side see the other's location, messages, contacts,
  or any data beyond: display name, whether they can currently wake you,
  and the timestamps of past triggers.
