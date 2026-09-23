// Package fcm delivers wake triggers to devices via Firebase Cloud
// Messaging. It is defined as a small interface so the api package's tests
// never need real Firebase credentials or network access.
package fcm

import "context"

type Sender interface {
	// SendWakeTrigger sends a high-priority, data-only push to token,
	// notifying the recipient device it has been triggered by
	// fromDisplayName. Returns the provider's message id on success.
	SendWakeTrigger(ctx context.Context, token string, triggerID int64, fromDisplayName string) (messageID string, err error)
}
