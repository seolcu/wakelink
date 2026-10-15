// Package api implements WakeLink's HTTP API: pairing (mutual-consent
// invite/accept), per-direction wake permissions, and triggering/
// acknowledging wake pushes over FCM.
package api

import (
	"time"

	"wakelink/internal/db"
	"wakelink/internal/fcm"
	"wakelink/internal/metrics"
	"wakelink/internal/ratelimit"
	"wakelink/internal/web"
)

type Server struct {
	DB        *db.DB
	FCM       fcm.Sender
	RateLimit ratelimit.Limiter
	InviteTTL time.Duration
	Metrics   *metrics.Counters
	Version   string
	StartedAt time.Time
	// DownloadURL is where GET /download redirects (the latest APK); empty
	// disables the route.
	DownloadURL string
	// RegisterLimit throttles account creation per client IP; nil = unlimited.
	RegisterLimit *ratelimit.Window
	// Site serves the public web pages (landing, invite links,
	// assetlinks.json); nil leaves them out.
	Site *web.Site
}
