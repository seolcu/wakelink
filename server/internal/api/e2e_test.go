package api

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"testing"
)

func TestPublicKeysAndSealedTriggers(t *testing.T) {
	s, sender := newTestServer(t)
	s.RateLimit.Cooldown = 0
	router := s.Router()
	alice, bob, pairingID := pairUp(t, router)

	// Before Alice publishes a key, Bob sees none for her.
	if got := listPairings(t, router, bob.APIKey); got[0].OtherPublicKey != nil {
		t.Fatalf("expected no key yet, got %q", *got[0].OtherPublicKey)
	}

	for _, bad := range []string{"", "not base64!", base64.StdEncoding.EncodeToString(make([]byte, 2000))} {
		if rec := doJSON(t, router, http.MethodPut, "/v1/me/public-key", alice.APIKey, publicKeyRequest{PublicKey: bad}); rec.Code != http.StatusBadRequest {
			t.Errorf("public key %.20q: expected 400, got %d", bad, rec.Code)
		}
	}
	aliceKey := base64.StdEncoding.EncodeToString([]byte("alice-public-keyset"))
	if rec := doJSON(t, router, http.MethodPut, "/v1/me/public-key", alice.APIKey, publicKeyRequest{PublicKey: aliceKey}); rec.Code != http.StatusNoContent {
		t.Fatalf("put key: status %d body %s", rec.Code, rec.Body.String())
	}
	if got := listPairings(t, router, bob.APIKey); got[0].OtherPublicKey == nil || *got[0].OtherPublicKey != aliceKey {
		t.Fatalf("Bob should now see Alice's key, got %+v", got[0])
	}

	// A sealed trigger is relayed as-is, without the sender's name.
	sealed := base64.StdEncoding.EncodeToString([]byte("ciphertext"))
	path := pairingPath(pairingID) + "/trigger"
	if rec := doJSON(t, router, http.MethodPost, path, bob.APIKey, triggerRequest{Sealed: sealed}); rec.Code != http.StatusCreated {
		t.Fatalf("sealed trigger: status %d body %s", rec.Code, rec.Body.String())
	}
	if m := sender.messages[0]; m.Sealed != sealed || m.Sound != "" || m.Image != "" {
		t.Fatalf("unexpected push %+v", m)
	}

	for name, req := range map[string]triggerRequest{
		"sealed plus plaintext sound": {Sealed: sealed, Sound: "preset:chime"},
		"not base64":                  {Sealed: "%%%"},
		"too long":                    {Sealed: base64.StdEncoding.EncodeToString(make([]byte, maxSealedLen))},
	} {
		if rec := doJSON(t, router, http.MethodPost, path, bob.APIKey, req); rec.Code != http.StatusBadRequest {
			t.Errorf("%s: expected 400, got %d", name, rec.Code)
		}
	}
}

func TestEncryptedUploadsAreAccepted(t *testing.T) {
	s, _ := newTestServer(t)
	router := s.Router()
	alice := register(t, router, "Alice", "alice-token")

	blob := append([]byte("WLE1"), make([]byte, 64)...)
	for _, path := range []string{"/v1/sounds?kind=voice&name=x", "/v1/images?name=x"} {
		rec := doRaw(t, router, http.MethodPost, path, alice.APIKey, blob)
		if rec.Code != http.StatusCreated {
			t.Fatalf("%s: status %d body %s", path, rec.Code, rec.Body.String())
		}
		var v mediaView
		_ = json.Unmarshal(rec.Body.Bytes(), &v)
		if v.Mime != "application/octet-stream" {
			t.Fatalf("%s: encrypted upload should be opaque, got mime %q", path, v.Mime)
		}
	}
}
