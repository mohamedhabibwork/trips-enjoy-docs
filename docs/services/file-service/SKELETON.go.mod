// SKELETON ONLY — documentation, not a working build.
// See ../TECH.md for the platform-default setup.
// Full OSS catalogue (SPDX IDs, license URLs, NOTICE generation) is in
// ../../shared/OSS_DEPENDENCIES.md
//
// Service: file-service
// Profile: Edge / hot path
// Language: Go (`net/http` + `chi`)
//
// To turn this into a runnable module:
//   1. Copy the platform go.mod template (defines the toolchain directive).
//   2. Replace the placeholder versions below with the resolved versions
//      from ../RECOMMENDATIONS.md 5.1.
//   3. Either keep the platform-internal packages (recommended for
//      in-platform run) or remove them and add the equivalent packages
//      individually for a standalone run.

module github.com/trips-enjoy/platform/file-service

go 1.25.0

require (
    // Router (MIT)
    github.com/go-chi/chi/v5 v5.x

    // Redis client (BSD-2-Clause)
    github.com/redis/go-redis/v9 v9.x

    // Prometheus metrics (Apache-2.0)
    github.com/prometheus/client_golang v1.20.x

    // OpenTelemetry SDK (Apache-2.0)
    go.opentelemetry.io/otel v1.40.x
    go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc v1.40.x
    go.opentelemetry.io/otel/sdk v1.40.x

    // Standard library extensions (BSD-3-Clause)
    golang.org/x/oauth2 v0.x
    golang.org/x/crypto v0.x
    golang.org/x/sys v0.x
    golang.org/x/net v0.x

    // UUID for UUIDv7 IDs (BSD-3-Clause)
    github.com/google/uuid v1.x
    require github.com/aws/aws-sdk-go-v2 v2.x
    require github.com/aws/aws-sdk-go-v2/service/s3 v2.x
)

    // ----------------------------------------------------------------------
    // External vendor SDK placeholder
    // ----------------------------------------------------------------------
    // S3 · ClamAV
    //
    // To extract this service, swap or stub the vendor SDK at the driver
    // boundary. The OSS catalogue entry for this dependency is in
    // ../../shared/OSS_DEPENDENCIES.md 7.
    // For the platform run, the vendor SDK is configured via
    // ../TECH.md {.External integrations}.
    // require <vendor/module/path>

