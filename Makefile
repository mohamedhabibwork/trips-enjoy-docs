SHELL := /bin/bash
.SHELLFLAGS := -ec

PYTHON ?= python3
VENV := apps/.venv

GO_SERVICES := api-gateway chat-service file-service geolocation-service
PYTHON_SERVICES := fraud-risk-service reporting-service
SPRING_SERVICES := admin-service audit-service configuration-service courier-service customer-service driver-service food-order-service identity-service ledger-service notification-service payment-service pricing-service restaurant-service search-service trip-service
SERVICES := $(GO_SERVICES) $(PYTHON_SERVICES) $(SPRING_SERVICES)

.DEFAULT_GOAL := help

.PHONY: help setup doctor build build-go build-python build-spring build-service quality quality-go quality-python quality-spring quality-service test test-go test-python test-spring test-service service run clean db-init db-init-service db-migrate db-migrate-service validate-docs status-md-check status-md-regenerate configuration-k8s-validate k8s-lint k8s-build k8s-validate-platform-rules $(SERVICES)

help: ## Show available commands.

	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_-]+:.*##/ {printf "%-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

setup: ## Create the Python environment and install Python service dependencies.

	$(PYTHON) -m venv $(VENV)
	$(VENV)/bin/python -m pip install --upgrade pip
	$(VENV)/bin/python -m pip install -r apps/fraud-risk-service/requirements.txt -r apps/reporting-service/requirements.txt

doctor: ## Verify required local tools; Docker is required for Spring integration tests.

	@command -v go >/dev/null || { echo "Missing required tool: go"; exit 1; }
	@command -v $(PYTHON) >/dev/null || { echo "Missing required tool: $(PYTHON)"; exit 1; }
	@command -v java >/dev/null || { echo "Missing required tool: java (JDK 25 required by Spring services)"; exit 1; }
	@docker info >/dev/null 2>&1 || { echo "Docker is unavailable; Spring integration tests use Testcontainers."; exit 1; }
	@echo "All required tools are available."

build: build-go build-python build-spring ## Build every service scaffold.

build-go: ## Compile all Go services.

	@for service in $(GO_SERVICES); do \
		echo "==> building $$service"; \
		(cd apps/$$service && go build ./...); \
	done

build-python: ## Compile all Python services.

	@for service in $(PYTHON_SERVICES); do \
		echo "==> compiling $$service"; \
		$(VENV)/bin/python -m compileall -q apps/$$service/app; \
	done

build-spring: ## Compile all Spring Boot services without running tests.

	@for service in $(SPRING_SERVICES); do \
		echo "==> building $$service"; \
		(cd apps/$$service && ./gradlew build -x test --no-daemon); \
	done

build-service: ## Build one service, e.g. make build-service SERVICE=api-gateway.

	@test -n "$(SERVICE)" || { echo "Set SERVICE to one of: $(SERVICES)"; exit 1; }
	@case "$(SERVICE)" in \
	  api-gateway|chat-service|file-service|geolocation-service) \
		cd apps/$(SERVICE) && go build ./... ;; \
	  fraud-risk-service|reporting-service) \
		$(VENV)/bin/python -m compileall -q apps/$(SERVICE)/app ;; \
	  admin-service|audit-service|configuration-service|courier-service|customer-service|driver-service|food-order-service|identity-service|ledger-service|notification-service|payment-service|pricing-service|restaurant-service|search-service|trip-service) \
		cd apps/$(SERVICE) && ./gradlew build -x test --no-daemon ;; \
	  *) echo "Unknown SERVICE: $(SERVICE). Choose one of: $(SERVICES)"; exit 1 ;; \
	esac

quality: quality-go quality-python quality-spring ## Run formatter, static analysis, and tests for every service.

quality-go: ## Run Go formatting, vet, and tests for all Go services.

	@for service in $(GO_SERVICES); do \
		echo "==> quality $$service"; \
		(cd apps/$$service && test -z "$$(gofmt -l $$(find . -name '*.go' -type f))" && go vet ./... && go test ./...); \
	done

