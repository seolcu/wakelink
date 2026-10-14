// Command wakelinkd is the WakeLink backend: pairing, permissions, and
// FCM-triggered wake pushes. See /docs/self-hosting.md at the repo root
// for how to run your own instance.
package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"wakelink/internal/api"
	"wakelink/internal/db"
	"wakelink/internal/fcm"
	"wakelink/internal/metrics"
	"wakelink/internal/ratelimit"
	"wakelink/internal/web"
)

func main() {
	dbPath := getenv("DB_PATH", "./data/wakelink.db")
	listenAddr := getenv("LISTEN_ADDR", ":8080")
	cooldownSec, err := getenvInt("RATE_LIMIT_COOLDOWN_SECONDS", 30)
	if err != nil {
		log.Fatal(err)
	}
	dailyCap, err := getenvInt("RATE_LIMIT_DAILY_CAP", 20)
	if err != nil {
		log.Fatal(err)
	}
	inviteTTLSec, err := getenvInt("INVITE_TTL_SECONDS", 600)
	if err != nil {
		log.Fatal(err)
	}
	if cooldownSec < 0 {
		log.Fatalf("RATE_LIMIT_COOLDOWN_SECONDS must be >= 0, got %d", cooldownSec)
	}
	if dailyCap < 1 {
		log.Fatalf("RATE_LIMIT_DAILY_CAP must be >= 1, got %d", dailyCap)
	}
	if inviteTTLSec < 1 {
		log.Fatalf("INVITE_TTL_SECONDS must be >= 1, got %d", inviteTTLSec)
	}
	fcmServiceAccountPath := os.Getenv("FCM_SERVICE_ACCOUNT_PATH")
	fcmMock := os.Getenv("FCM_MOCK") == "true"
	downloadURL := getenv("APK_DOWNLOAD_URL", "https://github.com/seolcu/wakelink/releases/latest/download/wakelink.apk")
	if downloadURL == "-" {
		downloadURL = ""
	}
	// SHA-256 of the release signing certificate, published in
	// /.well-known/assetlinks.json so the app can open https invite links.
	certSHA256 := getenv("ANDROID_CERT_SHA256", "C3:CC:93:17:E8:8B:60:BC:E7:E1:D5:1C:D2:A3:60:B2:89:94:7D:72:6A:54:B1:52:B5:B8:44:11:13:75:35:71")
	if certSHA256 == "-" {
		certSHA256 = ""
	} else if certSHA256, err = web.NormalizeFingerprint(certSHA256); err != nil {
		log.Fatalf("ANDROID_CERT_SHA256: %v", err)
	}

	database, err := db.Open(dbPath)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	if err := database.Migrate(); err != nil {
		log.Fatalf("migrate db: %v", err)
	}

	var sender fcm.Sender
	if fcmMock {
		log.Println("FCM_MOCK=true: using mock FCM sender, no real pushes will be sent")
		sender = fcm.NewMockSender()
	} else {
		if fcmServiceAccountPath == "" {
			log.Fatal("FCM_SERVICE_ACCOUNT_PATH is required unless FCM_MOCK=true")
		}
		realSender, err := fcm.NewFirebaseSender(context.Background(), fcmServiceAccountPath)
		if err != nil {
			log.Fatalf("init fcm sender: %v", err)
		}
		sender = realSender
	}

	downloadHref := "/download"
	if downloadURL == "" {
		downloadHref = "https://github.com/seolcu/wakelink/releases/latest"
	}
	site, err := web.New(web.Config{DownloadHref: downloadHref, CertSHA256: certSHA256})
	if err != nil {
		log.Fatalf("load web site: %v", err)
	}

	server := &api.Server{
		DB:  database,
		FCM: sender,
		RateLimit: ratelimit.Limiter{
			Cooldown: time.Duration(cooldownSec) * time.Second,
			DailyCap: dailyCap,
		},
		InviteTTL:   time.Duration(inviteTTLSec) * time.Second,
		Metrics:     &metrics.Counters{},
		Version:     "dev",
		StartedAt:   time.Now(),
		DownloadURL: downloadURL,
		Site:        site,
	}

	httpServer := &http.Server{
		Addr:              listenAddr,
		Handler:           server.Router(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	cleanupCtx, stopCleanup := context.WithCancel(context.Background())
	defer stopCleanup()
	go purgeExpiredInvites(cleanupCtx, database, time.Hour)

	go func() {
		log.Printf("wakelinkd listening on %s", listenAddr)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("http server: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	log.Println("shutting down...")
	stopCleanup()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Printf("shutdown error: %v", err)
	}
	if err := database.Close(); err != nil {
		log.Printf("close db: %v", err)
	}
}

// purgeExpiredInvites deletes dead invite codes once at startup and then
// every interval, until ctx is cancelled.
func purgeExpiredInvites(ctx context.Context, database *db.DB, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		if n, err := database.DeleteExpiredInvites(ctx, time.Now()); err != nil {
			log.Printf("purge expired invites: %v", err)
		} else if n > 0 {
			log.Printf("purged %d expired invite(s)", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getenvInt(key string, fallback int) (int, error) {
	v := os.Getenv(key)
	if v == "" {
		return fallback, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return 0, fmt.Errorf("%s: invalid integer %q: %w", key, v, err)
	}
	return n, nil
}
