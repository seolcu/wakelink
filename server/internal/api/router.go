package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
)

func (s *Server) Router() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)

	r.Get("/healthz", s.handleHealthz)
	r.Get("/version", s.handleVersion)
	r.Post("/v1/register", s.handleRegister)

	r.Group(func(r chi.Router) {
		r.Use(s.requireAuth)
		r.Get("/v1/me", s.handleGetMe)
		r.Patch("/v1/me", s.handlePatchMe)
		r.Delete("/v1/me", s.handleDeleteMe)
		r.Post("/v1/devices/token", s.handleUpdateToken)
		r.Post("/v1/invites", s.handleCreateInvite)
		r.Post("/v1/invites/accept", s.handleAcceptInvite)
		r.Get("/v1/pairings", s.handleListPairings)
		r.Patch("/v1/pairings/{id}", s.handlePatchPairing)
		r.Delete("/v1/pairings/{id}", s.handleDeletePairing)
		r.Post("/v1/pairings/{id}/trigger", s.handleTrigger)
		r.Post("/v1/triggers/{id}/ack", s.handleAckTrigger)
		r.Get("/v1/triggers", s.handleListTriggers)
		r.Post("/v1/me/rotate-key", s.handleRotateKey)
		r.Get("/v1/metrics", s.handleMetrics)
	})

	return r
}
