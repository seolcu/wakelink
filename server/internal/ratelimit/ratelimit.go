// Package ratelimit protects a pairing from being used to spam-ring a
// device -- important because a leaked/compromised sender API key
// otherwise has no other obstacle to ringing someone's phone repeatedly.
package ratelimit

import (
	"context"
	"errors"
	"time"

	"wakelink/internal/db"
)

var (
	ErrCooldown = errors.New("cooldown active")
	ErrDailyCap = errors.New("daily cap reached")
)

type Limiter struct {
	Cooldown time.Duration
	DailyCap int
}

// Check returns an error if fromUserID may not send another trigger to
// this pairing right now. It re-derives state from the triggers table on
// every call rather than keeping in-memory counters, so limits survive
// process restarts for free at this project's scale.
func (l *Limiter) Check(ctx context.Context, database *db.DB, pairingID, fromUserID int64) error {
	last, err := database.LastTriggerAt(ctx, pairingID, fromUserID)
	if err != nil {
		return err
	}
	if last > 0 && time.Now().Unix()-last < int64(l.Cooldown.Seconds()) {
		return ErrCooldown
	}

	since := time.Now().Add(-24 * time.Hour).Unix()
	count, err := database.CountTriggersSince(ctx, pairingID, fromUserID, since)
	if err != nil {
		return err
	}
	if count >= l.DailyCap {
		return ErrDailyCap
	}
	return nil
}
