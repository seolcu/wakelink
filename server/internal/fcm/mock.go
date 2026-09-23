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

func (m *MockSender) SendWakeTrigger(ctx context.Context, token string, triggerID int64, fromDisplayName string) (string, error) {
	id := make([]byte, 8)
	if _, err := rand.Read(id); err != nil {
		return "", err
	}
	msgID := "mock-" + hex.EncodeToString(id)
	log.Printf("[fcm mock] trigger_id=%d token=%s from=%q message_id=%s", triggerID, token, fromDisplayName, msgID)
	return msgID, nil
}
