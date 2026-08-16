# configuration-service — Alert Runbook

This is the canonical alert-response playbook for the 8 alert rules
defined in `monitoring/configuration-service.yaml`. Each section lists
the alert's symptoms, likely root causes, diagnostic steps, and the
remediation playbook. Update this runbook whenever an alert is added
or its meaning changes.

Primary on-call: `platform-oncall` (PagerDuty service `config-prod`).
Escalation: `configuration-service-eng@trips-enjoy.com` →
`platform-eng-leads@trips-enjoy.com`.

---

## 1. ConfigurationServiceReadLatencyP99

**Fires when:** p99 read latency on `GET /v1/configurations/{key}`
exceeds 200ms for 10 minutes.

**Symptoms:** mobile/web clients see slow key lookups; pricing-service
write path slows down (it reads commission keys on every quote).

**Diagnostics:**

```bash
# 1. Confirm the alert is real, not a single-replica spike.
kubectl -n configuration-service exec -it $(kubectl -n configuration-service get pod -o name | head -1) -- \
  curl -s http://localhost:8081/actuator/prometheus | grep http_server_requests_seconds

# 2. Check Redis cache hit ratio (FR-020 — read cache).
kubectl -n configuration-service logs -l app.kubernetes.io/name=configuration-service --tail=200 | \
  grep -E 'cache:hit|cache:miss'

# 3. Check Postgres partitioning — slow partition prune on `versions`?
psql -h $PGHOST -U postgres -d trips_enjoy -c "
SELECT schemaname, tablename, seq_scan, idx_scan
FROM pg_stat_user_tables WHERE tablename IN ('documents','versions','audit_log','outbox');
"
```

**Likely root causes:**

1. **Redis cache layer down** — every read falls back to DB. Check
   `redis-cli ping` from any pod; the `cache:tls` value should be
   `PONG`. Restart Redis if down.
2. **Outbox backlog starving threads** — see
   `ConfigurationServiceOutboxLag` and resolve first.
3. **Postgres connection pool exhaustion** — check
   `hikari_pool_*` metrics for `active`/`idle`. Restart the pod to
   reset if Hikari is wedged.
4. **JSON Schema validation spike** — see
   `ConfigurationServiceSchemaValidationFailureRate` (if firing).

**Resolution:**

- Cache layer: restart Redis, check `redis-clients` for stuck clients.
- Pool exhaustion: roll the deployment (`kubectl rollout restart
  deployment/configuration-service`) to reset Hikari.
- Schema validation cost: inspect the failing schema via
  `GET /v1/configurations/{key}/versions/latest` (admin only).

---

## 2. ConfigurationServiceWriteLatencyP99

**Fires when:** p99 write latency on `POST/PUT /v1/configurations/...`
exceeds 500ms for 10 minutes.

**Symptoms:** admin console shows slow saves; outbox events accumulate.

**Diagnostics:**

```bash
# 1. Are writes serializing on the same key?
psql -h $PGHOST -U postgres -d trips_enjoy -c "
SELECT
  current_setting('lock_timeout'),
  count(*) FILTER (WHERE granted = false) AS waiting
FROM pg_locks WHERE locktype = 'relation' AND relation::regclass::text LIKE 'configuration.%';
"

# 2. Is the audit_log immutability trigger blocking?
kubectl -n configuration-service logs -l app.kubernetes.io/name=configuration-service --tail=500 | \
  grep -E 'prevent_audit_log_mutation|trigger'
```

**Likely root causes:**

1. **Pessimistic-lock contention** — concurrent writes to the same key
   serialize on `SELECT ... FOR UPDATE` (ConfigurationIngestService).
   Two operators editing the same key simultaneously will queue.
2. **Audit_log immutability trigger (V4)** firing on a real UPDATE
   should never happen (UPDATE/DELETE are revoked). If it does, the
   trigger is fighting a buggy client — capture the stack trace.
3. **JSON Schema validation cost** — complex schemas (deeply nested
   `oneOf`/`anyOf`) can take >100ms per validation. Capture the schema.

**Resolution:**

- Lock contention: the two operators should serialize via the
  admin console; the alert is informational. If sustained, raise
  `lock_timeout` or split the key namespace.
- Audit trigger: rollback, capture the offending client, file a
  bug against the operator workflow.

---

## 3. ConfigurationServiceHighErrorRate

**Fires when:** 5xx rate exceeds 2% for 10 minutes.

**Symptoms:** admin console shows error toast; clients retry; consumers
fall behind on cache invalidation.

**Diagnostics:**

