package ratelimit

import (
	"sync"
	"time"
)

// Window allows at most Max events per key in any sliding Period, kept in
// memory (fine for one process; a restart just forgives everyone). Used to
// throttle unauthenticated endpoints per client IP, e.g. /v1/register.
// A zero Max disables it.
type Window struct {
	Max    int
	Period time.Duration

	mu     sync.Mutex
	events map[string][]time.Time
	now    func() time.Time
}

func NewWindow(max int, period time.Duration) *Window {
	return &Window{Max: max, Period: period, events: map[string][]time.Time{}, now: time.Now}
}

// Allow records an event for key and reports whether it was within the limit.
func (w *Window) Allow(key string) bool {
	if w == nil || w.Max <= 0 {
		return true
	}
	w.mu.Lock()
	defer w.mu.Unlock()

	now := w.now()
	cutoff := now.Add(-w.Period)
	kept := w.events[key][:0]
	for _, t := range w.events[key] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= w.Max {
		w.events[key] = kept
		return false
	}
	w.events[key] = append(kept, now)

	// Drop idle keys occasionally so the map can't grow without bound.
	if len(w.events) > 10_000 {
		for k, ts := range w.events {
			if len(ts) == 0 || !ts[len(ts)-1].After(cutoff) {
				delete(w.events, k)
			}
		}
	}
	return true
}
