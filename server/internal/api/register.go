package api

import (
	"net/http"

	"wakelink/internal/auth"
)

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
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DisplayName == "" {
		writeError(w, http.StatusBadRequest, "display_name is required")
		return
	}

	rawKey, hash, err := auth.GenerateAPIKey()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate api key")
		return
	}

	user, err := s.DB.CreateUser(r.Context(), req.DisplayName, hash)
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
