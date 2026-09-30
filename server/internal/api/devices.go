package api

import "net/http"

type updateTokenRequest struct {
	FCMToken string `json:"fcm_token"`
}

// handleUpdateToken refreshes the caller's FCM token. Called on every app
// start and whenever FirebaseMessagingService.onNewToken fires -- FCM
// tokens rotate periodically and a stale one silently drops pushes.
func (s *Server) handleUpdateToken(w http.ResponseWriter, r *http.Request) {
	var req updateTokenRequest
	if err := readJSON(w, r, &req); err != nil || req.FCMToken == "" {
		writeError(w, http.StatusBadRequest, "fcm_token is required")
		return
	}
	user := userFromContext(r)
	if err := s.DB.UpdateFCMToken(r.Context(), user.ID, req.FCMToken); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to update token")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