```bash
# 1. Which routes/status codes?
kubectl -n configuration-service exec -it $(kubectl -n configuration-service get pod -o name | head -1) -- \
  curl -s http://localhost:8081/actuator/prometheus | \
  grep http_server_requests_seconds_count | grep -E 'status="5'

# 2. Keycloak reachable?
curl -fsS https://keycloak.trips-enjoy.com/realms/platform-services/.well-known/openid-configuration | jq .

# 3. Postgres reachable?
psql -h $PGHOST -U postgres -d trips_enjoy -c "SELECT 1;"

# 4. Redis reachable?
redis-cli -h $REDIS_HOST PING
```

**Likely root causes:**

1. **Keycloak JWKS unreachable** — the JWT decoder caches the JWKS for
   60s; a JWKS outage older than that will 500 every request. Check
   the `KeycloakReachable` synthetic (separate alert).
2. **Postgres connection exhaustion** — see ReadLatencyP99 playbook.
3. **Redis cache layer down** — read-through works but writes that
   touch cache:write paths (none today) would 500.

**Resolution:**

- Keycloak: check `KeycloakLogs` in monitoring. If JWKS endpoint
  is returning 5xx, escalate to identity-service.
- Postgres: see ReadLatencyP99 playbook.
- Redis: see ReadLatencyP99 playbook.

---

## 4. ConfigurationServiceOutboxLag / ConfigurationServiceOutboxLagCritical

**Fires when:** oldest unpublished outbox event is >5s (warning) or
>60s (critical, pages oncall).

**Symptoms:** trip-service, payment-service, pricing-service all
running on stale config; new commission values, gateway enables, or
feature flags don't reach consumers.

**Diagnostics:**

```bash
# 1. Outbox lag distribution.
psql -h $PGHOST -U postgres -d trips_enjoy -c "
SELECT
  count(*) FILTER (WHERE published_at IS NULL) AS unpublished,
  count(*) FILTER (WHERE published_at IS NULL AND created_at < now() - INTERVAL '1 minute') AS stale,
  max(created_at) FILTER (WHERE published_at IS NULL) AS oldest
FROM configuration.outbox;
"

# 2. Is the OutboxPublisher scheduler running?
kubectl -n configuration-service logs -l app.kubernetes.io/name=configuration-service --tail=200 | \
  grep -E 'publishPending|publish-interval'

# 3. Is Kafka healthy?
kubectl -n kafka exec -it $(kubectl -n kafka get pod -o name | head -1) -- \
  kafka-topics --list --bootstrap-server localhost:9092 | head
```

**Likely root causes:**

1. **OutboxPublisher paused** — `@Scheduled` is single-threaded by
   default. If it throws an exception, it stops firing. Check the
   pod's logs.
2. **Kafka cluster down** — check `kafka-cluster-not-ready` synthetic.
3. **Topic ACL revoked** — broker rejects the publish. Check
   the `CONFIGURATION_SERVICE_KAFKA_*` ACLs.

**Resolution:**

- Publisher paused: roll the deployment to reset the scheduler.
- Kafka down: escalate to platform-data oncall.
- ACL revoked: file an ops ticket with the broker team; the
  publish path will start working again immediately after.

---

## 5. ConfigurationServicePartitionMaintenanceStalled

**Fires when:** `configuration_partition_maintained_timestamp_seconds`
has not been updated in over 25 hours.

**Symptoms:** writes to `configuration.versions` or
`configuration.audit_log` will start failing at month boundaries (no
child partition for the current month).

**Diagnostics:**

```bash
# 1. Has the job run?
kubectl -n configuration-service get cronjob configuration-service-partition-maintenance -o jsonpath='{.status.lastScheduleTime}'
kubectl -n configuration-service get jobs --sort-by=.metadata.creationTimestamp

# 2. Latest job log.
kubectl -n configuration-service logs -l job-name=<latest> --tail=200

# 3. Is the advisory lock held by another replica?
psql -h $PGHOST -U postgres -d trips_enjoy -c "
SELECT * FROM pg_locks
WHERE locktype = 'advisory' AND objid = hashtext('configuration.partition');
"
```

**Likely root causes:**

1. **All replicas failed** — most likely cause: `pg_try_advisory_xact_lock`
   is returning false because the Postgres connection pool is
   exhausted.
2. **Postgres pg_cron schedule missing** — the optional `pg_cron`
   fallback may be missing; the Spring wrapper is the
   authoritative path, but verify both.

**Resolution:**

- Re-trigger the job manually:
  `kubectl -n configuration-service create job --from=cronjob/configuration-service-partition-maintenance manual-$(date +%s)`
- Verify next run: same command with `--dry-run=client -o yaml`.
- If it keeps failing: scale the deployment down to 1 replica, then
  restart, then re-trigger.

---

## 6. ConfigurationServiceKafkaConsumerLag

**Fires when:** total consumer lag across the 5 inbound topics exceeds
10000 messages for 10 minutes.

