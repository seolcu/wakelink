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

func (s *FirebaseSender) SendWakeTrigger(ctx context.Context, token string, triggerID int64, fromDisplayName string) (string, error) {
	msg := &messaging.Message{
		Token: token,
		// Data-only: no "notification" payload. The receiver app must be
		// the one deciding how to alert (STREAM_ALARM + full-screen intent),
		// not the OS's default notification tray display, or the whole
		// point of bypassing silent/DND is defeated.
		Data: map[string]string{
			"type":              "wake_alarm",
			"trigger_id":        strconv.FormatInt(triggerID, 10),
			"from_display_name": fromDisplayName,
		},
		Android: &messaging.AndroidConfig{
			Priority: "high",
		},
	}
	return s.client.Send(ctx, msg)
}
