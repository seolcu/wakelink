package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"wakelink/internal/db"
	"wakelink/internal/fcm"
	"wakelink/internal/ratelimit"
)

// fakeSender records what it was asked to send instead of calling real FCM.
// Setting err makes every send fail with it, to exercise error paths.
type fakeSender struct {
	sent []string
	err  error
}

func (f *fakeSender) SendWakeTrigger(ctx context.Context, token string, triggerID int64, fromDisplayName string) (string, error) {
	f.sent = append(f.sent, token)
	if f.err != nil {
		return "", f.err
	}
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

func register(t *testing.T, router http.Handler, name, token string) registerResponse {
	t.Helper()
	rec := doJSON(t, router, http.MethodPost, "/v1/register", "", registerRequest{DisplayName: name, FCMToken: token})
	if rec.Code != http.StatusCreated {
		t.Fatalf("register %s: status %d body %s", name, rec.Code, rec.Body.String())
	}
	var out registerResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode register %s: %v", name, err)
	}
	return out
}

func createInvite(t *testing.T, router http.Handler, apiKey string) string {
	t.Helper()
	rec := doJSON(t, router, http.MethodPost, "/v1/invites", apiKey, nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("create invite: status %d body %s", rec.Code, rec.Body.String())
	}
	var invite struct {
		PairingCode string `json:"pairing_code"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &invite); err != nil {
		t.Fatalf("decode invite: %v", err)
	}
	return invite.PairingCode
}

// pairUp registers Alice and Bob and pairs them, returning both
// identities and the pairing id.
func pairUp(t *testing.T, router http.Handler) (alice, bob registerResponse, pairingID int64) {
	t.Helper()
	alice = register(t, router, "Alice", "alice-token")
	bob = register(t, router, "Bob", "bob-token")
	code := createInvite(t, router, alice.APIKey)
	rec := doJSON(t, router, http.MethodPost, "/v1/invites/accept", bob.APIKey, acceptInviteRequest{Code: code})
	if rec.Code != http.StatusCreated {
		t.Fatalf("accept invite: status %d body %s", rec.Code, rec.Body.String())
	}
	var accepted struct {
		PairingID int64 `json:"pairing_id"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &accepted); err != nil {
		t.Fatalf("decode accept: %v", err)
	}
	return alice, bob, accepted.PairingID
}

func pairingPath(id int64) string { return "/v1/pairings/" + strconv.FormatInt(id, 10) }

func listPairings(t *testing.T, router http.Handler, apiKey string) []pairingView {
	t.Helper()
	rec := doJSON(t, router, http.MethodGet, "/v1/pairings", apiKey, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("list pairings: status %d body %s", rec.Code, rec.Body.String())
	}
	var out []pairingView
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode pairings: %v", err)
	}
	return out
}

// Regression: triggers.pairing_id is a non-cascading FK with foreign_keys
// on, so deleting a pairing with any trigger history used to 500.
func TestDeletePairingWithTriggerHistory(t *testing.T) {
	s, _ := newTestServer(t)
	router := s.Router()
	alice, bob, pairingID := pairUp(t, router)

	rec := doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("trigger: status %d body %s", rec.Code, rec.Body.String())
	}

	rec = doJSON(t, router, http.MethodDelete, pairingPath(pairingID), alice.APIKey, nil)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("delete pairing: status %d body %s", rec.Code, rec.Body.String())
	}
	if got := listPairings(t, router, bob.APIKey); len(got) != 0 {
		t.Fatalf("expected bob to have no pairings after alice unpaired, got %+v", got)
	}
	rec = doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("trigger after delete: expected 404, got %d", rec.Code)
	}
}

