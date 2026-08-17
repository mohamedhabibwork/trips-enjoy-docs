# ledger-service — Operational Runbook

This runbook covers the high-impact alert paths for `ledger-service`. Each
section maps alert → triage → escalation. Pair with
`ledger-service-slo.md` and the PrometheusRule in
`ledger-service-servicemonitor.yaml`.

## 1. Posting latency p99 > 100 ms (SLO breach)

**Alert**: `LedgerServicePostingLatencyP99` (severity: critical).

**Triage**:

```bash
# 1. Check current posting p99 latency + per-account-type breakdown.
ledger:posting_p99_seconds
ledger_posting_seconds_count{account_type=~".+"}

# 2. Check Hikari connection pool — saturation = lock contention.
hikari_pool_pending
hikari_pool_usage

# 3. Check DB CPU + connection count.
node_cpu_usage{namespace="ledger-service"}
pg_stat_activity_count{state="active"}

# 4. Check Kafka consumer lag (a stalled consumer can starve
# the application thread pool).
ledger:consumer_lag_total
```

**Common causes** (in order of likelihood):

1. **DB connection pool exhausted** — Hikari pool size is 20 with a 30 s
   timeout. Look for `pending > 0`; raise `spring.datasource.hikari.maximum-pool-size`
   or fix the underlying slow query.
2. **Single-account hot row** — a single `account_code` is being
   hammered by concurrent postings. Check `topk(10, sum by(account_code) (rate(ledger_postings_total[5m])))`.
3. **GC pause storm** — `jvm_gc_pause_seconds_p99 > 0.05`. Heap dump
   (`jcmd <pid> GC.heap_dump /tmp/heapdump.hprof`) and analyse with
   Eclipse MAT.

**Escalate**: `@oncall-financial` if not resolved in 30 min.

## 2. Reconciliation drift detected

**Alert**: `LedgerServiceReconciliationDrift` (severity: critical,
p1_ticket).

**Triage**:

```bash
# 1. Look at the most recent reconciliation_runs row.
psql -d trips_enjoy -c "SELECT run_date, drift_minor, status, details
                          FROM ledger.reconciliation_runs
                         ORDER BY run_date DESC LIMIT 1;"

# 2. Per-account-type totals (the drift breakdown).
psql -d trips_enjoy -c "
SELECT a.type,
       sum(CASE WHEN pe.side='debit'  THEN pe.amount_minor ELSE 0 END) AS debit,
       sum(CASE WHEN pe.side='credit' THEN pe.amount_minor ELSE 0 END) AS credit,
       sum(CASE WHEN pe.side='debit'  THEN pe.amount_minor ELSE 0 END)
       - sum(CASE WHEN pe.side='credit' THEN pe.amount_minor ELSE 0 END) AS net
  FROM ledger.posting_entries pe
  JOIN ledger.accounts a ON a.code = pe.account_code AND a.valid_to IS NULL
 WHERE pe.posted_at >= CURRENT_DATE - 7
 GROUP BY a.type
 ORDER BY a.type;
"

# 3. Compare against the operational layer totals (the `details` JSONB
# column stores per-layer sums; query each via the operational service).
```

**Common causes**:

1. **Missing event consumer offset** — a money-movement event was
   published by `payment-service` / `payment-service (wallet)` etc.
   but the consumer's `inbox` row was lost. Inspect
   `ledger.inbox` for the day of the drift.
2. **Out-of-order posting** — a posting for an earlier date was
   committed after a posting for a later date, breaking the
   per-day balance. Inspect `ledger.postings` for the day.
3. **Currency conversion error** — a posting used a wrong currency,
   silently breaking the per-currency totals. Check the `currency` column
   on `posting_entries` for the day.

**Escalate**: P1 ticket in `#finance-incidents`; page `@oncall-financial`
+ `@finance-team-lead`.

## 3. Outbox lag > 60 s

**Alert**: `LedgerServiceOutboxLagCritical` (severity: critical).

**Triage**:

```bash
# 1. Check outbox publisher health.
ledger:outbox_lag_seconds_max
ledger_outbox_oldest_unpublished_seconds

# 2. Check Kafka broker reachability + acks.
nc -z kafka.trips-enjoy.svc.cluster.local 9092
ledger_kafka_send_seconds_count

# 3. Inspect the unpublished outbox rows.
psql -d trips_enjoy -c "
SELECT id, topic, attempts, last_error, created_at
  FROM ledger.outbox
 WHERE published_at IS NULL
 ORDER BY created_at ASC
 LIMIT 20;
"
```

**Common causes**:

1. **Kafka broker down / unreachable** — broker outage; wait for
   recovery or restart the publisher.
2. **Slow consumer** — the broker is healthy but a downstream consumer
   (audit / reporting) is slow; the publisher is back-pressured. Check
   `ledger:consumer_lag_total`.
3. **Topic-level quota exhausted** — the producer is rate-limited;
   check Kafka broker logs for `QuotaViolationException`.

**Recovery**:

```bash
# Force a publish cycle (skip the next 1 s wait):
kubectl exec -it ledger-service-0 -c ledger-service -- \
  curl -X POST http://localhost:8087/admin/v1/ledger/reconciliation/run
```

## 4. Kafka consumer lag > 10000

**Alert**: `LedgerServiceKafkaConsumerLag` (severity: warning).

**Triage**:

```bash
ledger:consumer_lag_total
```

**Common causes**:

1. **Slow downstream consumer** (audit / reporting). The ledger is
   publishing but consumers are slow.
2. **Insufficient replicas** — the ledger consumer is single-partitioned
   per topic; replicas > partitions doesn't help. Verify topic
   partition count.
3. **Single partition back-pressure** — increase topic partitions if
   the message rate is sustained.

## 5. JVM heap > 85%

**Alert**: `LedgerServiceHeapPressure` (severity: warning).

**Triage**:

```bash
# 1. Trigger a heap dump.
kubectl exec -it ledger-service-0 -c ledger-service -- \
  jcmd 1 GC.heap_dump /tmp/heapdump.hprof

# 2. Copy the heap dump out.
kubectl cp ledger-service-0:/tmp/heapdump.hprof ./ledger-service-heap.hprof

# 3. Open in Eclipse MAT.
```

**Common causes**: large Kafka consumer batches; large in-memory caches;
slow GC due to inefficient String allocations.

## 6. Reconciliation job missing

**Alert**: `LedgerServiceReconciliationJobMissing` (severity: critical).

**Triage**:

```bash
# 1. Confirm the CronJob exists + has a recent Job.
kubectl get cronjobs -n ledger-service ledger-service-reconciliation
kubectl get jobs -n ledger-service -l app.kubernetes.io/component=ledger

# 2. Inspect the last Job's status.
kubectl describe job -n ledger-service -l app.kubernetes.io/job=reconciliation

# 3. Manually trigger the reconciliation.
kubectl create job -n ledger-service manual-reconciliation \
  --from=cronjob/ledger-service-reconciliation
```

**Common causes**: CronJob suspended; image pull failure; concurrency
policy prevented the latest run.

## 7. Daily partition maintenance missing

**Alert**: `LedgerServicePartitionMaintenanceJobMissing` (severity: warning).

**Triage**: same as #6 but for `ledger-service-retention`. Without this
job, INSERTs will fail with "no partition of relation found" once the
pre-created partitions roll past today's date.

```bash
kubectl create job -n ledger-service manual-retention \
  --from=cronjob/ledger-service-retention
```

## 8. Pod crashlooping

**Alert**: `LedgerServicePodCrashlooping` (severity: critical).

**Triage**:

```bash
kubectl logs -n ledger-service ledger-service-<pod-id> --previous
kubectl describe pod -n ledger-service ledger-service-<pod-id>
```

**Common causes**: OOMKilled (heap pressure); database migration failed
(pods can't start until `ledger-service-migrate` Job succeeds); Kafka
broker unreachable.
