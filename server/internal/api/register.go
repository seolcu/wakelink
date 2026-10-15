package api

import (
	"errors"
	"net"
	"net/http"
	"strings"
	"unicode/utf8"

	"wakelink/internal/auth"
)

// maxDisplayNameLength is in runes, not bytes -- most users of the default
// instance type Korean names, which are 3 bytes per character in UTF-8.
const maxDisplayNameLength = 40

// normalizeDisplayName trims and validates a user-chosen display name. It's
// the only thing a contact ever sees about you, so it's shared by
// registration and PATCH /v1/me.
func normalizeDisplayName(raw string) (string, error) {
	name := strings.TrimSpace(raw)
	if name == "" {
		return "", errors.New("display_name is required")
	}
	if utf8.RuneCountInString(name) > maxDisplayNameLength {
		return "", errors.New("display_name is too long")
	}
	return name, nil
}

type registerRequest struct {
	DisplayName string `json:"display_name"`
	FCMToken    string `json:"fcm_token"`
}

type registerResponse struct {
	UserID int64  `json:"user_id"`
	APIKey string `json:"api_key"`
}

// handleRegister creates a new WakeLink identity for a fresh app install.
// It is necessarily unauthenticated (there's no credential yet), so it is
// throttled per client IP (RegisterLimit) to keep anyone from minting
// accounts in bulk.
func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if !s.RegisterLimit.Allow(clientIP(r)) {
		writeError(w, http.StatusTooManyRequests, "too many registrations, try again later")
		return
	}
	var req registerRequest
	if err := readJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	name, err := normalizeDisplayName(req.DisplayName)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	rawKey, hash, err := auth.GenerateAPIKey()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate api key")
		return
	}

	user, err := s.DB.CreateUser(r.Context(), name, hash)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create user")
		return
	}

	if req.FCMToken != "" {
		if err := s.DB.UpdateFCMToken(r.Context(), user.ID, req.FCMToken); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to store fcm token")
			return
		}
	}

	writeJSON(w, http.StatusCreated, registerResponse{UserID: user.ID, APIKey: rawKey})
}

// clientIP is the caller's address, as set by forwardedFor.
func clientIP(r *http.Request) string {
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

// forwardedFor replaces RemoteAddr with the client address the reverse
// proxy reports. Caddy (with no trusted proxies configured) overwrites
// X-Forwarded-For with the real client IP, so its first entry is
// authoritative. Headers a client can set freely, like X-Real-IP or
// True-Client-IP -- which chi's RealIP middleware would prefer -- are
// deliberately ignored, or anyone could dodge per-IP limits.
func forwardedFor(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			first, _, _ := strings.Cut(xff, ",")
			if ip := net.ParseIP(strings.TrimSpace(first)); ip != nil {
				r.RemoteAddr = net.JoinHostPort(ip.String(), "0")
			}
		}
		next.ServeHTTP(w, r)
	})
}
