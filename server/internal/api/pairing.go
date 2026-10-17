package api

import (
	"crypto/rand"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"wakelink/internal/db"
)

// inviteCodeAlphabet excludes visually-ambiguous characters (0/O, 1/I) so a
// code is easy to read aloud or type from a screen.
const inviteCodeAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

func generateInviteCode() (string, error) {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	code := make([]byte, 8)
	for i, v := range b {
		code[i] = inviteCodeAlphabet[int(v)%len(inviteCodeAlphabet)]
	}
	return string(code), nil
}

// handleCreateInvite issues a short-lived pairing code. This is the "A"
// half of mutual consent: A must deliberately generate and share this code
// -- nobody can be paired without taking this action themselves.
func (s *Server) handleCreateInvite(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)

	var code string
	for attempt := 0; attempt < 5; attempt++ {
		c, err := generateInviteCode()
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to generate invite code")
			return
		}
		if _, err := s.DB.GetInvite(r.Context(), c); err == db.ErrNotFound {
			code = c
			break
		} else if err != nil {
			log.Printf("check invite code collision: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to allocate invite code")
			return
		}
		// err == nil: genuine collision, retry with a fresh code.
	}
	if code == "" {
		writeError(w, http.StatusInternalServerError, "failed to allocate invite code")
		return
	}

	inv, err := s.DB.CreateInvite(r.Context(), code, user.ID, s.InviteTTL)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create invite")
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"pairing_code": inv.Code,
		"expires_at":   inv.ExpiresAt,
		"deep_link":    "wakelink://invite/" + inv.Code,
	})
}

type acceptInviteRequest struct {
	Code string `json:"pairing_code"`
}

// handleAcceptInvite is the "B" half of mutual consent: B must themselves
// type in (or scan) A's code. Only this explicit action on both sides
// creates a pairing -- neither side can add the other unilaterally.
func (s *Server) handleAcceptInvite(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)

	var req acceptInviteRequest
	if err := readJSON(w, r, &req); err != nil || req.Code == "" {
		writeError(w, http.StatusBadRequest, "pairing_code is required")
		return
	}

	inv, err := s.DB.GetInvite(r.Context(), req.Code)
	if err != nil {
		if err == db.ErrNotFound {
			writeError(w, http.StatusNotFound, "invite not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	if inv.Used {
		writeError(w, http.StatusConflict, "invite already used")
		return
	}
	if inv.ExpiresAt < time.Now().Unix() {
		writeError(w, http.StatusGone, "invite expired")
		return
	}
	if inv.InviterUserID == user.ID {
		writeError(w, http.StatusBadRequest, "cannot pair with yourself")
		return
	}

	if _, err := s.DB.GetPairingBetween(r.Context(), inv.InviterUserID, user.ID); err == nil {
		writeError(w, http.StatusConflict, "already paired")
		return
	} else if err != db.ErrNotFound {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	pairing, err := s.DB.CreatePairing(r.Context(), inv.InviterUserID, user.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to create pairing")
		return
	}
	if err := s.DB.MarkInviteUsed(r.Context(), inv.Code); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to mark invite used")
		return
	}

	inviter, err := s.DB.GetUserByID(r.Context(), inv.InviterUserID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"pairing_id":       pairing.ID,
		"paired_with_name": inviter.DisplayName,
	})
}

type pairingView struct {
	ID            int64  `json:"id"`
	OtherUserName string `json:"other_user_name"`
	ICanWakeThem  bool   `json:"i_can_wake_them"`
	TheyCanWakeMe bool   `json:"they_can_wake_me"`
	CreatedAt     int64  `json:"created_at"`
	// OtherPublicKey is the contact's device key for sealing wakes end to
	// end; null if their app is too old to have one.
	OtherPublicKey *string `json:"other_public_key"`
	// TheyAcceptWakes is false while the contact isn't taking wakes from
	// this user: either receiving is switched off entirely, or they turned
	// off this contact. Deliberately one flag, so the sender can't tell
	// "paused for everyone" from "paused for me".
	TheyAcceptWakes bool `json:"they_accept_wakes"`
	// IAcceptFromThem is this user's own per-contact switch.
	IAcceptFromThem bool `json:"i_accept_from_them"`
}

func (s *Server) handleListPairings(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)

	limit := 50
	if l := r.URL.Query().Get("limit"); l != "" {
		if parsed, err := strconv.Atoi(l); err == nil && parsed > 0 && parsed <= 100 {
			limit = parsed
		}
	}

	now := time.Now().Unix()
	pairings, err := s.DB.ListPairingsForUser(r.Context(), user.ID, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to list pairings")
		return
	}

	out := make([]pairingView, 0, len(pairings))
	for _, p := range pairings {
		otherID := p.UserAID
		if otherID == user.ID {
			otherID = p.UserBID
		}
		other, err := s.DB.GetUserByID(r.Context(), otherID)
		if err != nil {
			continue
		}
		view := pairingView{
			ID:              p.ID,
			OtherUserName:   other.DisplayName,
			CreatedAt:       p.CreatedAt,
			TheyAcceptWakes: other.AcceptsWakesAt(now) && p.Accepts(user.ID, other.ID),
			IAcceptFromThem: p.Accepts(other.ID, user.ID),
		}
		if other.PublicKey.Valid {
			view.OtherPublicKey = &other.PublicKey.String
		}
		if p.UserAID == user.ID {
			view.ICanWakeThem = p.ACanWakeB
			view.TheyCanWakeMe = p.BCanWakeA
		} else {
			view.ICanWakeThem = p.BCanWakeA
			view.TheyCanWakeMe = p.ACanWakeB
		}
		out = append(out, view)
	}
	writeJSON(w, http.StatusOK, out)
}

type patchPairingRequest struct {
	// ICanWakeThem sets whether the requesting user is allowed to trigger
	// the other side of this pairing. It only ever affects the
	// requester's own outbound direction -- a user can revoke their own
	// permission to wake someone, or a permission they've been granted
	// can't be re-granted by anyone but the grantor.
	ICanWakeThem *bool `json:"i_can_wake_them"`
	// IAcceptFromThem sets whether the requesting user accepts wakes from
	// the other side -- the receiver's own per-contact consent.
	IAcceptFromThem *bool `json:"i_accept_from_them"`
}

func (s *Server) handlePatchPairing(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	pairingID, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid pairing id")
		return
	}

	var req patchPairingRequest
	if err := readJSON(w, r, &req); err != nil || (req.ICanWakeThem == nil && req.IAcceptFromThem == nil) {
		writeError(w, http.StatusBadRequest, "i_can_wake_them or i_accept_from_them is required")
		return
	}

	var err2 error
	if req.ICanWakeThem != nil {
		err2 = s.DB.SetPermission(r.Context(), pairingID, user.ID, *req.ICanWakeThem)
	}
	if err2 == nil && req.IAcceptFromThem != nil {
		err2 = s.DB.SetAcceptsFrom(r.Context(), pairingID, user.ID, *req.IAcceptFromThem)
	}
	if err2 != nil {
		if err2 == db.ErrNotFound {
			writeError(w, http.StatusNotFound, "pairing not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to update pairing")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handleDeletePairing lets either side unpair unilaterally and immediately
// -- consent to be paired does not obligate either party to stay paired.
func (s *Server) handleDeletePairing(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r)
	pairingID, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid pairing id")
		return
	}
	if err := s.DB.DeletePairing(r.Context(), pairingID, user.ID); err != nil {
		if err == db.ErrNotFound {
			writeError(w, http.StatusNotFound, "pairing not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to delete pairing")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
