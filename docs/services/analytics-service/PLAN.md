# analytics-service — Implementation Plan

**Domain:** Platform & Operations
**Tier:** 3
**Technology:** Kotlin + Spring Boot 4 + Spring Kafka
**Criticality:** T3 (99.5% SLO)
**DB Schema:** `analytics_meta`
**Cache:** —
**HPA:** Kafka consumer lag, 3–15, 50k evt/s

---

## Purpose

`analytics-service` is the platform's event ingestion pipeline for the data lake and OLAP warehouse. It consumes every domain event from Kafka, applies schema evolution and PII handling, and lands data as Parquet on S3, then loads it into Snowflake/BigQuery/Redshift.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `analytics_meta`: tables `replay_jobs` (partitioned by month), `schema_registry_cache`, `consumer_offsets`, `outbox`, `inbox`
- [ ] Key columns: `replay_jobs(id UUID, topic TEXT, partition INT, from_offset BIGINT, to_offset BIGINT, status TEXT, events_processed BIGINT, started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ)`
- [ ] Write Flyway migrations (forward-only)
- [ ] Implement `ReplayJob` aggregate and `SchemaRegistryCache` repository

### Phase 2 — REST API
- [ ] `GET /v1/schemas` — list known schemas (requires `analytics.read` scope)
- [ ] `GET /v1/schemas/{name}/versions` — list versions of a schema
- [ ] `POST /v1/replays` — start a replay / backfill job (requires `analytics.admin`, `Idempotency-Key`)
- [ ] `GET /v1/replays/{id}` — read replay status
- [ ] `GET /v1/consumer/lag` — read consumer lag per topic/partition

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `analytics.replay.completed.v1` → topic `analytics.replay.completed` (on replay success)
- [ ] Publish `analytics.consumer.lag.v1` → topic `analytics.consumer.lag` (periodic, every minute)
- [ ] Publish `analytics.schema.registered.v1` → topic `analytics.schema.registered` (new schema version)
- [ ] Publish `analytics.ingest.batch_completed.v1` → topic `platform.analytics`
- [ ] Publish `analytics.export.completed.v1` → topic `platform.analytics`
- [ ] Publish `analytics.drift.detected.v1` → topic `platform.analytics`
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume all domain events (every topic) → deserialize, tokenize PII, write to data lake (Parquet/S3), load to OLAP warehouse
- [ ] Consume `ride.payment.completed.v1` → increment revenue/GMV fact table
- [ ] Consume `food.payment.completed.v1` → increment GMV/merchant revenue fact table
- [ ] Consume `trip.completed.v1` → increment trip count/distance/duration
- [ ] Consume `food.order.delivered.v1` → increment order count/prep time/delivery time
- [ ] Consume `ledger.posted.v1` → increment all money-movement facts
- [ ] Consume `payment.captured.v1` → write to data lake for OLAP

### Phase 5 — Caching
- [ ] Consumer offset cache for replay coordination
- [ ] Schema registry cache (avoid repeat fetches)

### Phase 6 — External Integrations
- [ ] AWS S3 — data lake landing at `s3://trips-enjoy-platform-analytics/datalake/<topic>/<yyyy>/<mm>/<dd>/`
- [ ] Snowflake / BigQuery / Redshift — OLAP sink (configurable via `OLAP_KIND`)
- [ ] Confluent/Apicurio Schema Registry — Avro schema evolution
- [ ] HashiCorp Vault — DB credentials, warehouse credentials, S3 keys, PII hash salt
- [ ] Circuit breakers on OLAP and schema registry outbound calls

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal`
- [ ] Required scopes/roles: `analytics.read` for read, `analytics.admin` for replay/schema management
- [ ] PII tokenization (HMAC-SHA256, salt from Vault) before any data lake landing
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`
- [ ] Metrics: RED per route + `analytics_consumer_lag{topic,partition}`, `analytics_datalake_writes_total{topic}`, `analytics_olap_loads_total{table}`
- [ ] OpenTelemetry traces with child spans for schema fetch, PII tokenization, lake write, OLAP load
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: PII tokenization logic, schema evolution, replay job state machine
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka); mock S3 and OLAP
- [ ] E2E tests: end-to-end event ingestion, replay backfill, schema incompatibility handling

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (Kafka consumer lag, 3–15 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| Schema Registry | GET per schema | Read Avro schema for deserialization | Yes |
| AWS S3 | PUT per event batch | Write Parquet to data lake | No (managed retry) |
| OLAP warehouse | per load | Load events to warehouse | Yes |
| HashiCorp Vault | GET per secret | Read DB/warehouse/S3 credentials | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `analytics.replay.completed.v1` | `analytics.replay.completed` | Replay job success | `reporting-service` |
| `analytics.consumer.lag.v1` | `analytics.consumer.lag` | Periodic (every minute) | Monitoring |
| `analytics.schema.registered.v1` | `analytics.schema.registered` | New schema version registered | `reporting-service` |
| `analytics.ingest.batch_completed.v1` | `platform.analytics` | Batch ingestion job completes | `reporting-service`, `admin-service` |
| `analytics.export.completed.v1` | `platform.analytics` | Scheduled export completes | `admin-service`, `reporting-service` |
| `analytics.drift.detected.v1` | `platform.analytics` | Reconciliation drift detected | `admin-service`, `support-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| All domain events (every topic) | All services | Deserialize, tokenize PII, write to data lake, load to OLAP |
| `ride.payment.completed.v1` | `ride-payment-integration-service` | Increment revenue/GMV fact table |
| `food.payment.completed.v1` | `food-payment-integration-service` | Increment GMV/merchant revenue fact table |
| `trip.completed.v1` | `trip-service` | Increment trip count/distance/duration |
| `food.order.delivered.v1` | `delivery-service` | Increment order count/prep time/delivery time |
| `ledger.posted.v1` | `ledger-service` | Increment all money-movement facts |
| `payment.captured.v1` | `payment-service` | Write to data lake for OLAP |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
