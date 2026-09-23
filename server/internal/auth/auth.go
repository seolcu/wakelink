// Package auth handles API key generation and hashing. It has no database
// dependency by design -- credential lookup lives in the api package, which
// owns request/response and context wiring.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
)

// GenerateAPIKey returns a new random API key and its SHA-256 hash (hex).
// The raw key is returned to the client exactly once at registration time;
// only the hash is ever persisted.
func GenerateAPIKey() (raw string, hash string, err error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", "", err
	}
	raw = "wl_" + base64.RawURLEncoding.EncodeToString(buf)
	hash = HashAPIKey(raw)
	return raw, hash, nil
}

// HashAPIKey returns the hex-encoded SHA-256 hash of a raw API key.
// Lookups compare hashes (via a unique index / exact match), not raw keys.
func HashAPIKey(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}
