package fcm

import (
	"context"
	"fmt"
	"strconv"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

// FirebaseSender sends real pushes through the Firebase Admin SDK. The
// service account credentials it's constructed with determine which
// Firebase project's device tokens it can reach -- see docs/self-hosting.md
// for why this matters for anyone forking this project.
type FirebaseSender struct {
	client *messaging.Client
}

func NewFirebaseSender(ctx context.Context, serviceAccountPath string) (*FirebaseSender, error) {
	app, err := firebase.NewApp(ctx, nil, option.WithCredentialsFile(serviceAccountPath))
	if err != nil {
		return nil, fmt.Errorf("init firebase app: %w", err)
	}
	client, err := app.Messaging(ctx)
	if err != nil {
		return nil, fmt.Errorf("init messaging client: %w", err)
	}
	return &FirebaseSender{client: client}, nil
}

func (s *FirebaseSender) SendWakeTrigger(ctx context.Context, token string, wake WakeMessage) (string, error) {
	data := map[string]string{
		"type":       "wake_alarm",
		"trigger_id": strconv.FormatInt(wake.TriggerID, 10),
	}
	if wake.Sealed != "" {
		// Google relays this push, so keep everything identifying sealed.
		data["sealed"] = wake.Sealed
	} else {
		data["from_display_name"] = wake.FromDisplayName
	}
	if wake.Sound != "" {
		data["sound"] = wake.Sound
	}
	if wake.Image != "" {
		data["image"] = wake.Image
	}
	msg := &messaging.Message{
		Token: token,
		// Data-only: no "notification" payload. The receiver app must be
		// the one deciding how to alert (STREAM_ALARM + full-screen intent),
		// not the OS's default notification tray display, or the whole
		// point of bypassing silent/DND is defeated.
		Data: data,
		Android: &messaging.AndroidConfig{
			Priority: "high",
		},
	}
	id, err := s.client.Send(ctx, msg)
	if err != nil && (messaging.IsUnregistered(err) || messaging.IsSenderIDMismatch(err)) {
		return "", fmt.Errorf("%w: %v", ErrUnregistered, err)
	}
	return id, err
}
