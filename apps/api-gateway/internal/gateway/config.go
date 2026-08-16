// Package gateway implements the platform's api-gateway. It is the
// single north-south edge for every external client: it terminates
// TLS, validates Keycloak JWTs, translates claims to X-User-*
// headers, applies per-route rate limits, routes to downstream
// services, emits audit events, and exposes an internal admin port
// for operational reloads.
//
// This file defines the gateway's configuration model. Every value
// is loadable from environment variables following the platform
// `<SVC>_*` convention (per docs/shared/PLATFORM_BASELINE.md §11).
// The legacy `PORT`, `CORS_ALLOWED_ORIGINS`, `GATEWAY_LOCAL_DEVELOPMENT`,
// and `UPSTREAM_<SVC>_URL` names are accepted as a one-release
// deprecation alias.
package gateway

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Default values aligned with docs/services/api-gateway/README.md §13
// and SRS §13 (gateway.* keys). The Keycloak + Redis defaults are
// pointed at the local docker-compose dev cluster; production
// deployments override via Vault (see .env.example header).
const (
	defaultPort                    = "8080"
	defaultAdminPort               = "8081"
	defaultAdminBind               = "0.0.0.0"
	defaultBodyMaxBytes            = 1 << 20 // 1 MiB
	defaultUpstreamTimeout         = 30 * time.Second
	defaultRedisAddr               = "127.0.0.1:6379"
	defaultRedisTimeout            = 100 * time.Millisecond
	defaultKafkaTimeout            = 5 * time.Second
	defaultJWKSRefresh             = 5 * time.Minute
	defaultBulkheadSize            = 1024
	defaultCircuitThreshold        = 5
	defaultCircuitCooldown         = 30 * time.Second
	defaultAuditSampleRatio        = 1.0
	defaultRateLimitWindow         = 60 // seconds
	defaultRateLimitMax            = 100
	defaultRevocationTTL           = 30 * 24 * time.Hour
	defaultRevokedJTIKeyTpl        = "gateway:revoked:jti:%s"
	defaultSuspendedSubKey         = "gateway:revoked:sub:%s"
	defaultJWKSKeyTpl              = "gateway:jwks:%s"
	defaultRateLimitKeyTpl         = "gateway:rl:%s:%s:%d"
	defaultBlockedIPKeyTpl         = "gateway:blocks:ip:%s"
	defaultDownstreamProbeTimeout  = 2 * time.Second
	defaultDownstreamProbeParallel = 8
)

// defaultKeycloakBaseURL is the local development Keycloak base URL
// used when API_GATEWAY_KEYCLOAK_ISSUER_URLS is not set. The
// authoritative realm list is the six platform realms declared in
// docs/architecture/IDENTITY_MODEL.md §3.
const defaultKeycloakBaseURL = "http://0.0.0.0:8181"

// defaultKeycloakRealms is the canonical 6-realm catalog used when
// the operator does not override API_GATEWAY_KEYCLOAK_ISSUER_URLS.
// Order matches docs/services/api-gateway/SRS.md §3.1.
var defaultKeycloakRealms = []string{
	"platform-customer",
	"platform-driver",
	"platform-courier",
	"platform-staff",
	"platform-internal",
	"platform-services",
}

// Route declares a public path prefix and the service that owns it.
// Production deployments replace these defaults with the route table
// supplied by configuration-service (`gateway.routes` key per
// README §13) and delivered via `configuration.updated.v1`.
type Route struct {
	Prefix         string
	Service        string
	Upstream       string
	RequiredRoles  []string
	AllowAnonymous bool
}

// Config is the full gateway configuration. Fields are read from
// environment variables at startup and immutable thereafter; runtime
// updates flow through the configuration-service consumer and the
// in-process atomic Snapshot (see config_snapshot.go).
type Config struct {
	// Public mux
	Port               string
	AllowedCORSOrigins []string
	BodyMaxBytes       int64

	// Admin mux (binds AdminBind, default 0.0.0.0)
	AdminPort  string
	AdminBind  string
	AdminToken string // bearer for admin endpoints; falls back to JWT verification

	// Routing
	Routes []Route

	// Keycloak / JWT
	Keycloak KeycloakConfig

	// Redis
	Redis RedisConfig

	// Kafka
	Kafka KafkaConfig

	// OpenTelemetry
	Telemetry TelemetryConfig

	// Isolation defaults
	UpstreamTimeout      time.Duration
	BulkheadSize         int
	CircuitFailureThresh uint32
	CircuitCooldown      time.Duration

	// Aggregated downstream-health endpoint (public /healthz/downstream)
	DownstreamProbeTimeout  time.Duration
	DownstreamProbeParallel int

	// Auditing
	AuditSampleRatio float64
}

