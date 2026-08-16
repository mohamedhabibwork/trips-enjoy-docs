# ADR-0024: Kafka dead-letter queue (DLQ) topic naming

- Status: Accepted
- Date: 2026-08-15
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: kafka, dlq, messaging, contracts

> **Catalog revision (2026-08-15, appended per append-not-renumber):**
> this ADR locks the platform-wide convention for Kafka DLQ topic
> names. The platform's 21 services must adopt `<topic>.dlq` (Spring
> Kafka's default) as the canonical DLQ naming pattern, replacing
> the previous platform convention of `<topic>.DLQ.v1` which was
> declared by `platform-spring-boot-messaging` but ignored by every
> service.

## Context and Problem Statement

The [`platform-spring-boot-starter`](../shared/INTEGRATION.md)
`platform-spring-boot-messaging` module ships a
`DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` that
defaults to the Spring Kafka convention `<topic>.dlq` — but the
module's own documented default in
[`shared/MODULES.md`](../shared/MODULES.md) was at one point
`<topic>.DLQ.v1` (uppercase, versioned). 8 of 8 services that wire
their own `KafkaConsumerConfiguration.kt` use `<topic>.dlq` —
confirming the Spring Kafka default is the de-facto convention.
The audit at [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0020](../../shared/PLATFORM_DRY_AUDIT.md)
flagged this drift.

The contract is load-bearing: every consumer's `@KafkaListener`
container has its DLQ topic name hard-coded into the consumer's
configuration. If one service ships `<topic>.dlq` and another
expects `<topic>.DLQ.v1`, every message that fails on the
shipping side lands in a topic the expecting side never reads.

## Decision Drivers

- **Operational simplicity.** A single, unambiguous DLQ naming
  pattern across 21 services.
- **Spring Kafka alignment.** `<topic>.dlq` is Spring Kafka's
  built-in default for `DeadLetterPublishingRecoverer`.
- **Tooling visibility.** Downstream error catalog, k8s alerts,
  and `linkerd-metrics` dashboards key on `<topic>.dlq`.

## Considered Options

1. **`<topic>.dlq`** (Spring Kafka default; 8/8 services)
2. **`<topic>.DLQ.v1`** (previous platform module default)
3. **`<topic>-dlq`** (kebab-case; not adopted by any service)
4. **`<topic>.deadletter`** (verbose; not adopted by any service)

## Decision Outcome

**Chosen option: `<topic>.dlq`.** This is the Spring Kafka default,
the de-facto convention across all 8 services that wire their own
consumer config, and the simplest possible pattern. The
`platform-spring-boot-messaging` module's `DefaultErrorHandler`
already defaults to this pattern; the previous "platform default"
of `<topic>.DLQ.v1` was a documentation artefact, not an enforced
behaviour. This ADR ratifies the actual default.

### Consequences

**Good:**
- Single canonical DLQ topic name across the platform
- 8 existing services need zero changes (already correct)
- 1 service (`api-gateway`, which uses segmentio/kafka-go and
  the audit DLQ topic `audit.admin.api_gateway.dlq`) needs no
  change either
- Future services follow Spring Kafka defaults out of the box
- All `linkerd-metrics` alerts and k8s PromQL rules keying on
  `<topic>.dlq` work uniformly

**Bad:**
- Documentation at `shared/MODULES.md` references `<topic>.DLQ.v1`
  and must be updated (1-line correction; tracked in this ADR's
  follow-up)
- Any consumer that was configured to read `<topic>.DLQ.v1` (none
  found during audit) would need migration; verified absent

### Follow-up

- [ ] Update `shared/MODULES.md` to declare `<topic>.dlq` (not
  `<topic>.DLQ.v1`) as the canonical platform DLQ naming pattern.
- [ ] Update `shared/PARTITION_FUNCTIONS.md` and any K8s
  `PrometheusRule` that referenced `<topic>.DLQ.v1`.
- [ ] Bump `platform-spring-boot-starter` to `4.1.1` (documentation-
  only release) to mark the corrected default.

## Pros and Cons of the Options

### `<topic>.dlq`

Spring Kafka's built-in default; matches all 8 services that wire
their own consumer config; minimal cognitive load.

### `<topic>.DLQ.v1`

Versioned uppercase pattern. Adds explicit versioning (`.v1`)
which is useful for schema-evolved topics, but unnecessary for
DLQs (a DLQ is a side-channel, not a primary event stream).

### `<topic>-dlq` / `<topic>.deadletter`

Neither pattern is in use anywhere on the platform; rejected as
premature standardisation.

## References

- [ADR-0005](0005-kafka-as-event-broker.md) — Apache Kafka as the
  event broker
- [ADR-0009](0009-transactional-outbox.md) — Outbox pattern for
  event publication
- [`shared/MODULES.md`](../shared/MODULES.md) — sub-module
  breakdown (where the prior `<topic>.DLQ.v1` default lived)
- [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0020](../../shared/PLATFORM_DRY_AUDIT.md)
  — the audit that flagged this drift
