// Package db is the geolocation-service PostgreSQL data layer.
// Connect() returns a pgxpool.Pool wired with search_path=geolocation,public
// so every unqualified table lookup resolves against the geolocation
// schema first. The production wiring also runs golang-migrate at boot
// (cmd/server/main.go); this scaffold defers that so the binary is
// runnable without a local DB.
package db

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Config is the slice of db settings the package needs.
type Config struct {
	URL      string
	Username string
	Password string
}

// Connect opens a pgxpool with a 5s connect-timeout and a small max
// conns cap. If the URL is unreachable we return a nil pool + a clear
// error so main can decide whether to abort or boot in degraded mode
// (per PLATFORM_BASELINE.md §8; this service degrades gracefully when
// the cache layer is offline because the in-memory cache still
// answers hot reads).
func Connect(ctx context.Context, cfg Config) (*pgxpool.Pool, error) {
	if cfg.URL == "" {
		return nil, errors.New("db: empty URL")
	}
	poolCfg, err := pgxpool.ParseConfig(cfg.URL)
	if err != nil {
		return nil, fmt.Errorf("db: parse config: %w", err)
	}
	if cfg.Username != "" {
		poolCfg.ConnConfig.User = cfg.Username
	}
	if cfg.Password != "" {
		poolCfg.ConnConfig.Password = cfg.Password
	}
	poolCfg.MaxConns = 10
	poolCfg.MinConns = 1
	poolCfg.MaxConnLifetime = 30 * time.Minute
	poolCfg.MaxConnIdleTime = 5 * time.Minute

	connectCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	pool, err := pgxpool.NewWithConfig(connectCtx, poolCfg)
	if err != nil {
		return nil, fmt.Errorf("db: connect: %w", err)
	}
	if err := pool.Ping(connectCtx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("db: ping: %w", err)
	}
	return pool, nil
}
