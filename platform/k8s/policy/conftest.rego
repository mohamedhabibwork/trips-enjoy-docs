# Platform k8s reference-tree policy.
#
# Rego rules for `conftest` (https://www.conftest.dev) that enforce the
# documented conventions:
#   - Every Service is type: ClusterIP (no NodePort/LoadBalancer/Ingress)
#   - Every Pod has runAsNonRoot: true + runAsUser >= 10001 (in-house images)
#   - Every Deployment that exposes Prometheus metrics has the
#     prometheus.io/scrape=true annotation
#   - Every container in a Deployment has resource requests
#   - Every container in a Deployment has capabilities drop ALL
#   - Every container in a Deployment has readOnlyRootFilesystem: true
#   - Every Pod has a seccompProfile.type set
#   - Every PriorityClass referenced by a Deployment is defined in the tree
#   - Every image reference uses the registry.trips-enjoy.com/ prefix
#     (or is an upstream platform image — postgres, redis, kafka, etc.)
#
# Wire into the root Makefile via `make k8s-lint`:
#   conftest test --policy platform/k8s/policy/ \
#     $(find platform/k8s/infra platform/k8s/services -name '*.yaml' -not -path '*/patches/*')
package kubernetes.admission

import future.keywords.in
import future.keywords.if
import future.keywords.contains

# ---------------------------------------------------------------------
# 1. Every Service must be ClusterIP.
# ---------------------------------------------------------------------
deny[msg] {
  input.kind == "Service"
  input.spec.type != "ClusterIP"
  msg := sprintf("Service %s/%s must be type=ClusterIP (got %s)", [input.metadata.namespace, input.metadata.name, input.spec.type])
}

# ---------------------------------------------------------------------
# 2. Every Pod must have runAsNonRoot: true.
# ---------------------------------------------------------------------
deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  not input.spec.template.spec.securityContext.runAsNonRoot
  msg := sprintf("%s %s/%s must set runAsNonRoot: true on the pod spec", [input.kind, input.metadata.namespace, input.metadata.name])
}

# ---------------------------------------------------------------------
# 3. Every Pod must have runAsUser >= 10001 OR be an upstream-platform
#    image (postgres/redis/kafka/keycloak/prometheus).
# ---------------------------------------------------------------------
platform_image_prefixes := {"postgres:", "redis:", "apache/kafka:", "quay.io/keycloak/", "prom/", "minio/minio:", "conductor-cinema/", "docker.elastic.co/", "grafana/", "grafana/loki:", "otel/opentelemetry-collector", "busybox:", "oliver006/redis_exporter"}

deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  uid := input.spec.template.spec.securityContext.runAsUser
  uid < 10001
  not is_platform_image(container.image)
  msg := sprintf("Container %s in %s/%s has runAsUser=%d (<10001) and is not a platform image", [container.name, input.metadata.namespace, input.metadata.name, uid])
}

is_platform_image(image) {
  prefix := platform_image_prefixes[_]
  startswith(image, prefix)
}

# ---------------------------------------------------------------------
# 4. Every Pod must have a seccompProfile.
# ---------------------------------------------------------------------
deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  not input.spec.template.spec.securityContext.seccompProfile
  msg := sprintf("%s %s/%s must set seccompProfile.type on the pod spec", [input.kind, input.metadata.namespace, input.metadata.name])
}

# ---------------------------------------------------------------------
# 5. Every container in a Deployment must drop ALL capabilities.
# ---------------------------------------------------------------------
deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  not container.securityContext.capabilities.drop
  msg := sprintf("Container %s in %s/%s must set securityContext.capabilities.drop", [container.name, input.metadata.namespace, input.metadata.name])
}

deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  container.securityContext.capabilities.drop[_] != "ALL"
  msg := sprintf("Container %s in %s/%s must drop ALL capabilities", [container.name, input.metadata.namespace, input.metadata.name])
}

