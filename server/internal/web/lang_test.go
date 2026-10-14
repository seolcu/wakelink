package web

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNegotiateLanguage(t *testing.T) {
	cases := map[string]string{
		"":                                 "en",
		"ko-KR,ko;q=0.9,en-US;q=0.8":       "ko",
		"en-US,en;q=0.9,ko;q=0.8":          "en",
		"ja-JP,ja;q=0.9,ko;q=0.8,en;q=0.7": "ko",
		"fr-FR,fr;q=0.9":                   "en",
		"en;q=0.5, ko;q=0.9":               "ko",
		"KO":                               "ko",
		"ko;q=0, en;q=0.1":                 "en",
		"*":                                "en",
	}
	for header, want := range cases {
		if got := negotiateLanguage(header); got != want {
			t.Errorf("negotiateLanguage(%q) = %q, want %q", header, got, want)
		}
	}
}

func getWith(t *testing.T, h http.Handler, path, acceptLanguage string, cookie *http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if acceptLanguage != "" {
		req.Header.Set("Accept-Language", acceptLanguage)
	}
	if cookie != nil {
		req.AddCookie(cookie)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestPagesFollowBrowserLanguage(t *testing.T) {
	h := newTestRouter(t, Config{DownloadHref: "/download"})

	ko := getWith(t, h, "/", "ko-KR,ko;q=0.9", nil)
	if !strings.Contains(ko.Body.String(), `<html lang="ko">`) || ko.Header().Get("Content-Language") != "ko" {
		t.Fatalf("Korean browser should get the Korean page")
	}
	en := getWith(t, h, "/", "de-DE", nil)
	if !strings.Contains(en.Body.String(), `<html lang="en">`) || !strings.Contains(en.Body.String(), "og-en.png") {
		t.Fatalf("other languages should fall back to English")
	}
	if vary := en.Header().Get("Vary"); !strings.Contains(vary, "Accept-Language") || !strings.Contains(vary, "Cookie") {
		t.Fatalf("language-dependent pages must Vary on Accept-Language and Cookie, got %q", vary)
	}
	if en.Header().Get("Set-Cookie") != "" {
		t.Fatalf("negotiation alone should not set a cookie")
	}

	invite := getWith(t, h, "/invite/AB23CD45", "ko", nil)
	if !strings.Contains(invite.Body.String(), "초대 코드") {
		t.Fatalf("invite page should be localized too")
	}
}

func TestExplicitLanguageChoiceIsRemembered(t *testing.T) {
	h := newTestRouter(t, Config{DownloadHref: "/download"})

	rec := getWith(t, h, "/?lang=en", "ko-KR", nil)
	if !strings.Contains(rec.Body.String(), `<html lang="en">`) {
		t.Fatalf("?lang=en should beat Accept-Language")
	}
	var cookie *http.Cookie
	for _, c := range rec.Result().Cookies() {
		if c.Name == langCookie {
			cookie = c
		}
	}
	if cookie == nil || cookie.Value != "en" {
		t.Fatalf("?lang=en should be remembered in a cookie")
	}

	// Later visits (and other pages) keep it without the query.
	rec = getWith(t, h, "/invite/AB23CD45", "ko-KR", cookie)
	if !strings.Contains(rec.Body.String(), `<html lang="en">`) {
		t.Fatalf("remembered language should beat Accept-Language")
	}

	if rec := getWith(t, h, "/?lang=xx", "ko-KR", nil); !strings.Contains(rec.Body.String(), `<html lang="ko">`) {
		t.Fatalf("unsupported ?lang should be ignored")
	}
}

func TestLanguageSwitcherLinksToOtherLanguage(t *testing.T) {
	h := newTestRouter(t, Config{})
	if body := getWith(t, h, "/invite/AB23CD45", "ko", nil).Body.String(); !strings.Contains(body, `href="/invite/AB23CD45?lang=en"`) {
		t.Fatalf("Korean page should link to its English version")
	}
	if body := getWith(t, h, "/", "en", nil).Body.String(); !strings.Contains(body, `href="/?lang=ko"`) {
		t.Fatalf("English page should link to its Korean version")
	}
}