func TestNonMemberCannotTouchPairing(t *testing.T) {
	s, _ := newTestServer(t)
	router := s.Router()
	_, _, pairingID := pairUp(t, router)
	mallory := register(t, router, "Mallory", "mallory-token")

	rec := doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", mallory.APIKey, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("non-member trigger: expected 403, got %d", rec.Code)
	}
	rec = doJSON(t, router, http.MethodGet, "/v1/triggers?pairing_id="+strconv.FormatInt(pairingID, 10), mallory.APIKey, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("non-member list triggers: expected 403, got %d", rec.Code)
	}
	allowed := true
	rec = doJSON(t, router, http.MethodPatch, pairingPath(pairingID), mallory.APIKey, patchPairingRequest{ICanWakeThem: &allowed})
	if rec.Code != http.StatusNotFound {
		t.Fatalf("non-member patch: expected 404, got %d", rec.Code)
	}
	rec = doJSON(t, router, http.MethodDelete, pairingPath(pairingID), mallory.APIKey, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("non-member delete: expected 404, got %d", rec.Code)
	}
}

func TestInviteAcceptErrors(t *testing.T) {
	s, _ := newTestServer(t)
	router := s.Router()
	alice := register(t, router, "Alice", "alice-token")
	bob := register(t, router, "Bob", "bob-token")
	carol := register(t, router, "Carol", "carol-token")

	accept := func(apiKey, code string) int {
		return doJSON(t, router, http.MethodPost, "/v1/invites/accept", apiKey, acceptInviteRequest{Code: code}).Code
	}

	code := createInvite(t, router, alice.APIKey)
	if got := accept(alice.APIKey, code); got != http.StatusBadRequest {
		t.Fatalf("self-accept: expected 400, got %d", got)
	}
	if got := accept(bob.APIKey, "NOSUCHCD"); got != http.StatusNotFound {
		t.Fatalf("unknown code: expected 404, got %d", got)
	}
	if got := accept(bob.APIKey, code); got != http.StatusCreated {
		t.Fatalf("first accept: expected 201, got %d", got)
	}
	if got := accept(carol.APIKey, code); got != http.StatusConflict {
		t.Fatalf("reuse code: expected 409, got %d", got)
	}
	if got := accept(alice.APIKey, createInvite(t, router, bob.APIKey)); got != http.StatusConflict {
		t.Fatalf("duplicate pairing: expected 409, got %d", got)
	}

	s.InviteTTL = -time.Minute
	if got := accept(carol.APIKey, createInvite(t, router, alice.APIKey)); got != http.StatusGone {
		t.Fatalf("expired code: expected 410, got %d", got)
	}
}

func TestPurgeExpiredInvites(t *testing.T) {
	s, _ := newTestServer(t)
	router := s.Router()
	alice := register(t, router, "Alice", "alice-token")
	live := createInvite(t, router, alice.APIKey)
	s.InviteTTL = -time.Minute
	dead := createInvite(t, router, alice.APIKey)

	n, err := s.DB.DeleteExpiredInvites(context.Background(), time.Now())
	if err != nil || n != 1 {
		t.Fatalf("purge: n=%d err=%v, want 1 deleted", n, err)
	}
	if _, err := s.DB.GetInvite(context.Background(), dead); err != db.ErrNotFound {
		t.Fatalf("expired invite still present: %v", err)
	}
	if _, err := s.DB.GetInvite(context.Background(), live); err != nil {
		t.Fatalf("live invite was purged: %v", err)
	}
}

func TestUnregisteredTokenIsCleared(t *testing.T) {
	s, sender := newTestServer(t)
	router := s.Router()
	alice, bob, pairingID := pairUp(t, router)

	sender.err = fmt.Errorf("%w: token gone", fcm.ErrUnregistered)
	rec := doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, nil)
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("trigger with dead token: expected 422, got %d body %s", rec.Code, rec.Body.String())
	}
	u, err := s.DB.GetUserByID(context.Background(), alice.UserID)
	if err != nil {
		t.Fatalf("get alice: %v", err)
	}
	if u.FCMToken.Valid {
		t.Fatalf("expected alice's dead token to be cleared, still %q", u.FCMToken.String)
	}

	// Further attempts fail fast without calling FCM at all.
	sender.err = nil
	sent := len(sender.sent)
	s.RateLimit.Cooldown = 0
	rec = doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, nil)
	if rec.Code != http.StatusUnprocessableEntity || len(sender.sent) != sent {
		t.Fatalf("expected fast 422 without a push, got %d (sends %d -> %d)", rec.Code, sent, len(sender.sent))
	}
}

