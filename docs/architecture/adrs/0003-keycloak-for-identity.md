# ADR-0003: Use Keycloak as the Central Identity Platform

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: identity, authn, authz, keycloak, security

> **Catalog revision (2026-08-05, appended per append-not-renumber):**
> the locked catalog is **21 active services** per
> [ADR-0017](0017-20-service-architecture.md) and
> [ADR-0021](0021-21-service-architecture-with-chat.md) (chat-service
> added 2026-08-12). The "58 services" figures in this ADR predate the
> 58 → 20 → 21 consolidation; the
> machine-to-machine client credentials pattern, the
> `identity-service` adapter role, and the consequences below
> apply unchanged to the surviving 20-service catalog.

## Context and Problem Statement

The platform serves six distinct user types (customer, driver, courier,
restaurant staff, merchant staff, internal staff/support/admin) plus
service-to-service machine accounts. Each persona has different MFA
requirements, password policies, social-login needs, session
lifetimes, and lockout behaviors. The platform must issue
short-lived JWTs that are validated at the edge (`api-gateway`) and
trusted by every downstream service. The decision is whether to run
an in-house identity service, buy a SaaS identity platform, or
self-host a standards-based one. The choice has to support multi-realm
separation (customers never share a session with internal admins),
federation to social identity providers (Google, Apple, WeChat), and
machine-to-machine credentials for the 21 active services calling each
other.

`identity-service` is the in-platform adapter over Keycloak (see
[`KEYCLOAK_ARCHITECTURE.md`](../KEYCLOAK_ARCHITECTURE.md)). It is
thin; it does not authenticate. Authentication is Keycloak's job.

## Decision Drivers

- Standards-based: OAuth 2.0 + OIDC + SAML, so any client (mobile,
  web, partner) and any framework can integrate.
- Built-in MFA, social login (Google, Apple, Facebook, WeChat),
  federation, and a realm/role/group model that fits our persona
  segmentation.
- Multi-realm: customers, drivers, couriers, staff, internal, and
  service accounts each in their own realm with isolated policies.
- On-prem deployable in our VPCs (multi-region: EU, KSA, etc.) so
  identity never leaves the region for residency reasons.
- Token introspection + JWKS endpoint with caching at the gateway.
- Operationally mature: clustering, Infinispan session cache, PITR
  on the identity DB.
- No vendor lock-in: we can move to any OIDC-compliant IdP if
  Keycloak is deprecated.
- Auditable: every login, logout, role change, and password change
  flows to `audit-service` via Keycloak's event listener SPI.

## Considered Options

- **Keycloak (self-hosted, clustered, with managed Postgres)** — the
  open-source OIDC/SAML IdP.
- **Auth0 (Okta CIC)** — SaaS OIDC provider with strong DX.
- **AWS Cognito** — managed IAM-style identity, integrated with AWS.
- **Homegrown identity-service** — write our own registration, MFA,
  token issuance, and storage.

## Decision Outcome

Chosen option: "**Keycloak (self-hosted, clustered)**", because (a)
it is the only option that gives us OIDC + SAML + multi-realm +
on-prem deploy + built-in MFA + social federation without per-MAU
SaaS pricing, (b) the realm/role/group model maps directly to our
six personas without contortion, (c) the JWKS endpoint with TTL
gives the gateway a 1-hour cache (matching the access-token
lifetime) so token validation is local and fast, and (d) we can
operate it inside our VPCs to satisfy regional data-residency
requirements (PDPL in KSA, GDPR in EU, etc.). Our thin
`identity-service` adapter normalizes Keycloak's model into our
internal `identity_id` (UUIDv7) and exposes a stable
`/v1/identities/{identity_id}` API so the rest of the platform
never has to know about Keycloak-specifics.

### Consequences

- Good: Standards-based. Every service uses the same JWT shape; the
  `api-gateway` validates once, and downstream services trust the
  headers (`X-User-Id`, `X-User-Type`, `X-Roles`, `X-Scopes`,
  `X-Tenant-Id`).
- Good: Per-realm isolation. A compromised customer credential
  cannot be replayed against the `platform-internal` realm; the
  realms have separate user stores, separate password policies, and
  separate MFA policies.
- Good: Built-in MFA, social identity brokers (Google, Apple,
  Facebook, WeChat), refresh-token rotation with theft detection,
  and step-up MFA for high-value actions.
- Good: Service-to-service client credentials in
  `platform-services` realm; each of the 21 active services has its own
  client with a Vault-rotated secret and explicit client roles
  granted in other services' clients.
- Good: Auditable. Keycloak's event listener SPI emits every login,
  logout, role change, and password change to Kafka, correlated
  with `identity.session.*.v1` events.