# ---------------------------------------------------------------------
# 6. Every container must have readOnlyRootFilesystem: true.
# ---------------------------------------------------------------------
deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  not container.securityContext.readOnlyRootFilesystem
  msg := sprintf("Container %s in %s/%s must set readOnlyRootFilesystem: true", [container.name, input.metadata.namespace, input.metadata.name])
}

# ---------------------------------------------------------------------
# 7. Every container must have resource requests + limits.
# ---------------------------------------------------------------------
deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  not container.resources.requests.cpu
  msg := sprintf("Container %s in %s/%s must declare resources.requests.cpu", [container.name, input.metadata.namespace, input.metadata.name])
}

deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  not container.resources.requests.memory
  msg := sprintf("Container %s in %s/%s must declare resources.requests.memory", [container.name, input.metadata.namespace, input.metadata.name])
}

deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  not container.resources.limits.cpu
  msg := sprintf("Container %s in %s/%s must declare resources.limits.cpu", [container.name, input.metadata.namespace, input.metadata.name])
}

deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  not container.resources.limits.memory
  msg := sprintf("Container %s in %s/%s must declare resources.limits.memory", [container.name, input.metadata.namespace, input.metadata.name])
}

# ---------------------------------------------------------------------
# 8. Every bounded-context service Deployment must have a ServiceMonitor
#    (i.e. expose Prometheus metrics).
# ---------------------------------------------------------------------
bounded_context_services := {
  "api-gateway", "identity-service", "audit-service", "configuration-service",
  "notification-service", "admin-service", "reporting-service", "fraud-risk-service",
  "customer-service", "search-service", "driver-service", "trip-service",
  "pricing-service", "restaurant-service", "food-order-service", "courier-service",
  "geolocation-service", "payment-service", "ledger-service", "chat-service",
  "file-service"
}

warn[msg] {
  input.kind == "Deployment"
  input.metadata.name in bounded_context_services
  not input.spec.template.metadata.annotations["prometheus.io/scrape"]
  msg := sprintf("Deployment %s/%s is missing prometheus.io/scrape annotation (no ServiceMonitor will target it)", [input.metadata.namespace, input.metadata.name])
}

# ---------------------------------------------------------------------
# 9. Every PriorityClass referenced by a Deployment must be defined
#    somewhere in the tree.
# ---------------------------------------------------------------------
defined_priority_classes := {pc |
  pc := input.metadata.name
  input.kind == "PriorityClass"
}

warn[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  pc := input.spec.template.spec.priorityClassName
  pc != ""
  not pc in defined_priority_classes
  msg := sprintf("%s %s/%s references PriorityClass %q but no PriorityClass by that name exists in the tree", [input.kind, input.metadata.namespace, input.metadata.name, pc])
}

# ---------------------------------------------------------------------
# 10. Every image must use the registry.trips-enjoy.com/ prefix OR be a
#     known upstream platform image.
# ---------------------------------------------------------------------
deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  not startswith(container.image, "registry.trips-enjoy.com/")
  not is_platform_image(container.image)
  msg := sprintf("Container %s in %s/%s uses image %s which is not from registry.trips-enjoy.com and is not a platform image", [container.name, input.metadata.namespace, input.metadata.name, container.image])
}

# ---------------------------------------------------------------------
# 11. Every Secret-backed Deployment envFrom must use either the
#     hand-written <service>-runtime or the ExternalSecret-synced
#     <service>-runtime (same name). Hardcoded secrets in env values
#     are forbidden.
# ---------------------------------------------------------------------
sensitive_keyword := {"password", "secret", "key", "token", "credential"}

deny[msg] {
  input.kind == "Deployment" || input.kind == "StatefulSet"
  container := input.spec.template.spec.containers[_]
  env := container.env[_]
  is_sensitive(env.name)
  startswith(env.value, "")
  not env.valueFrom
  msg := sprintf("Container %s in %s/%s has hardcoded env var %s — use envFrom.secretRef or env.valueFrom", [container.name, input.metadata.namespace, input.metadata.name, env.name])
}

is_sensitive(name) {
  keyword := sensitive_keyword[_]
  contains(lower(name), keyword)
}