// KeycloakConfig groups JWT verification configuration. The gateway
// validates every bearer JWT against Keycloak's JWKS; allowed
// issuers and audiences are configured via `gateway.jwt.*` keys
// per README §13.
type KeycloakConfig struct {
	// Issuer URL (e.g. https://keycloak.cloud.habib.cloud/realms/platform-customer).
	// Multiple realms are supported via comma-separated values; the
	// gateway builds one oidc provider per realm.
	IssuerURLs []string
	// JWKSRefresh is how often the local JWKS cache is refreshed.
	JWKSRefresh time.Duration
	// CacheTTL is how long the in-process JWKS is reused.
	CacheTTL time.Duration
}

// RedisConfig is the Redis connection config. The shared Redis
// cluster is keyed under a logical DB index with the `gateway:`
// prefix per ERD.md §5.
type RedisConfig struct {
	Addr     string
	Password string
	DB       int
	Timeout  time.Duration
}

// KafkaConfig drives the audit + downstream event consumer and
// producer. Both roles share the same cluster.
type KafkaConfig struct {
	Brokers            []string
	AuditTopic         string
	AuditDLQTopic      string
	IdentityTopic      string
	ConfigurationTopic string
	ConsumerGroup      string
	RequestTimeout     time.Duration
}

// TelemetryConfig holds OpenTelemetry resource attributes and the
// OTLP endpoint. Defaults are safe for development (stdout exporter).
type TelemetryConfig struct {
	ServiceName    string
	ServiceVersion string
	Environment    string
	Region         string
	OTLPEndpoint   string // empty = stdout exporter
	SampleRatio    float64
}

