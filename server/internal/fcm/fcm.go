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

// WakeMessage is what a wake push tells the receiving device.
type WakeMessage struct {
	TriggerID       int64
	FromDisplayName string
	// Sound is the sender's choice ("preset:chime", "custom:12:<sha256>"),
	// or "" to let the receiver use its own default. The receiver's own
	// policy decides whether to honor it.
	Sound string
	// Image is "" or "custom:<id>:<sha256>", a picture to show on the
	// alarm screen (subject to the receiver's own setting).
	Image string
	// Sealed, when set, is an end-to-end encrypted payload for the
	// recipient device holding the sender's name and choices; the push then
	// carries nothing else readable (no display name, sound or image).
	Sealed string
}

type Sender interface {
	// SendWakeTrigger sends a high-priority, data-only push to token.
	// Returns the provider's message id on success.
	SendWakeTrigger(ctx context.Context, token string, msg WakeMessage) (messageID string, err error)
}