quality-python: ## Run Ruff format/lint and pytest for all Python services.

	@for service in $(PYTHON_SERVICES); do \
		echo "==> quality $$service"; \
		(cd apps/$$service && ../.venv/bin/ruff format --check . && ../.venv/bin/ruff check . && ../.venv/bin/pytest); \
	done

quality-spring: ## Run Gradle verification, including tests, for all Spring Boot services.

	@for service in $(SPRING_SERVICES); do \
		echo "==> quality $$service"; \
		(cd apps/$$service && ./gradlew check --no-daemon); \
	done

quality-service: ## Run the full quality gate for one service, e.g. make quality-service SERVICE=api-gateway.

	@test -n "$(SERVICE)" || { echo "Set SERVICE to one of: $(SERVICES)"; exit 1; }
	@case "$(SERVICE)" in \
	  api-gateway|chat-service|file-service|geolocation-service) \
		cd apps/$(SERVICE) && test -z "$$(gofmt -l $$(find . -name '*.go' -type f))" && go vet ./... && go test ./... ;; \
	  fraud-risk-service|reporting-service) \
		cd apps/$(SERVICE) && ../.venv/bin/ruff format --check . && ../.venv/bin/ruff check . && ../.venv/bin/pytest ;; \
	  admin-service|audit-service|configuration-service|courier-service|customer-service|driver-service|food-order-service|identity-service|ledger-service|notification-service|payment-service|pricing-service|restaurant-service|search-service|trip-service) \
		cd apps/$(SERVICE) && ./gradlew check --no-daemon ;; \
	  *) echo "Unknown SERVICE: $(SERVICE). Choose one of: $(SERVICES)"; exit 1 ;; \
	esac

test: test-go test-python test-spring ## Run all available tests.

test-go: ## Run Go tests.

	@for service in $(GO_SERVICES); do \
		echo "==> testing $$service"; \
		(cd apps/$$service && go test ./...); \
	done

test-python: ## Run Python service tests.

	@for service in $(PYTHON_SERVICES); do \
		echo "==> testing $$service"; \
		(cd apps/$$service && ../.venv/bin/pytest); \
	done

test-spring: ## Run Spring Boot tests (requires Docker for Testcontainers).

	@docker info >/dev/null 2>&1 || { echo "Docker is unavailable; start Docker before running Spring integration tests."; exit 1; }
	@for service in $(SPRING_SERVICES); do \
		echo "==> testing $$service"; \
		(cd apps/$$service && ./gradlew test --no-daemon); \
	done

test-service: ## Test one service, e.g. make test-service SERVICE=api-gateway.

	@test -n "$(SERVICE)" || { echo "Set SERVICE to one of: $(SERVICES)"; exit 1; }
	@case "$(SERVICE)" in \
	  api-gateway|chat-service|file-service|geolocation-service) \
		cd apps/$(SERVICE) && go test ./... ;; \
	  fraud-risk-service|reporting-service) \
		cd apps/$(SERVICE) && ../.venv/bin/pytest ;; \
	  admin-service|audit-service|configuration-service|courier-service|customer-service|driver-service|food-order-service|identity-service|ledger-service|notification-service|payment-service|pricing-service|restaurant-service|search-service|trip-service) \
		docker info >/dev/null 2>&1 || { echo "Docker is unavailable; start Docker before running Spring integration tests."; exit 1; }; \
		cd apps/$(SERVICE) && ./gradlew test --no-daemon ;; \
	  *) echo "Unknown SERVICE: $(SERVICE). Choose one of: $(SERVICES)"; exit 1 ;; \
	esac

