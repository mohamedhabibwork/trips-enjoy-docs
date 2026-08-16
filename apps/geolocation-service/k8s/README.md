# geolocation-service Kubernetes manifests

This directory is the discoverability entry point for the service's
Kubernetes deployment assets.

## Files

| File | Purpose |
|---|---|
| [`geolocation-service.yaml`](./geolocation-service.yaml) | Single multi-document manifest for `ServiceAccount`, `ConfigMap`, `Service`, `Deployment`, `HPA`, `PDB`, and pre-upgrade migration `Job`. |

## Related tooling

- [`../Makefile`](../Makefile) — `k8s-dryrun` and `k8s-validate` targets.
- [`../../../docs/services/geolocation-service/README.md`](../../../docs/services/geolocation-service/README.md) — service overview and deployment notes.
- [`../../../docs/services/geolocation-service/TECH.md`](../../../docs/services/geolocation-service/TECH.md) — technology profile and runtime assumptions.
- [`../../../docs/services/geolocation-service/PLAN.md`](../../../docs/services/geolocation-service/PLAN.md) — deployment phase tasks.
