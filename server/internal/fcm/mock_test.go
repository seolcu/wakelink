package fcm

import (
	"context"
	"strings"
	"testing"
)

// Real FirebaseSender behavior is intentionally not covered here -- it
// needs a live Firebase project and service-account credentials, which
// aren't available in CI or local test runs. MockSender is what the api
// package's tests actually depend on, so that's what gets covered.
func TestMockSenderReturnsMessageID(t *testing.T) {
	sender := NewMockSender()
	id, err := sender.SendWakeTrigger(context.Background(), "some-token", 1, "Alice")
	if err != nil {
		t.Fatalf("send: %v", err)
	}
	if id == "" {
		t.Fatalf("expected a non-empty message id")
	}
	if !strings.HasPrefix(id, "mock-") {
		t.Fatalf("expected message id to start with 'mock-', got %q", id)
	}
}