service: ## Manage one service: make service SERVICE=<name> ACTION=run|build|test.

	@test -n "$(SERVICE)" || { echo "Set SERVICE to one of: $(SERVICES)"; exit 1; }
	@test -n "$(ACTION)" || { echo "Set ACTION to run, build, or test."; exit 1; }
	@case "$(ACTION)" in \
		run) $(MAKE) --no-print-directory run SERVICE=$(SERVICE) ;; \
		build) $(MAKE) --no-print-directory build-service SERVICE=$(SERVICE) ;; \
		test) $(MAKE) --no-print-directory test-service SERVICE=$(SERVICE) ;; \
		*) echo "Unknown ACTION: $(ACTION). Choose run, build, or test."; exit 1 ;; \
	esac

run: ## Run one service, e.g. make run SERVICE=api-gateway.

	@test -n "$(SERVICE)" || { echo "Set SERVICE to one of: $(SERVICES)"; exit 1; }
	@case "$(SERVICE)" in \
	  api-gateway|chat-service|file-service|geolocation-service) \
		cd apps/$(SERVICE) && go run ./cmd/server ;; \
	  fraud-risk-service|reporting-service) \
		exec $(VENV)/bin/python -m uvicorn app.main:app --app-dir apps/$(SERVICE) --host 0.0.0.0 --port "$${PORT:-8000}" ;; \
	  admin-service|audit-service|configuration-service|courier-service|customer-service|driver-service|food-order-service|identity-service|ledger-service|notification-service|payment-service|pricing-service|restaurant-service|search-service|trip-service) \
		cd apps/$(SERVICE) && ./gradlew bootRun --no-daemon ;; \
	  *) echo "Unknown SERVICE: $(SERVICE). Choose one of: $(SERVICES)"; exit 1 ;; \
	esac

$(SERVICES): ## Run this service directly, e.g. make api-gateway.

	@$(MAKE) --no-print-directory service SERVICE=$@ ACTION=run

clean: ## Remove service build output and Python bytecode caches.

	@find apps -type d \( -name build -o -name __pycache__ \) -prune -exec rm -rf {} +

# -----------------------------------------------------------------------------
# Database bootstrap (local Postgres only; see scripts/db-init.sh)
# -----------------------------------------------------------------------------
#
# Single shared database `trips_enjoy` for all 20 services; each service owns
# exactly one PostgreSQL SCHEMA inside it (snake_case form of the service
# name, per docs/architecture/DATABASE_ARCHITECTURE.md:102). Schemas are
# created on service boot by Flyway (Spring), golang-migrate (Go), or
# alembic (Python). This script only creates the `trips_enjoy` database.
#
# JDBC URL used by every service in dev:
#   jdbc:postgresql://0.0.0.0:5432/trips_enjoy?currentSchema=<schema>
#
# DB superuser (matches the postgres role created by Homebrew / Herd default install).
DB_SUPERUSER ?= postgres
DB_HOST ?= 0.0.0.0
DB_PORT ?= 5432
TRIPS_DB ?= trips_enjoy

db-init: ## Create the `trips_enjoy` database on the local Postgres (idempotent).

	@bash scripts/db-init.sh "$(DB_HOST):$(DB_PORT)" "$(DB_SUPERUSER)" "$(TRIPS_DB)"

db-init-service: ## Same as db-init today (kept for callers that expect per-service).

	@$(MAKE) --no-print-directory db-init

# Spring migrations are run automatically on bootRun via Flyway (`spring.flyway.enabled=true`).
# Use ./gradlew flywayMigrate from inside a service if you want to run migrations alone.
db-migrate: ## Print migration commands for every service (Stack-specific).

	@echo "Spring (auto on bootRun — Flyway):"
	@for service in $(SPRING_SERVICES); do \
		echo "  cd apps/$$service && ./gradlew bootRun    # runs Flyway via spring.flyway.enabled=true"; \
	done
	@echo ""
	@echo "Go (golang-migrate, file-service + geolocation-service only):"
	@echo "  install:  go install -tags 'postgres' github.com/golang-migrate/migrate/v4/cmd/migrate@latest"
	@echo "  run:      migrate -path apps/<svc>/migrations -database '<SVC>_DB_URL' up"
	@echo ""
	@echo "Python (alembic, fraud-risk + reporting):"
	@for service in $(PYTHON_SERVICES); do \
		echo "  cd apps/$$service && alembic upgrade head"; \
	done

