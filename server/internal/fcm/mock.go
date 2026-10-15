package fcm

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log"
)

// MockSender logs the push instead of sending it, for local development
// (FCM_MOCK=true) without real Firebase credentials.
type MockSender struct{}

func NewMockSender() *MockSender { return &MockSender{} }

func (m *MockSender) SendWakeTrigger(ctx context.Context, token string, wake WakeMessage) (string, error) {
	id := make([]byte, 8)
	if _, err := rand.Read(id); err != nil {
		return "", err
	}
	msgID := "mock-" + hex.EncodeToString(id)
	log.Printf("[fcm mock] trigger_id=%d token=%s from=%q sound=%q image=%q message_id=%s", wake.TriggerID, token, wake.FromDisplayName, wake.Sound, wake.Image, msgID)
	return msgID, nil
}