func TestListTriggersReflectsAck(t *testing.T) {
	s, _ := newTestServer(t)
	router := s.Router()
	alice, bob, pairingID := pairUp(t, router)

	rec := doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, nil)
	var triggered struct {
		TriggerID int64 `json:"trigger_id"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &triggered)

	list := func() []triggerView {
		rec := doJSON(t, router, http.MethodGet, "/v1/triggers?pairing_id="+strconv.FormatInt(pairingID, 10), bob.APIKey, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("list triggers: status %d body %s", rec.Code, rec.Body.String())
		}
		var out []triggerView
		_ = json.Unmarshal(rec.Body.Bytes(), &out)
		return out
	}

	got := list()
	if len(got) != 1 || got[0].ID != triggered.TriggerID || got[0].AckReceivedAt != nil || got[0].SendStatus != "sent" {
		t.Fatalf("before ack: unexpected triggers %+v", got)
	}

	// Only the recipient may ack.
	ackPath := "/v1/triggers/" + strconv.FormatInt(triggered.TriggerID, 10) + "/ack"
	if rec := doJSON(t, router, http.MethodPost, ackPath, bob.APIKey, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("sender ack: expected 404, got %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodPost, ackPath, alice.APIKey, nil); rec.Code != http.StatusOK {
		t.Fatalf("recipient ack: expected 200, got %d", rec.Code)
	}
	if got := list(); got[0].AckReceivedAt == nil {
		t.Fatalf("after ack: ack_received_at still null")
	}
}

func TestMeGetPatchDelete(t *testing.T) {
	s, _ := newTestServer(t)
	router := s.Router()
	alice, bob, pairingID := pairUp(t, router)
	if rec := doJSON(t, router, http.MethodPost, pairingPath(pairingID)+"/trigger", bob.APIKey, nil); rec.Code != http.StatusCreated {
		t.Fatalf("trigger: %d", rec.Code)
	}
	_ = createInvite(t, router, alice.APIKey)

	rec := doJSON(t, router, http.MethodGet, "/v1/me", alice.APIKey, nil)
	var me meView
	_ = json.Unmarshal(rec.Body.Bytes(), &me)
	if rec.Code != http.StatusOK || me.UserID != alice.UserID || me.DisplayName != "Alice" {
		t.Fatalf("get me: status %d body %s", rec.Code, rec.Body.String())
	}

	if rec := doJSON(t, router, http.MethodPatch, "/v1/me", alice.APIKey, patchMeRequest{DisplayName: "   "}); rec.Code != http.StatusBadRequest {
		t.Fatalf("blank rename: expected 400, got %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodPatch, "/v1/me", alice.APIKey, patchMeRequest{DisplayName: strings.Repeat("가", maxDisplayNameLength+1)}); rec.Code != http.StatusBadRequest {
		t.Fatalf("too-long rename: expected 400, got %d", rec.Code)
	}
	if rec := doJSON(t, router, http.MethodPatch, "/v1/me", alice.APIKey, patchMeRequest{DisplayName: "  앨리스 "}); rec.Code != http.StatusOK {
		t.Fatalf("rename: expected 200, got %d body %s", rec.Code, rec.Body.String())
	}
	if got := listPairings(t, router, bob.APIKey); len(got) != 1 || got[0].OtherUserName != "앨리스" {
		t.Fatalf("bob should see the trimmed new name, got %+v", got)
	}

	if rec := doJSON(t, router, http.MethodDelete, "/v1/me", alice.APIKey, nil); rec.Code != http.StatusNoContent {
		t.Fatalf("delete me: expected 204, got %d body %s", rec.Code, rec.Body.String())
	}
	if rec := doJSON(t, router, http.MethodGet, "/v1/me", alice.APIKey, nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("deleted key: expected 401, got %d", rec.Code)
	}
	if got := listPairings(t, router, bob.APIKey); len(got) != 0 {
		t.Fatalf("bob should have no pairings after alice deleted her account, got %+v", got)
	}
}