db-migrate-service: ## Run migrations for one service, e.g. make db-migrate-service SERVICE=trip-service.

	@test -n "$(SERVICE)" || { echo "Set SERVICE to one of: $(SERVICES)"; exit 1; }
	@case " $(SPRING_SERVICES) " in *" $(SERVICE) "*) \
		echo "Spring services migrate on bootRun (Flyway). Try: cd apps/$(SERVICE) && ./gradlew flywayMigrate" ;; \
	  " $(PYTHON_SERVICES) "*) \
		cd apps/$(SERVICE) && alembic upgrade head ;; \
	  " $(GO_SERVICES) "*) \
		echo "Go services that own a schema (file-service, geolocation-service) require golang-migrate CLI"; \
		echo "  migrate -path apps/$(SERVICE)/migrations -database \"\$${$(shell echo $(SERVICE) | tr a-z- A-Z_)_DB_URL}\" up" ;; \
	  *) echo "Unknown SERVICE: $(SERVICE). Choose one of: $(SERVICES)"; exit 1 ;; \
	esac

validate-docs: ## Validate every Mermaid diagram and Markdown local link in docs/.

	@bash scripts/validate-docs.sh

# ---------------------------------------------------------------------------
# STATUS.md composition contract (per docs/PLAN_INDEX.md "STATUS.md
# composition contract"): every active service ships a STATUS.md that
# composes fields from canonical sources. The doc-QA invariants:
#   - exactly 21 STATUS.md files
#   - each file has 8 numbered sections
#   - the Implementation lifecycle row count equals DEPLOYMENT_ORDER.md §8.2
# ---------------------------------------------------------------------------

DOC_STATUS_SERVICES := admin-service api-gateway audit-service chat-service configuration-service courier-service customer-service driver-service file-service food-order-service fraud-risk-service geolocation-service identity-service ledger-service notification-service payment-service pricing-service reporting-service restaurant-service search-service trip-service

status-md-check: ## Verify all 21 STATUS.md files conform to the template (8 sections + 1 header).

	@fail=0; \
	for svc in $(DOC_STATUS_SERVICES); do \
	  path=docs/services/$$svc/STATUS.md; \
	  if [ ! -f $$path ]; then \
	    echo "MISSING: $$path"; fail=1; continue; \
	  fi; \
	  sections=$$(grep -cE '^## [1-8]\. ' $$path); \
	  if [ "$$sections" != "8" ]; then \
	    echo "BAD SECTIONS ($$sections != 8): $$path"; fail=1; \
	  fi; \
	done; \
	if [ "$$fail" = "1" ]; then echo "status-md-check: FAILED"; exit 1; fi; \
	echo "status-md-check: OK (21/21 STATUS.md files conform to template)"

status-md-regenerate: ## Print the regeneration procedure (manual today; see docs/PLAN_INDEX.md).

	@echo "STATUS.md is composed manually today. Procedure:"; \
	echo "  1. Identity         — copy from docs/services/README.md + <svc>/README.md §1–2"; \
	echo "  2. Tech profile     — copy from <svc>/TECH.md + docs/services/RECOMMENDATIONS.md §2"; \
	echo "  3. Implementation   — copy row verbatim from docs/DEPLOYMENT_ORDER.md §8.2"; \
	echo "  4. Doc completeness — ls docs/services/<svc>/ + git log -1 per file"; \
	echo "  5. Contract         — count APIs/events from <svc>/INTEGRATION.md §1–4"; \
	echo "  6. Security/RBAC    — copy from <svc>/TECH.md §10 + RECOMMENDATIONS.md §6.2a"; \
	echo "  7. Plan snapshot    — copy header from <svc>/PLAN.md + grep Phase 7.x blocks"; \
	echo "  8. Cross-links      — fixed per template"; \
	echo "Full contract: docs/PLAN_INDEX.md \"STATUS.md composition contract\"."

