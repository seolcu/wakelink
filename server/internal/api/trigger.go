package api

import (
	"context"
	"database/sql"
	"encoding/base64"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"wakelink/internal/db"
	"wakelink/internal/fcm"
	"wakelink/internal/ratelimit"
)

type triggerRequest struct {
	// Sound is "", "device", "preset:<name>" or "custom:<id>" (one of the
	// sender's own uploads); see resolveTriggerSound.
	Sound string `json:"sound"`
	// Image is "" or "custom:<id>", one of the sender's own images to show
	// on the alarm screen.
	Image string `json:"image"`
	// Sealed is the end-to-end encrypted form (base64, made by the app for
	// the recipient's public key): it carries the sender's name, sound,
	// image and the keys for those uploads. When set, Sound and Image must
	// be empty -- the server never learns them -- and only Sealed is pushed.
	Sealed string `json:"sealed"`

	pushImage string
}

// maxSealedLen keeps the push comfortably inside FCM's 4 KB data limit.
const maxSealedLen = 3000

// handleTrigger is the core of the whole system: it checks the caller is a
// member of the pairing, that they currently have permission to wake the
// other side, applies the rate limit, and sends the FCM push.
func (s *Server) handleTrigger(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	pairingID, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid pairing id")
		return
	}

	pairing, err := s.DB.GetPairing(r.Context(), pairingID)
	if err != nil {
		if err == db.ErrNotFound {
			writeError(w, http.StatusNotFound, "pairing not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	if pairing.UserAID != user.ID && pairing.UserBID != user.ID {
		writeError(w, http.StatusForbidden, "not a member of this pairing")
		return
	}

	otherID := pairing.UserAID
	if otherID == user.ID {
		otherID = pairing.UserBID
	}
	if !pairing.CanWake(user.ID, otherID) {
		writeError(w, http.StatusForbidden, "not permitted to wake this contact")
		return
	}

	// Optional body (older apps send none): the sound the sender picked.
	var req triggerRequest
	if err := readJSON(w, r, &req); err != nil && !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Sealed != "" {
		if req.Sound != "" || req.Image != "" || len(req.Sealed) > maxSealedLen {
			writeError(w, http.StatusBadRequest, "sealed triggers carry everything inside the sealed payload")
			return
		}
		if _, err := base64.StdEncoding.DecodeString(req.Sealed); err != nil {
			writeError(w, http.StatusBadRequest, "invalid sealed payload")
			return
		}
	}
	pushSound, err := s.resolveTriggerSound(r.Context(), user.ID, req.Sound)
	if err == nil {
		var pushImage string
		pushImage, err = s.resolveTriggerImage(r.Context(), user.ID, req.Image)
		req.pushImage = pushImage
	}
	if err != nil {
		if errors.Is(err, errInvalidMedia) {
			writeError(w, http.StatusBadRequest, "unknown sound or image")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	if err := s.RateLimit.Check(r.Context(), s.DB, pairingID, user.ID); err != nil {
		switch err {
		case ratelimit.ErrCooldown:
			writeError(w, http.StatusTooManyRequests, "cooldown active, try again shortly")
		case ratelimit.ErrDailyCap:
			writeError(w, http.StatusTooManyRequests, "daily trigger limit reached")
		default:
			writeError(w, http.StatusInternalServerError, "internal error")
		}
		return
	}

	recipient, err := s.DB.GetUserByID(r.Context(), otherID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	if !recipient.AcceptingWakes {
		// Their app would just drop it; say so now instead of letting the
		// sender wait for an ack that never comes.
		writeError(w, http.StatusConflict, "recipient is not accepting wakes")
		return
	}
	if !recipient.FCMToken.Valid || recipient.FCMToken.String == "" {
		writeError(w, http.StatusUnprocessableEntity, "recipient device is not registered for push")
		return
	}

	trig, err := s.DB.CreateTrigger(r.Context(), pairingID, user.ID, otherID, req.Sound, req.Image)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to record trigger")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	msgID, sendErr := s.FCM.SendWakeTrigger(ctx, recipient.FCMToken.String, fcm.WakeMessage{
		TriggerID:       trig.ID,
		FromDisplayName: user.DisplayName,
		Sound:           pushSound,
		Image:           req.pushImage,
		Sealed:          req.Sealed,
	})
	if errors.Is(sendErr, fcm.ErrUnregistered) {
		s.Metrics.TriggersFailed.Add(1)
		_ = s.DB.UpdateTriggerSendResult(r.Context(), trig.ID, "fcm_error", "", sendErr.Error())
		_ = s.DB.ClearFCMToken(r.Context(), recipient.ID)
		writeError(w, http.StatusUnprocessableEntity, "recipient device is not registered for push")
		return
	}
	if sendErr != nil {
		s.Metrics.TriggersFailed.Add(1)
		if err := s.DB.UpdateTriggerSendResult(r.Context(), trig.ID, "fcm_error", "", sendErr.Error()); err != nil {
			log.Printf("update trigger send result (fcm_error): %v", err)
		}
		writeError(w, http.StatusBadGateway, "failed to deliver push")
		return
	}
	s.Metrics.TriggersSent.Add(1)
	if err := s.DB.UpdateTriggerSendResult(r.Context(), trig.ID, "sent", msgID, ""); err != nil {
		log.Printf("update trigger send result (sent): %v", err)
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"trigger_id": trig.ID,
		"status":     "sent",
	})
}

// handleAckTrigger is called by the receiver app the moment it actually
// starts playing the alarm sound. The sender UI waits for this before
// claiming success -- "sent" and "actually rang" are not the same thing.
func (s *Server) handleAckTrigger(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	triggerID, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid trigger id")
		return
	}
	if err := s.DB.AckTrigger(r.Context(), triggerID, user.ID); err != nil {
		if err == db.ErrNotFound {
			writeError(w, http.StatusNotFound, "trigger not found or already acked")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	s.Metrics.TriggersAcked.Add(1)
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// triggerView is the JSON-friendly shape of db.Trigger -- sql.Null* fields
// don't marshal to plain values by default, so this maps them to *T.
type triggerView struct {
	ID            int64   `json:"id"`
	PairingID     int64   `json:"pairing_id"`
	FromUserID    int64   `json:"from_user_id"`
	ToUserID      int64   `json:"to_user_id"`
	CreatedAt     int64   `json:"created_at"`
	FCMMessageID  *string `json:"fcm_message_id"`
	SendStatus    string  `json:"send_status"`
	SendError     *string `json:"send_error"`
	AckReceivedAt *int64  `json:"ack_received_at"`
}

func newTriggerView(t db.Trigger) triggerView {
	v := triggerView{
		ID:         t.ID,
		PairingID:  t.PairingID,
		FromUserID: t.FromUserID,
		ToUserID:   t.ToUserID,
		CreatedAt:  t.CreatedAt,
		SendStatus: t.SendStatus,
	}
	if s := nullString(t.FCMMessageID); s != nil {
		v.FCMMessageID = s
	}
	if s := nullString(t.SendError); s != nil {
		v.SendError = s
	}
	if t.AckReceivedAt.Valid {
		v.AckReceivedAt = &t.AckReceivedAt.Int64
	}
	return v
}

func nullString(ns sql.NullString) *string {
	if !ns.Valid {
		return nil
	}
	return &ns.String
}

func (s *Server) handleListTriggers(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	pairingIDStr := r.URL.Query().Get("pairing_id")
	if pairingIDStr == "" {
		writeError(w, http.StatusBadRequest, "pairing_id query param is required")
		return
	}
	pairingID, err := strconv.ParseInt(pairingIDStr, 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid pairing_id")
		return
	}

	pairing, err := s.DB.GetPairing(r.Context(), pairingID)
	if err != nil {
		writeError(w, http.StatusNotFound, "pairing not found")
		return
	}
	if pairing.UserAID != user.ID && pairing.UserBID != user.ID {
		writeError(w, http.StatusForbidden, "not a member of this pairing")
		return
	}

	limit := 20
	if l := r.URL.Query().Get("limit"); l != "" {
		if parsed, err := strconv.Atoi(l); err == nil && parsed > 0 && parsed <= 100 {
			limit = parsed
		}
	}

	triggers, err := s.DB.ListTriggersForPairing(r.Context(), pairingID, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list triggers")
		return
	}

	out := make([]triggerView, 0, len(triggers))
	for _, t := range triggers {
		out = append(out, newTriggerView(t))
	}
	writeJSON(w, http.StatusOK, out)
}
