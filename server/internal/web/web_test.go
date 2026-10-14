package web

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/go-chi/chi/v5"
)

const testCert = "C3:CC:93:17:E8:8B:60:BC:E7:E1:D5:1C:D2:A3:60:B2:89:94:7D:72:6A:54:B1:52:B5:B8:44:11:13:75:35:71"

func newTestRouter(t *testing.T, cfg Config) http.Handler {
	t.Helper()
	site, err := New(cfg)
	if err != nil {
		t.Fatalf("new site: %v", err)
	}
	r := chi.NewRouter()
	site.Mount(r)
	return r
}

func get(t *testing.T, h http.Handler, path string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec
}

func TestIndexPage(t *testing.T) {
	h := newTestRouter(t, Config{DownloadHref: "/download"})
	rec := get(t, h, "/")
	if rec.Code != http.StatusOK {
		t.Fatalf("index: status %d", rec.Code)
	}
	body := rec.Body.String()
	for _, want := range []string{"WakeLink", `href="/download"`, `og:image" content="https://example.com/assets/og.png"`} {
		if !strings.Contains(body, want) {
			t.Errorf("index missing %q", want)
		}
	}
	if csp := rec.Header().Get("Content-Security-Policy"); !strings.Contains(csp, "script-src 'self'") {
		t.Errorf("unexpected CSP %q", csp)
	}
}

func TestInvitePage(t *testing.T) {
	h := newTestRouter(t, Config{DownloadHref: "/download"})

	rec := get(t, h, "/invite/ab23cd45")
	if rec.Code != http.StatusOK {
		t.Fatalf("invite: status %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "wakelink://invite/AB23CD45") || !strings.Contains(body, ">AB23CD45<") {
		t.Fatalf("invite page should show the upper-cased code and app link")
	}

	for _, bad := range []string{"/invite/%3Cscript%3E", "/invite/AB", "/invite/" + strings.Repeat("A", 17)} {
		if rec := get(t, h, bad); rec.Code != http.StatusNotFound {
			t.Errorf("%s: expected 404, got %d", bad, rec.Code)
		}
	}
}

func TestAssets(t *testing.T) {
	h := newTestRouter(t, Config{})
	for _, path := range []string{"/assets/site.css", "/assets/logo.svg", "/assets/og.png", "/assets/invite.js"} {
		if rec := get(t, h, path); rec.Code != http.StatusOK {
			t.Errorf("%s: status %d", path, rec.Code)
		}
	}
	if rec := get(t, h, "/robots.txt"); !strings.Contains(rec.Body.String(), "Disallow: /invite/") {
		t.Errorf("robots.txt should keep invite pages out of search")
	}
}

func TestAssetLinks(t *testing.T) {
	if rec := get(t, newTestRouter(t, Config{}), "/.well-known/assetlinks.json"); rec.Code != http.StatusNotFound {
		t.Fatalf("without a cert: expected 404, got %d", rec.Code)
	}

	rec := get(t, newTestRouter(t, Config{CertSHA256: testCert}), "/.well-known/assetlinks.json")
	if rec.Code != http.StatusOK || rec.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("assetlinks: status %d type %q", rec.Code, rec.Header().Get("Content-Type"))
	}
	var statements []struct {
		Relation []string `json:"relation"`
		Target   struct {
			Namespace    string   `json:"namespace"`
			PackageName  string   `json:"package_name"`
			Fingerprints []string `json:"sha256_cert_fingerprints"`
		} `json:"target"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &statements); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(statements) != 1 || statements[0].Target.PackageName != AndroidPackage ||
		statements[0].Target.Fingerprints[0] != testCert ||
		statements[0].Relation[0] != "delegate_permission/common.handle_all_urls" {
		t.Fatalf("unexpected statements: %+v", statements)
	}
}

func TestNormalizeFingerprint(t *testing.T) {
	got, err := NormalizeFingerprint("c3cc9317e88b60bce7e1d51cd2a360b289947d726a54b152b5b8441113753571")
	if err != nil || got != testCert {
		t.Fatalf("got %q, %v", got, err)
	}
	if got, err := NormalizeFingerprint(" " + testCert + " "); err != nil || got != testCert {
		t.Fatalf("colon form: got %q, %v", got, err)
	}
	for _, bad := range []string{"", "C3:CC", "zz" + testCert[2:]} {
		if _, err := NormalizeFingerprint(bad); err == nil {
			t.Errorf("%q: expected error", bad)
		}
	}
}
