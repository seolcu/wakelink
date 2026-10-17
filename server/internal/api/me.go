package api

import (
	"encoding/base64"
	"net/http"
	"time"

	"wakelink/internal/auth"
)

type meView struct {
	UserID         int64  `json:"user_id"`
	DisplayName    string `json:"display_name"`
	AcceptingWakes bool   `json:"accepting_wakes"`
}

func (s *Server) handleGetMe(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	writeJSON(w, http.StatusOK, meView{UserID: user.ID, DisplayName: user.DisplayName, AcceptingWakes: user.AcceptsWakesAt(time.Now().Unix())})
}

type patchMeRequest struct {
	DisplayName string `json:"display_name"`
}

// handlePatchMe renames the caller. The new name shows up for every
// contact on their next pairing list fetch, and in future wake alerts.
func (s *Server) handlePatchMe(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)

	var req patchMeRequest
	if err := readJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	name, err := normalizeDisplayName(req.DisplayName)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := s.DB.UpdateDisplayName(r.Context(), user.ID, name); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update display name")
		return
	}
	writeJSON(w, http.StatusOK, meView{UserID: user.ID, DisplayName: name, AcceptingWakes: user.AcceptsWakesAt(time.Now().Unix())})
}

// handleDeleteMe permanently deletes the caller's account: every pairing
// (so no contact can wake this device any more), the trigger history, any
// open invites, and the API key itself.
func (s *Server) handleDeleteMe(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	if err := s.DB.DeleteUser(r.Context(), user.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to delete account")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleRotateKey issues the caller a new API key and immediately
// invalidates their old one, for recovery from a suspected key leak
// without forcing a full re-register (which would also lose all pairings).
func (s *Server) handleRotateKey(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	rawKey, hash, err := auth.GenerateAPIKey()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate api key")
		return
	}
	if err := s.DB.RotateAPIKeyHash(r.Context(), user.ID, hash); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to rotate api key")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"api_key": rawKey})
}

type publicKeyRequest struct {
	PublicKey string `json:"public_key"`
}

// maxPublicKeyLen bounds the stored key (a Tink public keyset is ~100
// bytes base64); it's opaque to the server, which only relays it.
const maxPublicKeyLen = 1024

// handlePutPublicKey records the caller's device public key, which their
// contacts use to seal wakes (see triggerRequest.Sealed).
func (s *Server) handlePutPublicKey(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	var req publicKeyRequest
	if err := readJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	raw, err := base64.StdEncoding.DecodeString(req.PublicKey)
	if err != nil || len(raw) == 0 || len(req.PublicKey) > maxPublicKeyLen {
		writeError(w, http.StatusBadRequest, "invalid public_key")
		return
	}
	if err := s.DB.SetPublicKey(r.Context(), user.ID, req.PublicKey); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save public key")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type acceptingRequest struct {
	Accepting *bool `json:"accepting"`
	// ResumeAt (unix seconds) turns switching off into a pause that ends
	// by itself, so a forgotten switch doesn't leave the person unwakeable.
	ResumeAt *int64 `json:"resume_at,omitempty"`
}

// maxPause bounds a temporary pause; longer means "switched off".
const maxPause = 7 * 24 * time.Hour

// handlePutAccepting mirrors the app's "receiving wake-ups" switch, so
// contacts see it and wakes are refused up front while it's off.
func (s *Server) handlePutAccepting(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	var req acceptingRequest
	if err := readJSON(w, r, &req); err != nil || req.Accepting == nil {
		writeError(w, http.StatusBadRequest, "accepting is required")
		return
	}
	resumeAt := req.ResumeAt
	if *req.Accepting {
		resumeAt = nil
	} else if resumeAt != nil {
		now := time.Now()
		if *resumeAt <= now.Unix() || *resumeAt > now.Add(maxPause).Unix() {
			writeError(w, http.StatusBadRequest, "resume_at must be in the next 7 days")
			return
		}
	}
	if err := s.DB.SetAcceptingWakes(r.Context(), user.ID, *req.Accepting, resumeAt); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
