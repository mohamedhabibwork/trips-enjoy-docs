package gateway

import (
	"testing"
	"time"
)

func TestLoadConfigRequiresKafka(t *testing.T) {
	t.Setenv("API_GATEWAY_KEYCLOAK_ISSUER_URLS", "https://kc.example.com/realms/customer")
	t.Setenv("API_GATEWAY_REDIS_ADDR", "localhost:6379")
	t.Setenv("API_GATEWAY_KAFKA_BOOTSTRAP_SERVERS", "")
	if _, err := LoadConfig(); err == nil {
		t.Fatal("expected error from missing Kafka")
	}
}

func TestLoadConfigDefaultsKeycloakWhenUnset(t *testing.T) {
	t.Setenv("API_GATEWAY_KEYCLOAK_ISSUER_URLS", "")
	t.Setenv("API_GATEWAY_KEYCLOAK_BASE_URL", "")
	t.Setenv("API_GATEWAY_KAFKA_BOOTSTRAP_SERVERS", "broker1:9092")
	t.Setenv("API_GATEWAY_REDIS_ADDR", "redis:6379")
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("load config: %v", err)
	}
	if got, want := len(cfg.Keycloak.IssuerURLs), len(defaultKeycloakRealms); got != want {
		t.Fatalf("issuer count = %d, want %d", got, want)
	}
	for i, realm := range defaultKeycloakRealms {
		want := defaultKeycloakBaseURL + "/realms/" + realm
		if cfg.Keycloak.IssuerURLs[i] != want {
			t.Errorf("issuer[%d] = %q, want %q", i, cfg.Keycloak.IssuerURLs[i], want)
		}
	}
}

func TestLoadConfigDefaultsRedisWhenUnset(t *testing.T) {
	t.Setenv("API_GATEWAY_KEYCLOAK_ISSUER_URLS", "https://kc.example.com/realms/customer")
	t.Setenv("API_GATEWAY_REDIS_ADDR", "")
	t.Setenv("API_GATEWAY_KAFKA_BOOTSTRAP_SERVERS", "broker1:9092")
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("load config: %v", err)
	}
	if cfg.Redis.Addr != defaultRedisAddr {
		t.Errorf("redis addr = %q, want %q", cfg.Redis.Addr, defaultRedisAddr)
	}
}

func TestLoadConfigKeycloakBaseURLOverride(t *testing.T) {
	t.Setenv("API_GATEWAY_KEYCLOAK_ISSUER_URLS", "")
	t.Setenv("API_GATEWAY_KEYCLOAK_BASE_URL", "https://kc.staging.example.com")
	t.Setenv("API_GATEWAY_KAFKA_BOOTSTRAP_SERVERS", "broker1:9092")
	t.Setenv("API_GATEWAY_REDIS_ADDR", "redis:6379")
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("load config: %v", err)
	}
	if cfg.Keycloak.IssuerURLs[0] != "https://kc.staging.example.com/realms/platform-customer" {
		t.Errorf("issuer[0] = %q", cfg.Keycloak.IssuerURLs[0])
	}
}

func TestLoadConfigSuccess(t *testing.T) {
	t.Setenv("API_GATEWAY_PORT", "8080")
	t.Setenv("API_GATEWAY_KEYCLOAK_ISSUER_URLS", "https://kc.example.com/realms/customer,https://kc.example.com/realms/driver")
	t.Setenv("API_GATEWAY_KAFKA_BOOTSTRAP_SERVERS", "broker1:9092,broker2:9092")
	t.Setenv("API_GATEWAY_REDIS_ADDR", "redis:6379")
	t.Setenv("API_GATEWAY_BODY_MAX_BYTES", "")
	t.Setenv("API_GATEWAY_UPSTREAM_TIMEOUT", "45s")
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("load config: %v", err)
	}
	if cfg.Port != "8080" {
		t.Errorf("port = %q", cfg.Port)
	}
	if len(cfg.Keycloak.IssuerURLs) != 2 {
		t.Errorf("issuers = %d", len(cfg.Keycloak.IssuerURLs))
	}
	if cfg.UpstreamTimeout != 45*time.Second {
		t.Errorf("upstream timeout = %v", cfg.UpstreamTimeout)
	}
}

func TestDefaultRoutesCoverEveryDownstreamService(t *testing.T) {
	want := map[string]bool{
		"admin-service": false, "audit-service": false, "chat-service": false,
		"configuration-service": false, "courier-service": false, "customer-service": false,
		"driver-service": false, "file-service": false, "food-order-service": false,
		"fraud-risk-service": false, "geolocation-service": false, "identity-service": false,
		"ledger-service": false, "notification-service": false, "payment-service": false,
		"pricing-service": false, "reporting-service": false, "restaurant-service": false,
		"search-service": false, "trip-service": false,
	}
	for _, r := range defaultRoutes() {
		if _, ok := want[r.Service]; ok {
			want[r.Service] = true
		}
	}
	for svc, found := range want {
		if !found {
			t.Errorf("missing route for %s", svc)
		}
	}
}

func TestUpstreamEnvOverride(t *testing.T) {
	t.Setenv("PAYMENT_SERVICE_UPSTREAM_URL", "http://payment.internal:9000")
	for _, r := range defaultRoutes() {
		if r.Service == "payment-service" {
			if r.Upstream != "http://payment.internal:9000" {
				t.Fatalf("payment upstream = %q", r.Upstream)
			}
			return
		}
	}
	t.Fatal("payment-service route not found")
}
