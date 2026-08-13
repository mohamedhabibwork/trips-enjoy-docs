# ADR-0014: Externalize Configuration via configuration-service

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: configuration, feature-flags, devops, hot-reload, audit

## Context and Problem Statement

The platform has business configuration that changes often (fares,
fees, surge rules, zone hours, promotion rules, tax rates, loyalty
tiers, review prompts) and operational configuration that changes
rarely (DB connection pool sizes, statement timeouts, circuit
breaker thresholds, log levels, OTel sampling rates). Business
configuration must be changeable without a code deploy, with an
audit trail, with hot-reload across all consumers, with a clear
rollback story, and with environment isolation
(`eu-west-prod` vs. `eu-west-staging` vs. `ksa-central-prod`).

We need to decide where configuration lives: in **environment
variables** (the 12-factor default), in **code defaults** (the
anti-pattern), in **config files in the repo** (the legacy
default), or in a **dedicated configuration-service** that holds
hierarchical, versioned configuration documents and pushes changes
to consumers via REST and `configuration.updated.v1` events.

## Decision Drivers

- Business configuration changes often (multiple times a day for
  fares, fees, surge rules, promotion rules) without a code
  deploy.
- Audit trail: every change is recorded with `actor_id`,
  `before`, `after`, `reason`, `timestamp`.
- Environment isolation: `eu-west-prod` and `ksa-central-prod`
  have different fares, taxes, and zone hours.
- Hot-reload: consumers pick up changes in seconds (long-poll +
  event) without a restart.
- Hierarchy: a global default with regional overrides, with
  per-service overrides for service-specific tuning.
- Rollback: a bad change can be reverted in seconds by promoting
  the previous version.
- Validation: a change is rejected at write time if it fails
  schema validation (e.g. a fare < 0).
- 21 active services consume configuration; the platform team cannot
  maintain 21 configuration files in lockstep.

## Considered Options

- **configuration-service (hierarchical, versioned, with REST and
  `configuration.updated.v1` events)** — the chosen option.
- **Environment variables** — the 12-factor default.
- **Config files in the repo** — the legacy default.
- **Code defaults** — the anti-pattern.
- **HashiCorp Consul KV / etcd** — distributed KV store.
- **Feature-flag-service only (no configuration-service)** —
  flags for everything.

## Decision Outcome

Chosen option: "**configuration-service**", because (a) it is the
only option that gives us hierarchical, versioned, validated
configuration with an audit trail and hot-reload across all
consumers, (b) the change is pushed to consumers via the
`configuration.updated.v1` event with the new version, and
consumers re-read on receipt (no restart), (c) the long-poll API
gives sub-second pickup for services that miss the event, (d)
the audit trail is a first-class concern (every change has
`actor_id`, `before`, `after`, `reason`, `timestamp`), and (e)
the schema validation at write time prevents a bad change from
ever reaching consumers.

Environment variables are still used for **deployment-time
configuration** (cluster name, region, log level, OTel sampling
rate) that is fixed at startup and does not change at runtime.
Code defaults are still used for **non-business defaults** (e.g.
HTTP timeouts, retry counts) that are tuning parameters, not
business configuration. The split is documented per service in
`INTEGRATION.md`.

### Consequences

- Good: Business configuration changes without a code deploy. A
  fare change goes live in seconds across all consumers, with an
  audit trail, with rollback, and with environment isolation.
- Good: Audit trail. Every change has `actor_id`, `before`,
  `after`, `reason`, `timestamp`. Auditors and support can see
  who changed what and when.
- Good: Environment isolation. `eu-west-prod` and
  `ksa-central-prod` have different configuration; the change
  in one region does not affect the other.
- Good: Hot-reload. `configuration.updated.v1` event with the
  new version; consumers re-read on receipt; long-poll picks up
  the change in seconds for services that miss the event.
- Good: Hierarchy. A global default with regional overrides,
  with per-service overrides for service-specific tuning.
- Good: Rollback. A bad change is reverted by promoting the
  previous version; consumers pick up the rollback via the same
  event.
- Good: Validation. A change is rejected at write time if it
  fails schema validation (e.g. a fare < 0, a missing required
  field, a type mismatch).
- Good: Per-service integration. Every service declares its
  configuration keys in `INTEGRATION.md`; the platform team
  validates that the keys exist in the configuration registry
  before the service ships.
- Bad: An additional service to operate. (Mitigation: a
  dedicated platform team; the service is small and stateless
  except for its `configuration` schema; a runbook for upgrades.)
