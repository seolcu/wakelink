package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"wakelink/internal/db"
	"wakelink/internal/ratelimit"
)

// fakeSender records what it was asked to send instead of calling real FCM.
type fakeSender struct {
	sent []string
}

func (f *fakeSender) SendWakeTrigger(ctx context.Context, token string, triggerID int64, fromDisplayName string) (string, error) {
	f.sent = append(f.sent, token)
	return "fake-msg-id", nil
}

func newTestServer(t *testing.T) (*Server, *fakeSender) {
	t.Helper()
	database, err := db.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	if err := database.Migrate(); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	sender := &fakeSender{}
	return &Server{
		DB:  database,
		FCM: sender,
		RateLimit: ratelimit.Limiter{
			Cooldown: time.Second,
			DailyCap: 20,
		},
		InviteTTL: 10 * time.Minute,
	}, sender
}

func doJSON(t *testing.T, handler http.Handler, method, path, apiKey string, body interface{}) *httptest.ResponseRecorder {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatalf("encode body: %v", err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	if apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+apiKey)
	}
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}

// TestRegisterInvitePairTriggerAckFlow exercises the full happy path plus
// the permission-revocation guard: register two users, pair them via
// invite code, trigger a wake in one direction, ack it, then confirm that
// revoking that direction's permission actually blocks a further trigger.
func TestRegisterInvitePairTriggerAckFlow(t *testing.T) {
	s, sender := newTestServer(t)
	router := s.Router()

	rec := doJSON(t, router, http.MethodPost, "/v1/register", "", registerRequest{DisplayName: "Alice", FCMToken: "alice-token"})
	if rec.Code != http.StatusCreated {
		t.Fatalf("register alice: status %d body %s", rec.Code, rec.Body.String())
	}
	var alice registerResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &alice); err != nil {
		t.Fatalf("decode alice register: %v", err)
	}

	rec = doJSON(t, router, http.MethodPost, "/v1/register", "", registerRequest{DisplayName: "Bob", FCMToken: "bob-token"})
	if rec.Code != http.StatusCreated {
		t.Fatalf("register bob: status %d body %s", rec.Code, rec.Body.String())
	}
	var bob registerResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &bob); err != nil {
		t.Fatalf("decode bob register: %v", err)
	}

	rec = doJSON(t, router, http.MethodPost, "/v1/invites", alice.APIKey, nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("create invite: status %d body %s", rec.Code, rec.Body.String())
	}
	var invite struct {
		PairingCode string `json:"pairing_code"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &invite); err != nil {
		t.Fatalf("decode invite: %v", err)
	}

	rec = doJSON(t, router, http.MethodPost, "/v1/invites/accept", bob.APIKey, acceptInviteRequest{Code: invite.PairingCode})
	if rec.Code != http.StatusCreated {
		t.Fatalf("accept invite: status %d body %s", rec.Code, rec.Body.String())
	}
	var accepted struct {
		PairingID int64 `json:"pairing_id"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &accepted); err != nil {
		t.Fatalf("decode accept: %v", err)
	}

	triggerPath := "/v1/pairings/" + strconv.FormatInt(accepted.PairingID, 10) + "/trigger"

	// bob wakes alice
	rec = doJSON(t, router, http.MethodPost, triggerPath, bob.APIKey, nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("trigger: status %d body %s", rec.Code, rec.Body.String())
	}
	var triggered struct {
		TriggerID int64 `json:"trigger_id"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &triggered); err != nil {
		t.Fatalf("decode trigger: %v", err)
	}

	if len(sender.sent) != 1 || sender.sent[0] != "alice-token" {
		t.Fatalf("expected exactly one push to alice-token, got %+v", sender.sent)
	}

	// alice acks
	ackPath := "/v1/triggers/" + strconv.FormatInt(triggered.TriggerID, 10) + "/ack"
	rec = doJSON(t, router, http.MethodPost, ackPath, alice.APIKey, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("ack: status %d body %s", rec.Code, rec.Body.String())
	}

	// double-ack should fail (already acked)
	rec = doJSON(t, router, http.MethodPost, ackPath, alice.APIKey, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 on double-ack, got status %d body %s", rec.Code, rec.Body.String())
	}

	// bob revokes his own permission to wake alice
	allowed := false
	patchPath := "/v1/pairings/" + strconv.FormatInt(accepted.PairingID, 10)
	rec = doJSON(t, router, http.MethodPatch, patchPath, bob.APIKey, patchPairingRequest{ICanWakeThem: &allowed})
	if rec.Code != http.StatusOK {
		t.Fatalf("patch pairing: status %d body %s", rec.Code, rec.Body.String())
	}

	// bob's next trigger attempt must now be forbidden
	rec = doJSON(t, router, http.MethodPost, triggerPath, bob.APIKey, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected forbidden after revoking permission, got status %d body %s", rec.Code, rec.Body.String())
	}

	// alice's direction is untouched -- she can still wake bob
	rec = doJSON(t, router, http.MethodPost, triggerPath, alice.APIKey, nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("alice trigger after bob's revoke: status %d body %s", rec.Code, rec.Body.String())
	}
}

func TestRateLimitCooldown(t *testing.T) {
	s, _ := newTestServer(t)
	s.RateLimit.Cooldown = time.Hour // force the cooldown to definitely still be active on the 2nd call
	router := s.Router()

	rec := doJSON(t, router, http.MethodPost, "/v1/register", "", registerRequest{DisplayName: "Alice", FCMToken: "alice-token"})
	var alice registerResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &alice)

	rec = doJSON(t, router, http.MethodPost, "/v1/register", "", registerRequest{DisplayName: "Bob", FCMToken: "bob-token"})
	var bob registerResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &bob)

	rec = doJSON(t, router, http.MethodPost, "/v1/invites", alice.APIKey, nil)
	var invite struct {
		PairingCode string `json:"pairing_code"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &invite)

	rec = doJSON(t, router, http.MethodPost, "/v1/invites/accept", bob.APIKey, acceptInviteRequest{Code: invite.PairingCode})
	var accepted struct {
		PairingID int64 `json:"pairing_id"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &accepted)

	triggerPath := "/v1/pairings/" + strconv.FormatInt(accepted.PairingID, 10) + "/trigger"

	rec = doJSON(t, router, http.MethodPost, triggerPath, bob.APIKey, nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("first trigger: status %d body %s", rec.Code, rec.Body.String())
	}

	rec = doJSON(t, router, http.MethodPost, triggerPath, bob.APIKey, nil)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 during cooldown, got status %d body %s", rec.Code, rec.Body.String())
	}
}