// LoadConfig reads the gateway configuration from environment
// variables. It returns an error only for hard failures (missing
// production-required values); it fills optional fields with
// sensible defaults.
func LoadConfig() (Config, error) {
	c := Config{
		Port:                    valueOrDefault(getenv("API_GATEWAY_PORT", "PORT"), defaultPort),
		AllowedCORSOrigins:      splitCSV(getenv("API_GATEWAY_CORS_ALLOWED_ORIGINS", "CORS_ALLOWED_ORIGINS")),
		BodyMaxBytes:            int64Env(getenv("API_GATEWAY_BODY_MAX_BYTES", ""), defaultBodyMaxBytes),
		AdminPort:               valueOrDefault(getenv("API_GATEWAY_ADMIN_PORT", ""), defaultAdminPort),
		AdminBind:               valueOrDefault(getenv("API_GATEWAY_ADMIN_BIND", ""), defaultAdminBind),
		AdminToken:              os.Getenv("API_GATEWAY_ADMIN_TOKEN"),
		Routes:                  defaultRoutes(),
		UpstreamTimeout:         durationEnv(os.Getenv("API_GATEWAY_UPSTREAM_TIMEOUT"), defaultUpstreamTimeout),
		BulkheadSize:            intEnv(os.Getenv("API_GATEWAY_BULKHEAD_SIZE"), defaultBulkheadSize),
		CircuitFailureThresh:    uint32(intEnv(os.Getenv("API_GATEWAY_CIRCUIT_THRESHOLD"), defaultCircuitThreshold)),
		CircuitCooldown:         durationEnv(os.Getenv("API_GATEWAY_CIRCUIT_COOLDOWN"), defaultCircuitCooldown),
		DownstreamProbeTimeout:  durationEnv(os.Getenv("API_GATEWAY_DOWNSTREAM_PROBE_TIMEOUT"), defaultDownstreamProbeTimeout),
		DownstreamProbeParallel: intEnv(os.Getenv("API_GATEWAY_DOWNSTREAM_PROBE_PARALLEL"), defaultDownstreamProbeParallel),
		AuditSampleRatio:        floatEnv(os.Getenv("API_GATEWAY_AUDIT_SAMPLE_RATIO"), defaultAuditSampleRatio),
		Keycloak: KeycloakConfig{
			IssuerURLs:  defaultKeycloakIssuers(),
			JWKSRefresh: durationEnv(os.Getenv("API_GATEWAY_KEYCLOAK_JWKS_REFRESH"), defaultJWKSRefresh),
			CacheTTL:    durationEnv(os.Getenv("API_GATEWAY_KEYCLOAK_CACHE_TTL"), defaultJWKSRefresh),
		},
		Redis: RedisConfig{
			Addr:     valueOrDefault(getenv("API_GATEWAY_REDIS_ADDR", "API_GATEWAY_REDIS_URL"), defaultRedisAddr),
			Password: os.Getenv("API_GATEWAY_REDIS_PASSWORD"),
			DB:       intEnv(os.Getenv("API_GATEWAY_REDIS_DB"), 0),
			Timeout:  durationEnv(os.Getenv("API_GATEWAY_REDIS_TIMEOUT"), defaultRedisTimeout),
		},
		Kafka: KafkaConfig{
			Brokers:            splitCSV(getenv("API_GATEWAY_KAFKA_BOOTSTRAP_SERVERS", "API_GATEWAY_KAFKA_BROKERS")),
			AuditTopic:         valueOrDefault(os.Getenv("API_GATEWAY_KAFKA_AUDIT_TOPIC"), "audit.api.request"),
			AuditDLQTopic:      valueOrDefault(os.Getenv("API_GATEWAY_KAFKA_AUDIT_DLQ_TOPIC"), "audit.api.request.dlq"),
			IdentityTopic:      valueOrDefault(os.Getenv("API_GATEWAY_KAFKA_IDENTITY_TOPIC"), "identity.events"),
			ConfigurationTopic: valueOrDefault(os.Getenv("API_GATEWAY_KAFKA_CONFIGURATION_TOPIC"), "configuration.events"),
			ConsumerGroup:      valueOrDefault(os.Getenv("API_GATEWAY_KAFKA_CONSUMER_GROUP"), "api-gateway"),
			RequestTimeout:     durationEnv(os.Getenv("API_GATEWAY_KAFKA_REQUEST_TIMEOUT"), defaultKafkaTimeout),
		},
		Telemetry: TelemetryConfig{
			ServiceName:    valueOrDefault(os.Getenv("API_GATEWAY_OTEL_SERVICE_NAME"), "api-gateway"),
			ServiceVersion: valueOrDefault(os.Getenv("API_GATEWAY_OTEL_SERVICE_VERSION"), "0.1.0"),
			Environment:    valueOrDefault(os.Getenv("API_GATEWAY_OTEL_ENVIRONMENT"), "dev"),
			Region:         valueOrDefault(os.Getenv("API_GATEWAY_OTEL_REGION"), "local"),
			OTLPEndpoint:   os.Getenv("API_GATEWAY_OTEL_EXPORTER_OTLP_ENDPOINT"),
			SampleRatio:    floatEnv(os.Getenv("API_GATEWAY_OTEL_SAMPLE_RATIO"), 1.0),
		},
	}
	if len(c.Keycloak.IssuerURLs) == 0 {
		return c, fmt.Errorf("at least one Keycloak issuer URL is required (API_GATEWAY_KEYCLOAK_ISSUER_URLS)")
	}
	if len(c.Kafka.Brokers) == 0 {
		return c, fmt.Errorf("at least one Kafka broker is required (API_GATEWAY_KAFKA_BOOTSTRAP_SERVERS)")
	}
	return c, nil
}