# ---------------------------------------------------------------------------
# Per-service K8s + monitoring validation (configuration-service uses
# kustomize v5 strict mode; the `--load-restrictor` flag is the documented
# escape hatch per https://kubectl.docs.kubernetes.io/references/kustomize/).
# ---------------------------------------------------------------------------
configuration-k8s-validate: ## Dry-run + kustomize build for configuration-service (all overlays).

	@for env in dev stg prod; do \
		echo "==> overlay: $$env"; \
		kubectl kustomize apps/configuration-service/k8s/$$env --load-restrictor=LoadRestrictionsNone > /dev/null && echo "    kustomize build: OK"; \
		kustomize_build=$$(kubectl kustomize apps/configuration-service/k8s/$$env --load-restrictor=LoadRestrictionsNone 2>/dev/null); \
		echo "$$kustomize_build" | kubectl apply --dry-run=client -f - 2>&1 | tail -3; \
	done

# ---------------------------------------------------------------------------
# Platform Kubernetes reference tree validation.
#
# k8s-lint               — conftest policy check against every YAML in
#                          platform/k8s/infra and platform/k8s/services.
# k8s-build              — kustomize build the full reference tree.
# k8s-validate-platform-rules — promtool check the platform-wide recording +
#                          alerting rules.
# ---------------------------------------------------------------------------
K8S_TREE := platform/k8s
K8S_YAML_FILES := $(shell find $(K8S_TREE)/infra $(K8S_TREE)/services -name '*.yaml' -not -path '*/patches/*')
K8S_PROM_RULE_FILES := $(shell find $(K8S_TREE)/infra $(K8S_TREE)/services -name '*.yaml' -not -path '*/patches/*' -exec grep -l 'PrometheusRule' {} \;)

k8s-lint: ## Conftest policy check on every platform/k8s YAML.
	@command -v conftest >/dev/null || { echo "conftest not installed; see https://www.conftest.dev"; exit 1; }
	@echo "==> conftest policy check on $(words $(K8S_YAML_FILES)) YAMLs"
	@conftest test --policy $(K8S_TREE)/policy $(K8S_YAML_FILES)

k8s-build: ## Kustomize build the full reference tree.
	@command -v kubectl >/dev/null || { echo "kubectl not installed"; exit 1; }
	@echo "==> kustomize build $(K8S_TREE)"
	@kubectl kustomize $(K8S_TREE) > /dev/null && echo "    build OK"

k8s-build-stg: ## Kustomize build the stg overlay.
	@command -v kubectl >/dev/null || { echo "kubectl not installed"; exit 1; }
	@echo "==> kustomize build $(K8S_TREE)/overlays/stg"
	@kubectl kustomize $(K8S_TREE)/overlays/stg > /dev/null && echo "    build OK"

k8s-build-prod: ## Kustomize build the prod overlay.
	@command -v kubectl >/dev/null || { echo "kubectl not installed"; exit 1; }
	@echo "==> kustomize build $(K8S_TREE)/overlays/prod"
	@kubectl kustomize $(K8S_TREE)/overlays/prod > /dev/null && echo "    build OK"

k8s-validate-platform-rules: ## promtool check every PrometheusRule in the tree.
	@command -v promtool >/dev/null || { echo "promtool not installed; see https://github.com/prometheus/prometheus/releases"; exit 1; }
	@echo "==> promtool check rules on $(words $(K8S_PROM_RULE_FILES)) files"
	@for f in $(K8S_PROM_RULE_FILES); do \
		echo "  promtool check: $$f"; \
		promtool check rules $$f; \
	done
