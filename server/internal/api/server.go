// Package api implements WakeLink's HTTP API: pairing (mutual-consent
// invite/accept), per-direction wake permissions, and triggering/
// acknowledging wake pushes over FCM.
package api

import (
	"time"

	"wakelink/internal/db"
	"wakelink/internal/fcm"
	"wakelink/internal/ratelimit"
)

type Server struct {
	DB        *db.DB
	FCM       fcm.Sender
	RateLimit ratelimit.Limiter
	InviteTTL time.Duration
}
