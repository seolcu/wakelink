// Package web serves wakelinkd's public, human-facing pages: the landing
// page at /, the invite page behind shareable https invite links, and the
// Digital Asset Links file that lets the Android app claim those links.
// Everything is embedded in the binary so the server stays a single,
// standalone build.
package web

import (
	"bytes"
	"embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html/template"
	"io/fs"
	"log"
	"net/http"
	"regexp"
	"strings"

	"github.com/go-chi/chi/v5"
)

//go:embed templates assets
var files embed.FS

// AndroidPackage is the applicationId the asset-links file vouches for.
const AndroidPackage = "app.wakelink.android"

// invitePattern accepts any plausible invite code without touching the DB:
// the page never reveals whether a code exists or who issued it.
var invitePattern = regexp.MustCompile(`^[A-Z0-9]{4,16}$`)

// Config is what a deployment can vary about the site.
type Config struct {
	// DownloadHref is where "download" buttons point ("/download" when the
	// server's redirect is enabled).
	DownloadHref string
	// CertSHA256 is the release signing certificate's SHA-256 fingerprint,
	// as produced by NormalizeFingerprint. Empty disables assetlinks.json,
	// so https invite links just open the web page instead of the app.
	CertSHA256 string
}

type Site struct {
	cfg Config
	// pages[lang][name]: each page is templates/<lang>/<name> wrapped in
	// the shared templates/base.html.
	pages      map[string]map[string]*template.Template
	assets     http.Handler
	assetLinks []byte
}

func New(cfg Config) (*Site, error) {
	s := &Site{cfg: cfg, pages: map[string]map[string]*template.Template{}}
	for _, lang := range []string{langKorean, langEnglish} {
		s.pages[lang] = map[string]*template.Template{}
		for _, name := range []string{"index.html", "invite.html"} {
			t, err := template.ParseFS(files, "templates/base.html", "templates/"+lang+"/"+name)
			if err != nil {
				return nil, err
			}
			s.pages[lang][name] = t
		}
	}
	assetsFS, err := fs.Sub(files, "assets")
	if err != nil {
		return nil, err
	}
	s.assets = http.StripPrefix("/assets/", http.FileServer(http.FS(assetsFS)))
	if cfg.CertSHA256 != "" {
		s.assetLinks, err = json.Marshal([]map[string]any{{
			"relation": []string{"delegate_permission/common.handle_all_urls"},
			"target": map[string]any{
				"namespace":                "android_app",
				"package_name":             AndroidPackage,
				"sha256_cert_fingerprints": []string{cfg.CertSHA256},
			},
		}})
		if err != nil {
			return nil, err
		}
	}
	return s, nil
}

// Mount registers the site's routes. API routes live alongside and never
// overlap (/v1/*, /healthz, /version, /download).
func (s *Site) Mount(r chi.Router) {
	r.Get("/", s.handleIndex)
	r.Get("/invite/{code}", s.handleInvite)
	r.Get("/assets/*", s.handleAsset)
	r.Get("/robots.txt", s.handleRobots)
	if s.assetLinks != nil {
		r.Get("/.well-known/assetlinks.json", s.handleAssetLinks)
	}
}

type pageData struct {
	DownloadHref string
	Code         string
	// Lang is the page's language; Path is the current path, for the
	// language switcher and hreflang links (path + "?lang=xx").
	Lang string
	Path string
	// Origin is the absolute scheme://host the page was requested on, for
	// Open Graph tags (link previews need absolute URLs).
	Origin string
}

func requestOrigin(r *http.Request) string {
	scheme := "https"
	if p := r.Header.Get("X-Forwarded-Proto"); p != "" {
		scheme = p
	} else if r.TLS == nil && (strings.HasPrefix(r.Host, "localhost") || strings.HasPrefix(r.Host, "127.")) {
		scheme = "http"
	}
	return scheme + "://" + r.Host
}

func (s *Site) render(w http.ResponseWriter, r *http.Request, name string, data pageData) {
	lang, fromQuery := pickLanguage(r)
	data.Lang = lang
	data.Path = r.URL.Path
	data.DownloadHref = s.cfg.DownloadHref
	data.Origin = requestOrigin(r)
	t := s.pages[lang][name]

	var buf bytes.Buffer
	if err := t.ExecuteTemplate(&buf, "base", data); err != nil {
		log.Printf("render page: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	setPageHeaders(w)
	w.Header().Set("Content-Language", lang)
	// Same URL, different language depending on these request headers.
	w.Header().Set("Vary", "Accept-Language, Cookie")
	if fromQuery {
		rememberLanguage(w, lang)
	}
	_, _ = buf.WriteTo(w)
}

func (s *Site) handleIndex(w http.ResponseWriter, r *http.Request) {
	s.render(w, r, "index.html", pageData{})
}

func (s *Site) handleInvite(w http.ResponseWriter, r *http.Request) {
	code := strings.ToUpper(chi.URLParam(r, "code"))
	if !invitePattern.MatchString(code) {
		http.NotFound(w, r)
		return
	}
	s.render(w, r, "invite.html", pageData{Code: code})
}

func (s *Site) handleAsset(w http.ResponseWriter, r *http.Request) {
	// Assets aren't fingerprinted, so keep the cache short enough that a
	// redeploy shows up within the hour.
	w.Header().Set("Cache-Control", "public, max-age=3600")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	s.assets.ServeHTTP(w, r)
}

func (s *Site) handleRobots(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	// Invite pages are personal and short-lived; keep them out of search.
	_, _ = w.Write([]byte("User-agent: *\nDisallow: /invite/\nDisallow: /v1/\n"))
}

func (s *Site) handleAssetLinks(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=3600")
	_, _ = w.Write(s.assetLinks)
}

func setPageHeaders(w http.ResponseWriter) {
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("Content-Security-Policy", "default-src 'none'; "+
		"style-src 'self' https://cdn.jsdelivr.net; font-src https://cdn.jsdelivr.net; "+
		"img-src 'self'; script-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
}

// NormalizeFingerprint accepts a SHA-256 certificate fingerprint with or
// without colons, any case, and returns the colon-separated uppercase form
// assetlinks.json requires.
func NormalizeFingerprint(raw string) (string, error) {
	hexDigits := strings.ReplaceAll(strings.TrimSpace(raw), ":", "")
	b, err := hex.DecodeString(hexDigits)
	if err != nil || len(b) != 32 {
		return "", fmt.Errorf("not a SHA-256 fingerprint: %q", raw)
	}
	parts := make([]string, len(b))
	for i, v := range b {
		parts[i] = fmt.Sprintf("%02X", v)
	}
	return strings.Join(parts, ":"), nil
}
