#!/usr/bin/env bash
#
# gen-status-md.sh — generate docs/services/<svc>/STATUS.md from canonical
# sources. Run from the repo root. Usage:
#
#     scripts/gen-status-md.sh <service>
#     scripts/gen-status-md.sh --all
#
# What it composes (sources of truth — see docs/PLAN_INDEX.md "STATUS.md
# composition contract"):
#
#   §1 Identity            — services/README.md + <svc>/README.md §1–2
#   §2 Tech profile        — <svc>/TECH.md + RECOMMENDATIONS.md §2
#   §3 Implementation      — DEPLOYMENT_ORDER.md §8.2 (verbatim row)
#   §4 Doc completeness    — filesystem scan + git log -1 per file
#   §5 Contract snapshot   — <svc>/INTEGRATION.md §1–4
#   §6 Security / RBAC     — <svc>/TECH.md §10 + RECOMMENDATIONS.md §6.2a
#   §7 Plan snapshot       — <svc>/PLAN.md header + phase-block grep
#   §8 Cross-links         — fixed per template
#
# Output is a markdown file with exactly 8 numbered sections (the
# `make status-md-check` target asserts this shape).
#
# This is a *helper*, not the authoritative source. Every value either
# is a pointer to a canonical source or is a verbatim copy of a canonical
# value (e.g. the lifecycle row text from DEPLOYMENT_ORDER.md §8.2).
#
# The script intentionally leaves some fields blank ("—") when not
# documented for that service; this is correct for stub services.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# 21 active services (canonical catalog)
ALL_SERVICES=(
    admin-service api-gateway audit-service chat-service configuration-service
    courier-service customer-service driver-service file-service food-order-service
    fraud-risk-service geolocation-service identity-service ledger-service
    notification-service payment-service pricing-service reporting-service
    restaurant-service search-service trip-service
)

