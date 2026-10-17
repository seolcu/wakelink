package db

import "database/sql"

type User struct {
	ID          int64
	DisplayName string
	FCMToken    sql.NullString
	APIKeyHash  string
	CreatedAt   int64
	PublicKey   sql.NullString
	// AcceptingWakes mirrors the app's "receiving wake-ups" switch.
	AcceptingWakes bool
	// AcceptingResumesAt, when set, ends a switched-off period on its own
	// (a temporary pause); see AcceptsWakesAt.
	AcceptingResumesAt sql.NullInt64
}

// AcceptsWakesAt reports whether the user takes wakes at unix time now:
// switched on, or switched off only until a resume time that has passed.
func (u *User) AcceptsWakesAt(now int64) bool {
	return u.AcceptingWakes || (u.AcceptingResumesAt.Valid && now >= u.AcceptingResumesAt.Int64)
}

type Invite struct {
	Code          string
	InviterUserID int64
	ExpiresAt     int64
	Used          bool
	CreatedAt     int64
}

type Pairing struct {
	ID        int64
	UserAID   int64
	UserBID   int64
	ACanWakeB bool
	BCanWakeA bool
	CreatedAt int64
	// AAcceptsFromB is a's own choice to receive wakes from b (and vice
	// versa) -- the receiving side's consent, independent of CanWake.
	AAcceptsFromB bool
	BAcceptsFromA bool
}

// CanWake reports whether fromUserID currently has permission to wake
// toUserID under this pairing. Returns false for any pair of ids that
// aren't actually the two members of this pairing.
func (p *Pairing) CanWake(fromUserID, toUserID int64) bool {
	if p.UserAID == fromUserID && p.UserBID == toUserID {
		return p.ACanWakeB
	}
	if p.UserBID == fromUserID && p.UserAID == toUserID {
		return p.BCanWakeA
	}
	return false
}

// Accepts reports whether toUserID currently accepts wakes from
// fromUserID under this pairing (the receiver's per-contact switch).
// False for ids that aren't the two members.
func (p *Pairing) Accepts(fromUserID, toUserID int64) bool {
	if p.UserBID == fromUserID && p.UserAID == toUserID {
		return p.AAcceptsFromB
	}
	if p.UserAID == fromUserID && p.UserBID == toUserID {
		return p.BAcceptsFromA
	}
	return false
}

// Trigger is the raw DB row. sql.Null* fields marshal to JSON as
// {"String":...,"Valid":...} by default, which is not a fit API shape --
// the api package converts this to a plain DTO before serving it (see
// api.triggerView in trigger.go), so no json tags are needed here.
type Trigger struct {
	ID            int64
	PairingID     int64
	FromUserID    int64
	ToUserID      int64
	CreatedAt     int64
	FCMMessageID  sql.NullString
	SendStatus    string
	SendError     sql.NullString
	AckReceivedAt sql.NullInt64
	DismissedAt   sql.NullInt64
}
