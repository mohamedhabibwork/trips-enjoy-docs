# Skills Quality Gate

All skills in this directory guide code changes and MUST enforce the following
quality gate in addition to their service-specific requirements:

- Read the local app `AGENTS.md` and the matching service documentation before
  implementation; documentation contracts are authoritative. Read
  `docs/architecture/ARCHITECTURE.md` first for any service implementation or
  contract work, then the relevant architecture companion documents.
- For service work, use the matching documentation set at
  `../docs/services/<service-name>/`: `README.md`, `BRD.md`, `SRS.md`,
  `ERD.md`, `INTEGRATION.md`, `WORKFLOWS.md`, `TECH.md`, and `PLAN.md`.
  Link implementation decisions and externally visible changes back to those
  artifacts; update every affected contract document in the same change.
- Use the main architecture as a hard guardrail: preserve the 20-service
  bounded-context model, database-per-service ownership, REST/Kafka boundaries,
  eventual consistency across services, and the documented security and
  resilience mechanisms. Never introduce cross-service database access or
  foreign keys.
- Keep code cohesive, explicit, typed where supported, and free of unrelated
  refactors. Preserve clean separation between boundaries, domain logic,
  persistence, integrations, and configuration.
- Format changed files; run the configured linter/static analysis and the
  narrowest relevant tests. Add regression tests for behavior, validation,
  authorization, error paths, idempotency, and changed state transitions.
- Do not suppress linter findings, weaken tests, commit generated output, or
  bypass security checks to produce a green build.
- Verify secure handling of input, authentication, authorization, secrets,
  sensitive data, logs, errors, and dependencies before handoff. Report checks
  that cannot run with the reason.
- Ensure every changed HTTP API remains fully represented in its service's
  generated OpenAPI 3 contract (`/openapi.json`) and Swagger UI (`/docs`). Keep
  `/v1` paths, schemas, canonical errors, security requirements, idempotency,
  and correlation headers synchronized with the SRS and `INTEGRATION.md`; add
  focused tests for those documentation endpoints when applicable.

## Service Setup Gate

When creating or materially extending a service, ensure its framework-native
source and test directories are present, and set up the following operational
assets where the service is deployed:

- **Testing:** add focused unit tests plus boundary tests for validation,
  authorization, canonical errors, idempotency, state transitions, and changed
  API documentation endpoints. Add integration tests for persistence, Kafka,
  or downstream contracts when those boundaries change. Run the language-native
  formatter, static analysis, and narrowest relevant test suite.
- **Kubernetes:** maintain `k8s/` manifests or overlays for the service's
  workload, Service, configuration and secret references, resource requests
  and limits, health probes, security context, NetworkPolicy, and autoscaling
  when required by `TECH.md` or the deployment architecture. Never place secret
  values in manifests. Align names, ports, routes, and environment settings with
  the service README, SRS, and `docs/architecture/DEPLOYMENT_ARCHITECTURE.md`.
- **Monitoring:** maintain `monitoring/` assets for service dashboards and
  alerts. Instrument structured logs, OpenTelemetry traces, RED metrics, and
  the service's documented business and dependency-health signals. Preserve
  correlation IDs and redact sensitive data. Align alert thresholds and runbook
  references with `docs/architecture/OBSERVABILITY.md` and the service SRS.

Do not create empty placeholders: add these assets only with meaningful,
service-specific content, and keep their ownership inside that service folder.
