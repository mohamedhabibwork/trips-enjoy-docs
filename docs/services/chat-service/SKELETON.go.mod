// SKELETON ONLY — documentation, not a working build.
// See ../TECH.md for the platform-default setup.
// Full OSS catalogue (SPDX IDs, license URLs, NOTICE generation) is in
// ../../shared/OSS_DEPENDENCIES.md
//
// Service: chat-service
// Profile: Edge / hot path (Go)
// Language: Go (`net/http` + `chi` + `coder/websocket` for the WebSocket transport)
//
// To turn this into a runnable module:
//   1. Copy the platform go.mod template (defines the toolchain directive).
//   2. Replace the placeholder versions below with the resolved versions
//      from ../RECOMMENDATIONS.md 5.1.
//   3. Either keep the platform-internal packages (recommended for
//      in-platform run) or remove them and add the equivalent packages
//      individually for a standalone run.

module github.com/trips-enjoy/platform/chat-service

go 1.25.0

require (
    // Router (MIT)
    github.com/go-chi/chi/v5 v5.x

    // WebSocket server (MIT) — primary transport for live chat fan-out.
    // Pinned to coder/websocket (formerly nhooyr/websocket) — the
    // platform-standard WebSocket library.
    github.com/coder/websocket v1.x

    // PostgreSQL driver + connection pool (Apache-2.0)
    github.com/jackc/pgx/v5 v5.x

    // Redis client (BSD-2-Clause) — used for cross-replica WebSocket
    // fan-out via Pub/Sub; same dependency as api-gateway.
    github.com/redis/go-redis/v9 v9.x

    // Kafka producer/consumer (MIT) — used for the chat message audit
    // event stream and the `chat.message.offline_delivery_required.v1`
    // emission to notification-service.
    github.com/segmentio/kafka-go v0.x

    // JWT validation (MIT) — validates tokens locally against
    // identity-service's JWKS (no direct Keycloak calls).
    github.com/golang-jwt/jwt/v5 v5.x

    // Prometheus metrics (Apache-2.0)
    github.com/prometheus/client_golang v1.20.x

    // OpenTelemetry SDK (Apache-2.0)
    go.opentelemetry.io/otel v1.40.x
    go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc v1.40.x
    go.opentelemetry.io/otel/sdk v1.40.x

    // Structured logging (MIT) — primary logger
    go.uber.org/zap v1.x

    // Structured logging (MIT) — alternate logger for fan-out workers
    github.com/rs/zerolog v1.x

    // WebSocket per-message compression (BSD-3-Clause)
    github.com/klauspost/compress v1.x

    // Standard library extensions (BSD-3-Clause)
    golang.org/x/oauth2 v0.x
    golang.org/x/crypto v0.x
    golang.org/x/sys v0.x
    golang.org/x/net v0.x

    // UUID for UUIDv7 IDs (BSD-3-Clause) — primary keys on chat.threads,
    // chat.messages, chat.attachments, chat.reports, chat.blocks.
    github.com/google/uuid v1.x

    // Keycloak JWKS client (Apache-2.0)
    github.com/coreos/go-oidc/v3 v3.x
)

    // ----------------------------------------------------------------------
    // External vendor SDK placeholder
    // ----------------------------------------------------------------------
    // Keycloak JWKS — same dependency as api-gateway; chat-service validates
    // tokens locally against identity-service's cached JWKS.
    //
    // To extract this service, swap or stub the vendor SDK at the driver
    // boundary. The OSS catalogue entry for this dependency is in
    // ../../shared/OSS_DEPENDENCIES.md 7.
    // For the platform run, the vendor SDK is configured via
    // ../TECH.md {.External integrations}.
    // require <vendor/module/path>
