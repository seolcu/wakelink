package ratelimit

import (
	"testing"
	"time"
)

func TestWindow(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	w := NewWindow(2, time.Hour)
	w.now = func() time.Time { return now }

	if !w.Allow("a") || !w.Allow("a") {
		t.Fatal("first two should pass")
	}
	if w.Allow("a") {
		t.Fatal("third within the hour should be refused")
	}
	if !w.Allow("b") {
		t.Fatal("keys are independent")
	}
	now = now.Add(61 * time.Minute)
	if !w.Allow("a") {
		t.Fatal("old events should age out of the window")
	}

	var disabled *Window
	if !disabled.Allow("x") || !NewWindow(0, time.Hour).Allow("x") {
		t.Fatal("nil or zero-max window must allow everything")
	}
}
