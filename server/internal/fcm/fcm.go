// Package fcm delivers wake triggers to devices via Firebase Cloud
// Messaging. It is defined as a small interface so the api package's tests
// never need real Firebase credentials or network access.
package fcm

import (
	"context"
	"errors"
)

// ErrUnregistered is returned (wrapped) by a Sender when the provider says
// the device token is no longer valid -- the app was uninstalled, or the
// token rotated and the new one never reached us. Callers should forget
// the token rather than keep retrying it.
var ErrUnregistered = errors.New("fcm: device token is no longer registered")

type Sender interface {
	// SendWakeTrigger sends a high-priority, data-only push to token,
	// notifying the recipient device it has been triggered by
	// fromDisplayName. Returns the provider's message id on success.
	SendWakeTrigger(ctx context.Context, token string, triggerID int64, fromDisplayName string) (messageID string, err error)
}
