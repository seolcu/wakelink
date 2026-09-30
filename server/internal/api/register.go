package api

import (
	"errors"
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
// It is deliberately unauthenticated (there's no credential yet) --
// callers should rate-limit this at the reverse proxy / infra layer if
// abuse becomes a concern at public-instance scale.
func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
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
