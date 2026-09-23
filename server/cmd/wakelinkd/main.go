// Command wakelinkd is the WakeLink backend: pairing, permissions, and
// FCM-triggered wake pushes. See /docs/self-hosting.md at the repo root
// for how to run your own instance.
package main

import (
	"context"
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
	"wakelink/internal/ratelimit"
)

func main() {
	dbPath := getenv("DB_PATH", "./data/wakelink.db")
	listenAddr := getenv("LISTEN_ADDR", ":8080")
	cooldownSec := getenvInt("RATE_LIMIT_COOLDOWN_SECONDS", 30)
	dailyCap := getenvInt("RATE_LIMIT_DAILY_CAP", 20)
	inviteTTLSec := getenvInt("INVITE_TTL_SECONDS", 600)
	fcmServiceAccountPath := os.Getenv("FCM_SERVICE_ACCOUNT_PATH")
	fcmMock := os.Getenv("FCM_MOCK") == "true"

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

	server := &api.Server{
		DB:  database,
		FCM: sender,
		RateLimit: ratelimit.Limiter{
			Cooldown: time.Duration(cooldownSec) * time.Second,
			DailyCap: dailyCap,
		},
		InviteTTL: time.Duration(inviteTTLSec) * time.Second,
	}

	httpServer := &http.Server{
		Addr:              listenAddr,
		Handler:           server.Router(),
		ReadHeaderTimeout: 5 * time.Second,
	}

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
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Printf("shutdown error: %v", err)
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getenvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}
