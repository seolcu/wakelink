package auth

import "testing"

func TestGenerateAPIKeyIsUniqueAndHashMatches(t *testing.T) {
	raw1, hash1, err := GenerateAPIKey()
	if err != nil {
		t.Fatalf("generate key 1: %v", err)
	}
	raw2, hash2, err := GenerateAPIKey()
	if err != nil {
		t.Fatalf("generate key 2: %v", err)
	}

	if raw1 == raw2 {
		t.Fatalf("expected two calls to produce different raw keys")
	}
	if hash1 == hash2 {
		t.Fatalf("expected two calls to produce different hashes")
	}
	if HashAPIKey(raw1) != hash1 {
		t.Fatalf("HashAPIKey(raw1) does not match the hash returned alongside it")
	}
	if HashAPIKey(raw2) != hash2 {
		t.Fatalf("HashAPIKey(raw2) does not match the hash returned alongside it")
	}
}

func TestHashAPIKeyIsDeterministic(t *testing.T) {
	raw, hash, err := GenerateAPIKey()
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	if HashAPIKey(raw) != hash {
		t.Fatalf("first HashAPIKey call did not match")
	}
	if HashAPIKey(raw) != HashAPIKey(raw) {
		t.Fatalf("HashAPIKey is not deterministic")
	}
}