- Good: On-prem in our VPC. Identity data never leaves the region.
- Bad: Operational cost. We must run a clustered Keycloak (≥ 3
  nodes) with Infinispan, a managed Postgres for the identity
  schema, JWKS caching at the gateway, and a runbook for upgrades.
  (Mitigation: a dedicated identity-platform team that owns this
  end-to-end.)
- Bad: Keycloak's user federation is rich but its admin API is
  complex. We mitigate by routing every admin operation through
  `admin-service`, which is the only client authorized to call
  Keycloak's admin API; this gives us a single audit trail.
- Bad: Upstream breaking changes between Keycloak minor versions have
  bitten us. We pin to one minor version per quarter and run a
  staging soak for 7 days before promoting.
- Neutral: We rely on Keycloak's session model; server-initiated
  logout is via the admin API plus a Redis-backed revocation set at
  the gateway. This is the right shape, but it is a custom
  integration we must maintain.

### Confirmation

- 100% of human and service identities authenticate via Keycloak
  (no out-of-band credentials stored by any service).
- 99.99% availability for the `platform-services` realm (s2s
  auth must not be a single point of failure for the platform).
- JWKS cache hit rate > 99% at the gateway.
- Mean time from `identity.session.revoked.v1` to gateway-side
  token-blacklist update: < 1 second.
- Quarterly review: rotate all client secrets, audit realm roles,
  prune stale sessions, review MFA enrollment rates per persona.

## Pros and Cons of the Options

### Keycloak (self-hosted)

Open-source OIDC + SAML IdP. Clustered, with Infinispan distributed
cache, external Postgres for the identity schema, JWKS endpoint.

- Good: Standards-based (OIDC, SAML, OAuth 2.0).
- Good: Multi-realm; per-realm user store, password policy, MFA
  policy, theme, token lifetime.
- Good: Built-in MFA, social identity brokers, refresh-token
  rotation with theft detection.
- Good: Operationally mature; clustering, Infinispan, DB-backed
  sessions, JWKS endpoint.
- Good: On-prem; identity data never leaves the region.
- Good: No per-MAU SaaS pricing; cost is the cluster we already run.
- Bad: Operational cost of running it (3+ nodes, upgrades, theme
  customizations).
- Bad: Admin API is complex; we must invest in our `admin-service`
  wrapper.
- Bad: Upstream breaking changes between minor versions; we pin and
  soak.

### Auth0 (Okta CIC)

SaaS OIDC provider.

- Good: Excellent developer experience; quick to integrate.
- Good: Hosted; no cluster to run.
- Good: Strong social-login catalog and MFA out of the box.
- Bad: Per-MAU pricing scales linearly with our user base; for
  100k+ customers, this is a material line item.
- Bad: Multi-region data residency: the SaaS controls the data
  plane. Our PDPL / GDPR requirements make this risky.
- Bad: Vendor lock-in to Okta-specific extensions (rules, hooks,
  Actions) for things we can do natively in Keycloak.
- Bad: Outbound dependency for every login; if the SaaS is degraded
  in a region, our login is degraded.

### AWS Cognito

Managed IAM-style identity, integrated with AWS.

- Good: Managed; no cluster to run.
- Good: Cheap at low scale; integrates with AWS IAM.
- Bad: User pools have weaker customization than Keycloak realms;
  our six-persona model maps awkwardly.
- Bad: Limited social-login catalog out of the box (some require
  Lambda triggers).
- Bad: Tied to AWS; multi-cloud is harder.
- Bad: Same data-residency concerns as Auth0 (we are not
  AWS-only; we deploy in EU and KSA regions).

### Homegrown identity-service

Write our own registration, MFA, token issuance, and storage.

- Good: Full control.
- Good: Tailored exactly to our needs.
- Bad: We become the maintainer of an IdP. This is a multi-year
  investment with diminishing returns; we'd be rebuilding what
  Keycloak gives us for free.
- Bad: Security is hard. The IdP is the highest-value target in
  the system; a homegrown IdP is a security risk.
- Bad: We forgo the ecosystem (OIDC libraries, social-login
  connectors, MFA authenticators) that has been hardened over a
  decade.

## References

- [`KEYCLOAK_ARCHITECTURE.md`](../KEYCLOAK_ARCHITECTURE.md) — the
  full design: realms, clients, roles, groups, scopes, claims, token
  flows, lifetimes, logout, MFA, password policy, social login,
  phone/OTP, device management, admin access, machine-to-machine,
  high-availability, audit.
- [`SECURITY_ARCHITECTURE.md`](../SECURITY_ARCHITECTURE.md) — token
  validation, revocation, defense in depth.
- [`API_STANDARDS.md`](../API_STANDARDS.md) — the JWT-bearer
  contract, the headers the gateway injects, the rate-limiting
  rules per endpoint.
- Keycloak documentation — realms, clients, identity brokers,
  event listener SPI.
- OAuth 2.0 / OIDC specifications — RFC 6749, RFC 6750, OpenID
  Connect Core 1.0.
