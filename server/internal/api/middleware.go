package api

import (
	"context"
	"net/http"
	"strings"

	"wakelink/internal/auth"
	"wakelink/internal/db"
)

type ctxKey int

const userCtxKey ctxKey = iota

// requireAuth resolves the Authorization: Bearer <api_key> header to a
// user and attaches it to the request context. Keys are compared by their
// stored SHA-256 hash, never in plaintext.
func (s *Server) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		const prefix = "Bearer "
		if !strings.HasPrefix(authHeader, prefix) {
			writeError(w, http.StatusUnauthorized, "missing bearer token")
			return
		}
		raw := strings.TrimPrefix(authHeader, prefix)
		hash := auth.HashAPIKey(raw)

		user, err := s.DB.GetUserByAPIKeyHash(r.Context(), hash)
		if err != nil {
			if err == db.ErrNotFound {
				writeError(w, http.StatusUnauthorized, "invalid api key")
				return
			}
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}

		ctx := context.WithValue(r.Context(), userCtxKey, user)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func userFromContext(r *http.Request) *db.User {
	u, _ := r.Context().Value(userCtxKey).(*db.User)
	return u
}
