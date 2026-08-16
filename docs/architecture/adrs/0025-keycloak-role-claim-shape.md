# ADR-0025: Keycloak role claim shape (`ROLE_<UPPER>_<UPPER>`)

- Status: Accepted
- Date: 2026-08-15
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: security, keycloak, jwt, rbac, identity

> **Catalog revision (2026-08-15, appended per append-not-renumber):**
> this ADR locks the platform-wide convention for how Keycloak roles
> and scopes are projected into JWT `authorities` claims. The
> platform's 21 services must adopt `ROLE_<UPPER>_<UPPER>` (with
> client prefix where applicable), matching what
> `identity-service`'s `service-claims` protocol mapper emits and
> what `platform-spring-boot-security` decodes.

## Context and Problem Statement

The [`identity-service`](../services/identity-service/) Keycloak
seeder projects 21 `<service>.admin` realm roles onto every token
via the `service-claims` client scope's protocol mappers. The
canonical claim shape produced by those mappers is:

- **Realm roles:** `ROLE_<UPPER>` (e.g. `ROLE_PLATFORM_SUPER_ADMIN`)
- **Client roles (with prefix):** `ROLE_<CLIENT>_<UPPER>` (e.g.
  `ROLE_PAYMENT_ADMIN`)
- **OAuth scopes:** `SCOPE_<UPPER>` (e.g. `SCOPE_PAYMENT_READ`)

The `platform-spring-boot-security` module's `JwtRoleConverter`
parses tokens into this shape. But 11 of 14 Kotlin services ship
their own `SecurityConfiguration.kt` with a custom
`JwtAuthenticationConverter` that:

- Lowercases scopes (`SCOPE_<lower>`)
- Uses realm roles only (drops the client-prefix)
- Maps differently per service (some use `ROLE_<upper>`,
  others `ROLE_<CLIENT>_<UPPER>`)

The audit at [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0021](../../shared/PLATFORM_DRY_AUDIT.md)
flagged this as drift that must be resolved before deleting the
11 redundant `SecurityConfiguration.kt` files. Without convergence,
`@PreAuthorize("hasRole('payment.admin')")` annotations would fail
silently on services that expect `ROLE_PAYMENT_ADMIN` and the
gateway-injected principal wouldn't match the service-local claim
shape.

## Decision Drivers

- **Single canonical authority shape.** All 21 services must
  agree on `ROLE_<UPPER>` and `SCOPE_<UPPER>` (and
  `ROLE_<CLIENT>_<UPPER>` for client-scoped roles).
- **Keycloak seeder parity.** The `service-claims` protocol mapper
  in `identity-service` is the single source of truth; services
  must not re-project claims.
- **Spring Security compatibility.** The
  `org.springframework.security.core.authority.SimpleGrantedAuthority`
  shape requires uppercase enum values; lowercase roles
  (`SCOPE_payment_read`) collide with the OAuth2 scope namespace.

## Considered Options

1. **`ROLE_<UPPER>_<UPPER>`** (platform canonical, identity-seeder
   emits, Spring Security convention)
2. **`ROLE_<CLIENT>_<UPPER>`** (only for client-scoped roles;
   otherwise `ROLE_<UPPER>`)
3. **Lowercased shape `role_<lower>`** (8 of 11 services' current
   default)
4. **Custom `trips_<service>_<role>` namespacing** (rejected —
   breaks Spring Security's authority prefix conventions)

## Decision Outcome

**Chosen option: option 2 with option 1 as fallback.**

- **Realm roles** are projected as `ROLE_<UPPER>` (e.g.
  `ROLE_PLATFORM_SUPER_ADMIN`).
- **Client roles** are projected as `ROLE_<CLIENT>_<UPPER>` (e.g.
  `ROLE_PAYMENT_ADMIN` for the `payment-service`'s `admin` client
  role).
- **OAuth scopes** are projected as `SCOPE_<UPPER>` (e.g.
  `SCOPE_PAYMENT_READ`).

This matches what `identity-service`'s `service-claims` protocol
mapper emits and what `platform-spring-boot-security`'s
`JwtRoleConverter` parses. The 11 redundant
`SecurityConfiguration.kt` files are deleted; each service keeps
only a 1-2 line subclass that overrides `permitAll()` paths and
the `/admin/v1/**` matcher.

### Consequences

**Good:**
- Single canonical role/scope shape across 21 services
- `identity-service` seeder's projection becomes the source of
  truth (no per-service re-projection)
- `@PreAuthorize("hasRole('payment.admin')")` resolves to
  `ROLE_PAYMENT_ADMIN` consistently
- 11 redundant `SecurityConfiguration.kt` files deleted (~880 LOC)

**Bad:**
- 11 services' existing `@PreAuthorize` strings must be
  uppercased and prefixed where applicable (V__ migration
  per service: rename `hasRole('payment.admin')` →
  `hasRole('PAYMENT_ADMIN')`)
- Integration tests that mint test tokens with the old shape must
  be updated to use the new shape
- Rollout requires coordination with `identity-service` seeder
  change to ensure mapper output matches

### Follow-up

- [ ] Update `identity-service`'s `service-claims` protocol
  mapper to confirm uppercase output (audit found it already
  does; this ADR formalises the contract).
- [ ] Update `shared/SECURITY_ARCHITECTURE.md` §RBAC to declare
  the canonical role/scope shape.
- [ ] Update `services/RECOMMENDATIONS.md` §6.2a (SUPER_ADMIN
  preset) to confirm the 21 `<service>.admin` roles project
  to `ROLE_<SERVICE>_ADMIN`.

## Pros and Cons of the Options

### `ROLE_<UPPER>` / `ROLE_<CLIENT>_<UPPER>` (chosen)

Matches identity-seeder, matches Spring Security, scales to 21
services without collision. The client prefix is necessary
because multiple services can have an `admin` role and they must
not collide in the principal's authorities list.

### Lowercased shape

What 8 of 11 services currently default to. Collides with OAuth
scope lowercase namespace and breaks Spring Security's authority
prefix conventions.

### Custom `trips_<service>_<role>`

Adds an extra prefix layer. Rejected because the client prefix
(`ROLE_<CLIENT>_<UPPER>`) already provides the necessary
namespacing and aligns with Keycloak's own role representation.

## References

- [ADR-0003](0003-keycloak-for-identity.md) — Keycloak as the
  central identity platform
- [ADR-0019](0019-request-id-at-the-edge.md) — request id at the
  edge (the gateway-injected principal claim contract)
- [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0021](../../shared/PLATFORM_DRY_AUDIT.md)
  — the audit that flagged this drift
- [`services/identity-service/`](../services/identity-service/) —
  the `service-claims` protocol mapper source
- [`shared/SECURITY_ARCHITECTURE.md`](../SECURITY_ARCHITECTURE.md)
  — where the canonical RBAC shape is documented