**Symptoms:** cache invalidation is falling behind; consumers may
serve stale data.

**Diagnostics:**

```bash
# Per-topic lag.
kubectl -n configuration-service exec -it $(kubectl -n configuration-service get pod -o name | head -1) -- \
  curl -s http://localhost:8081/actuator/prometheus | grep kafka_consumer_records_lag_max
```

**Likely root causes:**

1. **Slow handler** — one of the 5 consumers is slow. Check the
   `application/handler_duration` metric in the logs.
2. **Producer hot-loop** — `customer.segment.changed.v1` fires per
   segment change. A mass-segment-update (e.g. price-tier
   rebalancing) can outpace the consumer.

**Resolution:**

- Slow handler: restart the affected pod. The consumer's
  `MANUAL_IMMEDIATE` ack mode means a restart loses only the in-flight
  message.
- Producer hot-loop: no action; the alert is informational and will
  resolve once the producer-side batch completes.

---

## 7. ConfigurationServiceHeapPressure

**Fires when:** JVM heap utilization > 85% for 10 minutes.

**Symptoms:** GC overhead growing; requests slow; pod may OOM-kill.

**Diagnostics:**

```bash
# 1. Force a heap dump and download.
kubectl -n configuration-service exec -it $(kubectl -n configuration-service get pod -o name | head -1) -- \
  jcmd 1 GC.heap_dump /tmp/heapdump.hprof
kubectl -n configuration-service cp <pod>:/tmp/heapdump.hprof /tmp/heapdump.hprof

# 2. Pull jvm_memory_used_bytes by area.
kubectl -n configuration-service exec -it $(kubectl -n configuration-service get pod -o name | head -1) -- \
  curl -s http://localhost:8081/actuator/prometheus | grep jvm_memory_used_bytes
```

**Likely root causes:**

1. **Long-poll registry leak** — `LongPollService.waiters` is a
   `ConcurrentHashMap<UUID, MutableList<CompletableDeferred<Update>>>`.
   If subscribers never receive a notification (e.g. Redis pub/sub
   layer down), the deferred promises accumulate. The fix is
   already implemented (the bounded `withTimeoutOrNull`), but
   verify the timeout is enforced.
2. **OutboxEvent row accumulation** — if OutboxPublisher is broken,
   rows pile up. JVM heap pressure + outbox lag together are a
   strong signal here.

**Resolution:**

- Heap dump → analyze with Eclipse MAT → identify the leak.
- Roll the deployment if the leak is severe.
- Fix the underlying root cause (see the OutboxLag playbook if both
  are firing).

---

## 8. ConfigurationServiceSnapshotJobMissing / LongPollSaturation

**Snapshot missing fires when:** DailySnapshotJob has not produced a
snapshot in over 26 hours.

**Long-poll saturation fires when:** active long-poll connections >
80% of the 1000/pod cap.

**Diagnostics (snapshot):**

```bash
# 1. Has the job run today?
kubectl -n configuration-service get cronjob configuration-service-snapshot -o jsonpath='{.status.lastScheduleTime}'
kubectl -n configuration-service get jobs --sort-by=.metadata.creationTimestamp | head -5

# 2. Most recent job's logs.
kubectl -n configuration-service logs -l job-name=<latest> --tail=200
```

**Diagnostics (long-poll saturation):**

```bash
# Long-poll connections by route.
kubectl -n configuration-service exec -it $(kubectl -n configuration-service get pod -o name | head -1) -- \
  curl -s http://localhost:8081/actuator/prometheus | grep configuration_longpoll_connections
```

**Likely root causes (snapshot):**

1. **HPA suspended the CronJob** — the CronJob template has
   `suspend: true` in the dev overlay. Verify the prod overlay has
   it unset.
2. **Local FS write failed** — check `CONFIGURATION_SERVICE_SNAPSHOT_LOCAL_DIR`
   is a writable volume.

**Likely root causes (long-poll saturation):**

1. **One or more subscribers are hot-looping** — check the
   `configuration_longpoll_connections` label `route`.
2. **HPA is not scaling up** — check the HPA's
   `currentReplicas` vs `desiredReplicas`.

**Resolution (snapshot):** clear `suspend: true`, scale the CronJob
manually if needed. Resolution (long-poll): scale the HPA's
`maxReplicas`, or rate-limit the long-poll endpoint.

---

## On-call Handbook

- Slack: `#configuration-service-oncall`
- PagerDuty: `config-prod`
- Dashboard: [Grafana configuration-service overview](https://grafana.trips-enjoy.com/d/configuration-service)
- Logs: `kubectl -n configuration-service logs -l app.kubernetes.io/name=configuration-service`
- Traces: [Tempo `service=configuration-service`](https://tempo.trips-enjoy.com/search?service=configuration-service)