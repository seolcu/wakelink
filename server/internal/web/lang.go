package web

import (
	"net/http"
	"sort"
	"strconv"
	"strings"
)

// Languages the site is written in. Visitors whose browser prefers neither
// get English, the more widely understood fallback.
const (
	langKorean   = "ko"
	langEnglish  = "en"
	fallbackLang = langEnglish

	langCookie = "lang"
	langParam  = "lang"
)

func supportedLang(tag string) bool { return tag == langKorean || tag == langEnglish }

// pickLanguage decides which language to render, most explicit signal
// first: a ?lang= link (e.g. the language switcher, or a shared English
// link), then the remembered choice from a previous switch, then the
// browser's Accept-Language. It reports whether the choice came from the
// query, so the caller can remember it.
func pickLanguage(r *http.Request) (lang string, fromQuery bool) {
	if q := strings.ToLower(r.URL.Query().Get(langParam)); supportedLang(q) {
		return q, true
	}
	if c, err := r.Cookie(langCookie); err == nil && supportedLang(c.Value) {
		return c.Value, false
	}
	return negotiateLanguage(r.Header.Get("Accept-Language")), false
}

// negotiateLanguage returns the supported language the Accept-Language
// header ranks highest (by q-value, then order), matching on the primary
// subtag so "ko-KR" counts as "ko" and "en-GB" as "en".
func negotiateLanguage(header string) string {
	type pref struct {
		tag string
		q   float64
	}
	var prefs []pref
	for _, part := range strings.Split(header, ",") {
		fields := strings.Split(strings.TrimSpace(part), ";")
		tag := strings.ToLower(strings.TrimSpace(fields[0]))
		if tag == "" {
			continue
		}
		q := 1.0
		for _, f := range fields[1:] {
			if v, ok := strings.CutPrefix(strings.TrimSpace(f), "q="); ok {
				if parsed, err := strconv.ParseFloat(v, 64); err == nil {
					q = parsed
				}
			}
		}
		if q <= 0 {
			continue
		}
		primary, _, _ := strings.Cut(tag, "-")
		prefs = append(prefs, pref{tag: primary, q: q})
	}
	sort.SliceStable(prefs, func(a, b int) bool { return prefs[a].q > prefs[b].q })
	for _, p := range prefs {
		if supportedLang(p.tag) {
			return p.tag
		}
	}
	return fallbackLang
}

// rememberLanguage stores an explicit ?lang= choice so later visits (and
// the invite page) keep it without the query string.
func rememberLanguage(w http.ResponseWriter, lang string) {
	http.SetCookie(w, &http.Cookie{
		Name:     langCookie,
		Value:    lang,
		Path:     "/",
		MaxAge:   365 * 24 * 60 * 60,
		SameSite: http.SameSiteLaxMode,
		HttpOnly: true,
	})
}