- Bad: Consumers must handle a configuration change that arrives
  mid-request. (Mitigation: a versioning scheme; consumers
  re-read on receipt and switch atomically; in-flight requests
  use the version they started with.)
- Bad: A bad change in production can affect all consumers in
  seconds. (Mitigation: a canary stage (`prod-canary`); the
  change is applied to a canary environment first, then to
  `prod`; the audit trail and the rollback story are the
  safety net.)
- Neutral: Environment variables are still used for
  deployment-time configuration. The split is documented per
  service.

### Confirmation

- 100% of business configuration (fares, fees, surge rules,
  zone hours, promotion rules, tax rates, loyalty tiers) is
  managed in `configuration-service`; verified by a CI lint
  that flags hard-coded values in service code.
- Configuration change pickup: P99 < 5 seconds from
  `configuration.updated.v1` to consumer cache invalidation.
- Audit trail: 100% of changes have `actor_id`, `before`,
  `after`, `reason`, `timestamp`; verified by a quarterly
  audit.
- Rollback: a bad change is reverted in < 1 minute by
  promoting the previous version; verified by a quarterly
  drill.
- Hierarchy: a global default with regional overrides is
  testable in staging; the per-service override is
  testable in `prod-canary`.

## Pros and Cons of the Options

### configuration-service

The chosen option. A dedicated service that holds hierarchical,
versioned configuration documents; exposes a REST API for
read and a `configuration.updated.v1` event for change
notification; validates at write time; audits every change.

- Good: Hierarchical, versioned, validated.
- Good: Audit trail (actor, before, after, reason, timestamp).
- Good: Environment isolation.
- Good: Hot-reload via event + long-poll.
- Good: Rollback by promoting the previous version.
- Good: Schema validation at write time.
- Bad: Additional service to operate.
- Bad: Consumers must handle mid-request configuration
  changes.
- Bad: A bad change can affect all consumers in seconds
  (mitigated by canary stage and audit trail).

### Environment variables

The 12-factor default.

- Good: Simple; standard.
- Good: Per-pod, per-deployment configuration.
- Bad: Not changeable without a restart.
- Bad: Not versioned; not audited.
- Bad: Not hierarchical.
- Bad: 21 services × N keys = N×M env vars to manage.

### Config files in the repo

The legacy default.

- Good: Versioned with the code.
- Good: Per-service.
- Bad: Not changeable without a deploy.
- Bad: Not audited at change time.
- Bad: Not hot-reloadable.
- Bad: 21 services × M config files = drift is inevitable.

### Code defaults

The anti-pattern.

- Good: Simplest possible.
- Bad: Not changeable without a deploy.
- Bad: Not auditable.
- Bad: Drift across services is inevitable.
- Bad: Region-specific defaults are impossible without
  per-region builds.

### HashiCorp Consul KV / etcd

A distributed KV store.

- Good: Mature; consistent; watches.
- Good: Hierarchical keys.
- Bad: Not versioned (etcd has revisions, but not the
  audit-trail-with-reason shape we need).
- Bad: Not validated at write time (we'd build a layer
  on top, which is `configuration-service`).
- Bad: Operationally separate from the rest of the
  platform (we'd run a Consul cluster alongside our
  Kubernetes clusters).

### Feature-flag-service only

Flags for everything.

- Good: Mature; well-known; toggleable per cohort.
- Good: Hot-reload.
- Bad: Flags are boolean (or small enum); not suitable
  for hierarchical business configuration (a fare is a
  number with currency, not a boolean).
- Bad: Flags are not versioned documents; they are
  toggles.
- Bad: Mixing flags and configuration muddies the
  semantics; we keep the two services separate.

## References

- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — cross-cutting
  decisions: "Hard-coded business rules" is an anti-pattern
  explicitly avoided; "configuration-service +
  `configuration-service` (flags)" is what we do instead.
- [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) —
  `configuration-service` and ``configuration-service` (flags)` and
  their consumers.
- [`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) —
  `configuration.updated.v1` and `feature_flag.updated.v1`.
- [`DEPLOYMENT_ARCHITECTURE.md`](../DEPLOYMENT_ARCHITECTURE.md) —
  the two-layer configuration: build-time / environment vs.
  runtime business config.
- [`SECURITY_ARCHITECTURE.md`](../SECURITY_ARCHITECTURE.md) —
  configuration changes are audited; admin endpoints require
  request signing.
- ADR-0009 — outbox pattern, which the configuration-service
  uses to commit the new version and the
  `configuration.updated.v1` event atomically.
- `configuration-service` (flags) documentation — the sibling service
  for boolean toggles and rollouts.