usage() {
    cat >&2 <<EOF
Usage: $0 <service>     # generate STATUS.md for one service
       $0 --all         # generate STATUS.md for every service in the catalog
       $0 --check       # verify all 21 STATUS.md files conform (alias for 'make status-md-check')
EOF
    exit 1
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

last_updated() {
    # git log --format=%ci → YYYY-MM-DD; fall back to "—" if not tracked
    local path="$1"
    local raw
    raw=$(git log -1 --format=%ci -- "$path" 2>/dev/null || true)
    if [ -z "$raw" ]; then echo "—"; else echo "${raw%% *}"; fi
}

# Extract the Implementation lifecycle row text from DEPLOYMENT_ORDER.md §8.2
lifecycle_row() {
    local svc="$1"
    awk -v svc="$svc" '
        /^### 8\.2 Per-service graduate checklist/ { flag=1; next }
        flag && /^\| [0-9]+ \|/ {
            # split on | and pull column 2 (service name); if matches, print whole row
            n=split($0, a, "|")
            svc_cell=a[3]
            gsub(/^ +| +$/, "", svc_cell)
            gsub(/`/, "", svc_cell)
            if (svc_cell == svc) { print; exit }
        }
    ' docs/DEPLOYMENT_ORDER.md
}

bounded_context() {
    # README.md §2 first non-empty line after the heading; strip various
    # leading prefixes services use:
    #   "Bounded context: <X>"
    #   "**Bounded context**: <X>"
    #   "**Bounded context:** <X>"
    local svc="$1"
    awk '
        /^## 2\. Bounded Context/ { flag=1; next }
        flag && /^## 3\./ { exit }
        flag && NF { print; exit }
    ' "docs/services/$svc/README.md" \
        | sed -E 's/^\*\*Bounded context\*\*:[[:space:]]*//' \
        | sed -E 's/^\*\*Bounded context\*\*:[[:space:]]*//' \
        | sed -E 's/^Bounded context:[[:space:]]*//' \
        | sed -E 's/^[[:space:]]+//' \
        | head -c 200
}

domain_of() {
    # From PLAN.md header line 1: **Domain:** X
    local svc="$1"
    grep -E '^\*\*Domain:\*\*' "docs/services/$svc/PLAN.md" 2>/dev/null | sed -E 's/^\*\*Domain:\*\* //' || echo "—"
}

tier_criticality_of() {
    local svc="$1"
    awk '
        /^\*\*Tier:\*\*/ { tier=$0 }
        /^\*\*Criticality:\*\*/ { crit=$0 }
        END { print tier "\n" crit }
    ' "docs/services/$svc/PLAN.md" 2>/dev/null | sed -E 's/^\*\*Tier:\*\* //; s/^\*\*Criticality:\*\* //' || true
}

position_of() {
    # From DEPLOYMENT_ORDER.md §2 — column 2 of the per-service deployment table.
    # §2 has 4 tier-grouped sub-tables; walk all of them.
    local svc="$1"
    awk -v svc="$svc" '
        /^## 2\./ { flag=1 }
        /^## 3\./ { flag=0 }
        flag && /^\| [0-9]+ \|/ {
            n=split($0, a, "|")
            svc_cell=a[3]
            gsub(/^ +| +$/, "", svc_cell)
            gsub(/`/, "", svc_cell)
            if (svc_cell == svc) { gsub(/^ +| +$/, "", a[2]); print a[2]; exit }
        }
    ' docs/DEPLOYMENT_ORDER.md
}

tech_profile() {
    local svc="$1"
    awk '
        /^## 1\. Runtime/ { flag=1 }
        flag && /^## 2\./ { exit }
        flag
    ' "docs/services/$svc/TECH.md" 2>/dev/null | sed -nE 's/^\| \*\*(Language|Framework|Profile|Container)\*\* \| (.*) \|$/\1: \2/p' | head -10 || echo "—"
}

hpa_of() {
    # From PLAN.md header
    grep -E '^\*\*HPA:\*\*' "docs/services/$1/PLAN.md" 2>/dev/null | sed -E 's/^\*\*HPA:\*\* //' || echo "—"
}

cache_of() {
    grep -E '^\*\*Cache:\*\*' "docs/services/$1/PLAN.md" 2>/dev/null | sed -E 's/^\*\*Cache:\*\* //' || echo "—"
}

schema_of() {
    grep -E '^\*\*DB Schema:\*\*' "docs/services/$1/PLAN.md" 2>/dev/null | sed -E 's/^\*\*DB Schema:\*\* //' || echo "—"
}

phase_blocks() {
    local svc="$1"
    local has_70="—" has_75="—" has_76="—" has_77="—"
    if grep -qE '^### Phase 7\.0' "docs/services/$svc/PLAN.md" 2>/dev/null; then has_70="✅ present"; fi
    if grep -qE '^### Phase 7\.5' "docs/services/$svc/PLAN.md" 2>/dev/null; then has_75="✅ present"; fi
    if grep -qE '^### Phase 7\.6' "docs/services/$svc/PLAN.md" 2>/dev/null; then has_76="✅ present"; fi
    if grep -qE '^### Phase 7\.7' "docs/services/$svc/PLAN.md" 2>/dev/null; then has_77="✅ present"; fi
    printf '%s\n%s\n%s\n%s\n' "$has_70" "$has_75" "$has_76" "$has_77"
}

impl_evidence() {
    # apps/<svc>/ presence checks for Dockerfile, k8s/, monitoring/
    local svc="$1"
    local dir="apps/$svc"
    local docker="—" k8s="—" mon="—"
    [ -f "$dir/Dockerfile" ] && docker="✅ present"
    [ -d "$dir/k8s" ] && k8s="✅ present"
    [ -d "$dir/monitoring" ] && mon="✅ present"
    printf '%s\n%s\n%s\n' "$docker" "$k8s" "$mon"
}

contract_counts() {
    local svc="$1"
    local f="docs/services/$svc/INTEGRATION.md"
    [ -f "$f" ] || { echo "—|—|—|—"; return; }
    local inbound outbound produced consumed
    inbound=$(grep -cE '^### 1\.[0-9]+ ' "$f" 2>/dev/null || echo 0)
    # Outbound APIs are listed in §2 as a table; count rows after the §2 header
    outbound=$(awk '/^## 2\. Outbound APIs/,/^## 3\./' "$f" | grep -cE '^\| .* \| (POST|GET|PUT|PATCH|DELETE) \|' 2>/dev/null || echo 0)
    produced=$(grep -cE '^### 3\.[0-9]+ ' "$f" 2>/dev/null || echo 0)
    consumed=$(grep -cE '^### 4\.[0-9]+ ' "$f" 2>/dev/null || echo 0)
    printf '%s|%s|%s|%s\n' "$inbound" "$outbound" "$produced" "$consumed"
}

# ---------------------------------------------------------------------------
# Main: emit one STATUS.md
# ---------------------------------------------------------------------------

emit_status_md() {
    local svc="$1"
    local path="docs/services/$svc/STATUS.md"
    local today
    today=$(date -u +%Y-%m-%d)
    local bc domain tier crit position
    bc=$(bounded_context "$svc")
    domain=$(domain_of "$svc")
    # Read tier + criticality via mapfile to avoid IFS-mangling in pipelines
    local tc_lines=()
    mapfile -t tc_lines < <(tier_criticality_of "$svc")
    tier="${tc_lines[0]}"
    crit="${tc_lines[1]}"
    # Strip the "**Tier:** " / "**Criticality:** " markers
    tier="${tier#\*\*Tier:\*\* }"
    crit="${crit#\*\*Criticality:\*\* }"
    position=$(position_of "$svc")
    [ -z "$position" ] && position="?"
    local tech_block
    tech_block=$(tech_profile "$svc")
    local hpa cache schema
    hpa=$(hpa_of "$svc")
    cache=$(cache_of "$svc")
    schema=$(schema_of "$svc")
    local p70 p75 p76 p77
    { read -r p70; read -r p75; read -r p76; read -r p77; } < <(phase_blocks "$svc")
    local docker k8s mon
    { read -r docker; read -r k8s; read -r mon; } < <(impl_evidence "$svc")
    local inbound outbound produced consumed
    { IFS='|' read -r inbound outbound produced consumed; } < <(contract_counts "$svc")

    # Lifecycle row text (verbatim copy of DEPLOYMENT_ORDER.md §8.2 row)
    local lifecycle_line
    lifecycle_line=$(lifecycle_row "$svc")
    local status_text="⏳ Stub"
    if echo "$lifecycle_line" | grep -q "✅ Graduated"; then status_text="✅ Graduated"; fi

    # Doc-completeness table — files present in docs/services/<svc>/
    local dc_rows=""
    local f
    for f in README.md BRD.md SRS.md ERD.md INTEGRATION.md WORKFLOWS.md TECH.md PLAN.md; do
        local present="—" lu="—"
        if [ -f "docs/services/$svc/$f" ]; then
            present="✅"
            lu=$(last_updated "docs/services/$svc/$f")
        fi
        dc_rows+=$'| `'"$f"'` | ✅ mandatory | '"$present"' | '"$lu"' |'$'\n'
    done
    # SKELETON — extension differs per language
    local skeleton_ext="gradle.kts"
    case "$svc" in
        api-gateway|chat-service|file-service|geolocation-service) skeleton_ext="go.mod" ;;
        fraud-risk-service|reporting-service) skeleton_ext="pyproject.toml" ;;
    esac
    local skel_present="—" skel_lu="—"
    if [ -f "docs/services/$svc/SKELETON.$skeleton_ext" ]; then
        skel_present="✅"
        skel_lu=$(last_updated "docs/services/$svc/SKELETON.$skeleton_ext")
    fi
    dc_rows+=$'| `SKELETON.'"$skeleton_ext"'` | ✅ mandatory | '"$skel_present"' | '"$skel_lu"' |'$'\n'
    dc_rows+=$'| `STATUS.md` (this file) | ✅ mandatory (new) | ✅ | '"$today"' |'$'\n'

    cat > "$path" <<HEADER
