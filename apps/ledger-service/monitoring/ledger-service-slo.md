# ledger-service — SLOs / Error Budget

This document defines the Service Level Objectives (SLOs) and Service Level
Indicators (SLIs) for `ledger-service`, per `docs/services/ledger-service/SRS §6`
(NFRs) and `docs/services/ledger-service/README §15` (observability).

## SLOs

| ID | SLO | SLI | Window | Error budget |
|----|-----|-----|--------|--------------|
| SLO--001 | Posting p99 latency ≤ 100 ms | `ledger:posting_p99_seconds` | 30d rolling | 0.1% of postings > 100 ms |
| SLO--002 | Balance read p99 ≤ 50 ms | `ledger:balance_read_p99_seconds` | 30d rolling | 0.1% of reads > 50 ms |
| SLO--003 | Report p99 ≤ 5 s | `ledger:report_p99_seconds` | 30d rolling | 0.1% of reports > 5 s |
| SLO--004 | Service uptime ≥ 99.95% | `ledger:error_ratio_5m` (sum across windows) | 30d rolling | 0.05% downtime ≈ 21 min/month |
| SLO--005 | Trial balance drift = 0 | `ledger:reconciliation_drift_count_24h` | per-day | 0 drift events |
| SLO--006 | Reconciliation runs ≥ 99% on schedule | `LedgerServiceReconciliationJobMissing` (absence) | 30d | 0 missed runs |

## Error budget burn rates

| Burn rate | Window | Severity | Action |
|-----------|--------|----------|--------|
| 1x (sustainable) | 1h | info | continue |
| 2x | 30m | warning | post in `#ledger-finance` |
| 5x | 10m | warning | page `@oncall-financial` |
| 10x | 5m | critical | page `@oncall-financial` + freeze non-critical deploys |

## Multi-window burn-rate alerting

```
# 2% budget consumption in 1h → warning
(1 - ledger:error_ratio_5m) < (1 - 0.9995) * 2 * 1 / 720

# 5% budget consumption in 6h → warning
(1 - ledger:error_ratio_5m) < (1 - 0.9995) * 5 * 6 / 720
```

The `PrometheusRule` (see `ledger-service-servicemonitor.yaml`) wires
these burn rates to Alertmanager severity labels.

## Monthly review

The platform SRE team reviews error-budget consumption every month.
Decisions to make:

- **Within budget**: continue as normal.
- **>80% consumed**: freeze non-critical merges; review recent incident
  trends; consider increasing the budget for the next month.
- **>100% consumed**: explicit customer-facing incident; freeze all merges
  until the budget is reset (next month) or restored (next quarter).
