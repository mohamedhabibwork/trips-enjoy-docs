# OpenAPI Contract

This service publishes its generated OpenAPI 3 contract at `/openapi.json`
and Swagger UI at `/docs`.

Swagger calls `identity-service`; it never calls Keycloak directly. The
identity-service owns the OIDC BFF and calls Keycloak server-side on behalf
of clients. Set `IDENTITY_PUBLIC_URL` to the externally reachable identity URL
for the `servers` entry and OAuth2 authorization/token endpoints. The
default is `http://localhost:8082` for local development.

Treat the generated contract as an implementation artifact. Keep every
`/v1/...` operation, schema, canonical error, authentication requirement,
idempotency behavior, and correlation header aligned with the service
documentation under `../../../docs/services/` and the platform standards in
`../../../docs/architecture/`.

When changing an externally visible endpoint, update the implementation and
the matching SRS and integration contract together, then verify both
documentation endpoints with the service's focused test suite.

## Seeder-aware defaults (appended 2026-08-14)

When `identity.keycloak.seed.enabled=true` (the `dev` profile default),
`OpenApiConfiguration` injects the seeded Keycloak realm graph into the
contract so Swagger UI is usable as an auth playground:

- one `Server` URL pointing at `{IDENTITY_PUBLIC_URL}` (the identity-service,
  not the Keycloak realm),
- one `oauth2` `authorizationCode` `SecurityScheme` per channel client
  (e.g. `kc-platform-customer-web-customer`,
  `kc-platform-driver-mobile-driver`), each wired to the identity-service's
  `/oauth2/authorize` and `/oauth2/token` BFF endpoints with the realm name
  passed as a query parameter,
- one `tags` entry per seeded realm (`platform-customer`,
  `platform-driver`, …, `platform-services`); the default-realm tag
  carries an `x-seed-default=true` extension flag.

Both the seeder and the OpenAPI bean consume the same `SeedSpec` bean
(`integration/keycloak/SeedCatalog.kt`), so the realm graph stays a
single source of truth. When the seeder is disabled, the contract
falls back to the minimal `bearerAuth` shape so it still renders.