# $svc — Status Snapshot

> **Composition page.** This file is a reader-rendered
> composition of fields from the canonical sources below.
> When any source changes, regenerate this file (see
> [\`PLAN_INDEX.md\`](../../PLAN_INDEX.md) "STATUS.md
> composition contract" for the contract and the doc-QA
> invariants).
>
> **Canonical sources for each field** (in order of
> preference; never duplicate the value — link to it):
>
> | Field group | Source of truth |
> |---|---|
> | Identity | [\`docs/services/README.md\`](../README.md) + [\`README.md\`](./README.md) §1–2 |
> | Tech profile | [\`TECH.md\`](./TECH.md) + [\`docs/services/RECOMMENDATIONS.md\`](../RECOMMENDATIONS.md) §2 |
> | Implementation lifecycle | [\`docs/DEPLOYMENT_ORDER.md\` §8.2](../../DEPLOYMENT_ORDER.md) |
> | Documentation completeness | filesystem scan (\`docs/services/$svc/\`) |
> | Contract snapshot | [\`INTEGRATION.md\`](./INTEGRATION.md) + [\`docs/SERVICE_INTEGRATION_MATRIX.md\`](../../SERVICE_INTEGRATION_MATRIX.md) |
> | Security / RBAC | [\`TECH.md\`](./TECH.md) §10 + [\`docs/services/RECOMMENDATIONS.md\`](../RECOMMENDATIONS.md) §6.2a |
> | Plan snapshot | [\`PLAN.md\`](./PLAN.md) |

## 1. Identity

| Field | Value |
|---|---|
| Service name (kebab-case) | \`$svc\` |
| Bounded context | $bc |
| Domain | $domain |
| Tier (deployment) | ${tier:-—} (position ${position:-—} of 21; \`DEPLOYMENT_ORDER.md\` §2) |
| Criticality / SLO | ${crit:-—} |
| Owner team | — |

## 2. Tech profile

| Field | Value | Source |
|---|---|---|
| Language | — | \`TECH.md\` §1 |
| Framework | — | \`TECH.md\` §1 |
| Profile | — | \`RECOMMENDATIONS.md\` §1 |
| DB schema | $schema (per-service) | \`services/README.md\` env-var table |
| Cache | $cache | \`TECH.md\` §4 |
| HPA signal | $hpa | \`TECH.md\` §8 |
| Replicas (default) | — | \`TECH.md\` §8 |
| p99 latency target | — | \`TECH.md\` §8 |
| Image | \`registry.trips-enjoy.com/$svc:<sha>\` | \`README.md\` §18 |
| Container port | 8080 | \`TECH.md\` §1 |
| Health endpoints | \`/actuator/health/liveness\`, \`/actuator/health/readiness\` | \`TECH.md\` §7 |
| \`.env.example\` | \`apps/$svc/.env.example\` $([ -f "apps/$svc/.env.example" ] && echo '✅' || echo '—') | filesystem |

## 3. Implementation lifecycle

> Source of truth: [\`DEPLOYMENT_ORDER.md\` §8.2](../../DEPLOYMENT_ORDER.md).
> Row copied verbatim from §8.2:

\`\`\`
$lifecycle_line
\`\`\`

| Field | Value |
|---|---|
| Status | $status_text |
| \`apps/$svc/\` Dockerfile | $docker |
| \`apps/$svc/k8s/\` (flat kustomize overlays) | $k8s |
| \`apps/$svc/monitoring/\` (ServiceMonitor + PrometheusRule) | $mon |
| Local test suite | (see §8.2 row above) |
| Implementation memory | $([ "$status_text" = "✅ Graduated" ] && echo "see \`uber-$svc-implementation-*.md\` in project memory index" || echo "—") |

## 4. Documentation completeness

| File | Required? | Present? | Last updated |
|---|---|---|---|
HEADER

    # Append the dynamically-built rows
    printf "%b" "$dc_rows" >> "$path"

    cat >> "$path" <<TAIL

## 5. Contract snapshot

> Sources: [\`INTEGRATION.md\`](./INTEGRATION.md) §1–4 and
> [\`SERVICE_INTEGRATION_MATRIX.md\`](../../SERVICE_INTEGRATION_MATRIX.md).

| Field | Count / Value |
|---|---|
| Inbound REST APIs | $inbound (full contract in INTEGRATION.md §1) |
| Outbound REST APIs | $outbound (INTEGRATION.md §2) |
| Produced events | $produced (INTEGRATION.md §3) |
| Consumed events | $consumed (INTEGRATION.md §4) |
| Sync deps | see \`SERVICE_INTEGRATION_MATRIX.md\` row |
| Workflows participated | see \`services/README.md\` "By workflow participation" |

## 6. Security / RBAC

| Field | Value |
|---|---|
| AuthN | Bearer JWT (Keycloak) per \`TECH.md\` §6 |
| AuthZ | RBAC; admin role \`$svc.admin\` per \`TECH.md\` §10 |
| SUPER_ADMIN preset | ✅ member of the 22-role preset (\`platform.super_admin\` + 21 × \`<service>.admin\`) per \`services/RECOMMENDATIONS.md\` §6.2a |
| Time-bounded alias | \`platform-internal\` realm \`service-claims\` scope mappers (per identity-service per-service claim contract); \`$svc.scopes\` / \`$svc.level\` / \`$svc.tenant\` claims available |

## 7. Plan snapshot

> Source: [\`PLAN.md\`](./PLAN.md) (header lines 3–9 + phase blocks).

| Field | Value |
|---|---|
| Plan header | Domain: $domain / Tier: ${tier:-—} / DB Schema: $schema / Cache: $cache / HPA: $hpa |
| Phase 7.0 (cross-cutting) block | $p70 |
| Phase 7.5 (Make-a-Deal kernel) block | $p75 |
| Phase 7.6 (Conductor workers) block | $p76 |
| Phase 7.7 (in-app chat) block | $p77 |
| Plan task total | (count of \`T-$svc\`-prefixed rows in \`PLAN.md\`) |
| Plan task status | pending: all · in_progress: 0 · done: 0 · blocked: 0 (PLAN.md task tables are all \`pending\` today) |

## 8. Cross-links

- **Sibling docs**: [README](./README.md) · [BRD](./BRD.md) · [SRS](./SRS.md) · [ERD](./ERD.md) · [INTEGRATION](./INTEGRATION.md) · [WORKFLOWS](./WORKFLOWS.md) · [TECH](./TECH.md) · [PLAN](./PLAN.md) · [SKELETON.$skeleton_ext](./SKELETON.$skeleton_ext)
- **Platform-wide**: [\`services/README.md\`](../README.md) · [\`MICROSERVICES_MAP.md\`](../../architecture/MICROSERVICES_MAP.md) · [\`SERVICE_INTEGRATION_MATRIX.md\`](../../SERVICE_INTEGRATION_MATRIX.md) · [\`DEPLOYMENT_ORDER.md\` §8](../../DEPLOYMENT_ORDER.md) · [\`RECOMMENDATIONS.md\`](../RECOMMENDATIONS.md) §6.2a
- **Implementation memory** (graduates only): \`uber-$svc-implementation-<date>.md\` (project memory index)
TAIL

    echo "wrote: $path"
}

# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if [ $# -eq 0 ]; then usage; fi
case "$1" in
    --all)
        for svc in "${ALL_SERVICES[@]}"; do emit_status_md "$svc"; done
        ;;
    --check) exec make status-md-check ;;
    -h|--help) usage ;;
    *)
        emit_status_md "$1"
        ;;
esac
