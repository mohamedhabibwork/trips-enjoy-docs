// Package config loads geolocation-service configuration from the
// GEOLOCATION_SERVICE_* environment variables described in .env.example
// and PLATFORM_BASELINE.md. Required values fail-fast at Load(); optional
// values fall back to safe dev defaults so the binary boots offline.
//
// The set of keys is the union of:
//   - PLATFORM_BASELINE.md §5 (DB / Redis / Kafka / OTel / Keycloak)
//   - docs/services/geolocation-service/TECH.md §1..§2 (vendor API keys,
//     HMAC, ports, log level)
//   - docs/services/geolocation-service/INTEGRATION.md §3 (Kafka topic map)
package config

import (
	"errors"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is the resolved, immutable view of geolocation-service
// configuration. Every field is populated by Load() at process start; the
// struct is passed by value to every package that needs settings.
type Config struct {
	// Platform
	PlatformEnv string // dev | stg | prod

	// Server
	PublicPort string // :8080 mux (default 8085 to match the gateway route table)
	AdminPort  string // :8081 admin mux (default 8086)

	// Database (per docs/architecture/DATABASE_ARCHITECTURE.md:102)
	DBURL      string
	DBUsername string
	DBPassword string

	// Redis (hot cache for geocodes / ETA / routes + chain plan)
	RedisHost     string
	RedisPort     string
	RedisPassword string

	// Kafka (event publisher; optional in dev — falls back to stdout publisher)
	KafkaBootstrapServers string

	// Kafka topic map (per docs/services/geolocation-service/INTEGRATION.md §3)
	TopicGeocoded         string
	TopicEtaComputed      string
	TopicCacheInvalidated string
	TopicProviderChain    string
	TopicProviderHealth   string

	// Keycloak / auth
	KeycloakJWKSURI   string
	KeycloakIssuerURI string

	// Vendor API keys (Vault path in production; raw values only in dev)
	GoogleMapsAPIKey string
	HereAPIKey       string

	// HMAC secret for admin endpoints (per INTEGRATION.md §5.4)
	HMACSecret string

	// OpenTelemetry
	OTLPEndpoint string
	ServiceName  string

	// Cache TTLs (per docs/services/geolocation-service/SRS.md §12.4)
	GeocodeTTL time.Duration
	EtaTTL     time.Duration
	RouteTTL   time.Duration

	// Chain resolver knobs (per SRS.md §12.1)
	ProviderChainCacheTTL time.Duration
}

// Load reads environment variables and returns a Config. Required
// variables (DBURL) must be present; missing them returns an error so
// the binary fails fast per PLATFORM_BASELINE.md §8.
func Load() (Config, error) {
	cfg := Config{
		PlatformEnv:           valueOrDefault(os.Getenv("PLATFORM_ENV"), "dev"),
		PublicPort:            valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_PUBLIC_PORT"), "8085"),
		AdminPort:             valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_ADMIN_PORT"), "8086"),
		DBURL:                 valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_DB_URL"), "postgres://postgres@0.0.0.0:5432/trips_enjoy?sslmode=disable&options=-c%20search_path%3Dgeolocation,public"),
		DBUsername:            valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_DB_USERNAME"), "postgres"),
		DBPassword:            valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_DB_PASSWORD"), ""),
		RedisHost:             valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_REDIS_HOST"), "0.0.0.0"),
		RedisPort:             valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_REDIS_PORT"), "6379"),
		RedisPassword:         os.Getenv("GEOLOCATION_SERVICE_REDIS_PASSWORD"),
		KafkaBootstrapServers: os.Getenv("GEOLOCATION_SERVICE_KAFKA_BOOTSTRAP_SERVERS"),
		TopicGeocoded:         valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_KAFKA_TOPIC_GEOCODED"), "geolocation.geocoded"),
		TopicEtaComputed:      valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_KAFKA_TOPIC_ETA_COMPUTED"), "geolocation.eta.computed"),
		TopicCacheInvalidated: valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_KAFKA_TOPIC_CACHE_INVALIDATED"), "geolocation.cache.invalidated"),
		TopicProviderChain:    valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_KAFKA_TOPIC_PROVIDER_CHAIN"), "geolocation.provider_chain.changed"),
		TopicProviderHealth:   valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_KAFKA_TOPIC_PROVIDER_HEALTH"), "geolocation.provider_health"),
		KeycloakJWKSURI:       os.Getenv("GEOLOCATION_SERVICE_KEYCLOAK_JWKS_URI"),
		KeycloakIssuerURI:     os.Getenv("GEOLOCATION_SERVICE_KEYCLOAK_ISSUER_URI"),
		GoogleMapsAPIKey:      os.Getenv("GEOLOCATION_SERVICE_GOOGLEMAPS_API_KEY"),
		HereAPIKey:            os.Getenv("GEOLOCATION_SERVICE_HERE_API_KEY"),
		HMACSecret:            valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_HMAC_SECRET"), "local-dev-hmac-secret-change-me"),
		OTLPEndpoint:          os.Getenv("GEOLOCATION_SERVICE_OTEL_EXPORTER_OTLP_ENDPOINT"),
		ServiceName:           valueOrDefault(os.Getenv("GEOLOCATION_SERVICE_OTEL_SERVICE_NAME"), "geolocation-service"),
		GeocodeTTL:            durationSecondsOrDefault(os.Getenv("GEOLOCATION_SERVICE_GEOCODE_TTL_SECONDS"), 24*time.Hour),
		EtaTTL:                durationSecondsOrDefault(os.Getenv("GEOLOCATION_SERVICE_ETA_TTL_SECONDS"), 60*time.Second),
		RouteTTL:              durationSecondsOrDefault(os.Getenv("GEOLOCATION_SERVICE_ROUTE_TTL_SECONDS"), 5*time.Minute),
		ProviderChainCacheTTL: durationSecondsOrDefault(os.Getenv("GEOLOCATION_SERVICE_PROVIDER_CHAIN_CACHE_TTL_SECONDS"), 60*time.Second),
	}

	if cfg.DBURL == "" {
		return Config{}, errors.New("GEOLOCATION_SERVICE_DB_URL is required (see .env.example)")
	}
	return cfg, nil
}

func valueOrDefault(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func durationSecondsOrDefault(value string, fallback time.Duration) time.Duration {
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return time.Duration(parsed) * time.Second
}
