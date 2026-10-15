package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
)

func (s *Server) Router() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(forwardedFor)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)

	r.Get("/healthz", s.handleHealthz)
	r.Get("/version", s.handleVersion)
	if s.DownloadURL != "" {
		r.Get("/download", s.handleDownload)
	}
	r.Post("/v1/register", s.handleRegister)
	if s.Site != nil {
		s.Site.Mount(r)
	}

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
		r.Post("/v1/triggers/{id}/dismiss", s.handleDismissTrigger)
		r.Get("/v1/triggers", s.handleListTriggers)
		r.Post("/v1/me/rotate-key", s.handleRotateKey)
		r.Put("/v1/me/public-key", s.handlePutPublicKey)
		r.Put("/v1/me/accepting", s.handlePutAccepting)
		r.Get("/v1/metrics", s.handleMetrics)
		r.Get("/v1/sounds", s.list(soundMedia))
		r.Post("/v1/sounds", s.upload(soundMedia))
		r.Delete("/v1/sounds/{id}", s.remove(soundMedia))
		r.Get("/v1/sounds/{id}/audio", s.serve(soundMedia))
		r.Get("/v1/images", s.list(imageMedia))
		r.Post("/v1/images", s.upload(imageMedia))
		r.Delete("/v1/images/{id}", s.remove(imageMedia))
		r.Get("/v1/images/{id}/data", s.serve(imageMedia))
	})

	return r
}