// defaultRoutes is the canonical fallback route table. The 20
// service catalog follows ADR-0017; per-service upstream URLs come
// from the `<SERVICE>_UPSTREAM_URL` environment variables.
func defaultRoutes() []Route {
	defs := []struct {
		prefix   string
		service  string
		fallback string
		roles    []string
		anon     bool
	}{
		{"/v1/admin", "admin-service", "http://admin-service:8080", []string{"api_gateway.admin", "platform.admin", "platform.engineering"}, false},
		{"/v1/addresses", "customer-service", "http://customer-service:8080", nil, false},
		{"/v1/audit", "audit-service", "http://audit-service:8080", []string{"api_gateway.admin", "platform.admin", "platform.engineering"}, false},
		{"/v1/chat", "chat-service", "http://chat-service:8080", nil, false},
		{"/v1/configurations", "configuration-service", "http://configuration-service:8080", []string{"platform.admin", "platform.engineering"}, false},
		{"/v1/couriers", "courier-service", "http://courier-service:8080", nil, false},
		{"/v1/customers", "customer-service", "http://customer-service:8080", nil, false},
		{"/v1/deliveries", "courier-service", "http://courier-service:8080", nil, false},
		{"/v1/drivers", "driver-service", "http://driver-service:8080", nil, false},
		{"/v1/files", "file-service", "http://file-service:8080", nil, false},
		{"/v1/fraud", "fraud-risk-service", "http://fraud-risk-service:8080", []string{"fraud_risk.admin", "platform.admin"}, false},
		{"/v1/geolocation", "geolocation-service", "http://geolocation-service:8080", nil, false},
		{"/v1/identities", "identity-service", "http://identity-service:8080", nil, false},
		{"/v1/ledger", "ledger-service", "http://ledger-service:8080", []string{"ledger.admin", "platform.admin", "platform.finance", "platform.engineering"}, false},
		{"/v1/notifications", "notification-service", "http://notification-service:8080", nil, false},
		{"/v1/orders", "food-order-service", "http://food-order-service:8080", nil, false},
		{"/v1/payments", "payment-service", "http://payment-service:8080", nil, false},
		{"/v1/pricing", "pricing-service", "http://pricing-service:8080", []string{"pricing.admin", "platform.admin"}, false},
		{"/v1/reports", "reporting-service", "http://reporting-service:8080", []string{"reporting.admin", "platform.admin", "platform.data_eng"}, false},
		{"/v1/restaurants", "restaurant-service", "http://restaurant-service:8080", nil, true},
		{"/v1/rides", "trip-service", "http://trip-service:8080", nil, false},
		{"/v1/search", "search-service", "http://search-service:8080", nil, true},
		{"/v1/support", "admin-service", "http://admin-service:8080", []string{"platform.support", "platform.admin"}, false},
		{"/v1/trips", "trip-service", "http://trip-service:8080", nil, false},
		{"/v1/vehicles", "driver-service", "http://driver-service:8080", nil, false},
	}
	out := make([]Route, 0, len(defs))
	for _, d := range defs {
		out = append(out, Route{
			Prefix:         d.prefix,
			Service:        d.service,
			Upstream:       valueOrDefault(os.Getenv(strings.ToUpper(strings.ReplaceAll(d.service, "-", "_"))+"_UPSTREAM_URL"), d.fallback),
			RequiredRoles:  d.roles,
			AllowAnonymous: d.anon,
		})
	}
	return out
}

func getenv(primary, fallback string) string {
	if v := os.Getenv(primary); v != "" {
		return v
	}
	return os.Getenv(fallback)
}

func valueOrDefault(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func intEnv(value string, fallback int) int {
	if value == "" {
		return fallback
	}
	if v, err := strconv.Atoi(value); err == nil {
		return v
	}
	return fallback
}

func int64Env(value string, fallback int64) int64 {
	if value == "" {
		return fallback
	}
	if v, err := strconv.ParseInt(value, 10, 64); err == nil {
		return v
	}
	return fallback
}

func durationEnv(value string, fallback time.Duration) time.Duration {
	if value == "" {
		return fallback
	}
	if d, err := time.ParseDuration(value); err == nil {
		return d
	}
	return fallback
}

func floatEnv(value string, fallback float64) float64 {
	if value == "" {
		return fallback
	}
	if v, err := strconv.ParseFloat(value, 64); err == nil {
		return v
	}
	return fallback
}

func splitCSV(value string) []string {
	if value == "" {
		return nil
	}
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

// defaultKeycloakIssuers resolves the Keycloak issuer list with the
// following precedence:
//
//  1. API_GATEWAY_KEYCLOAK_ISSUER_URLS / API_GATEWAY_KEYCLOAK_ISSUER_URL
//     (comma-separated; explicit operator override)
//  2. API_GATEWAY_KEYCLOAK_BASE_URL + the 6 canonical realms (per
//     docs/architecture/IDENTITY_MODEL.md §3)
//  3. The local development default (defaultKeycloakBaseURL + the
//     6 canonical realms); production deployments MUST override
//     API_GATEWAY_KEYCLOAK_ISSUER_URLS via Vault.
func defaultKeycloakIssuers() []string {
	if explicit := splitCSV(getenv("API_GATEWAY_KEYCLOAK_ISSUER_URLS", "API_GATEWAY_KEYCLOAK_ISSUER_URL")); len(explicit) > 0 {
		return explicit
	}
	base := valueOrDefault(os.Getenv("API_GATEWAY_KEYCLOAK_BASE_URL"), defaultKeycloakBaseURL)
	base = strings.TrimRight(base, "/")
	urls := make([]string, 0, len(defaultKeycloakRealms))
	for _, realm := range defaultKeycloakRealms {
		urls = append(urls, base+"/realms/"+realm)
	}
	return urls
}
