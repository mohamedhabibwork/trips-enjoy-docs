# geolocation-service local Makefile.
#
# Mirrors apps/file-service/Makefile in shape so every Go service on
# the platform has the same local ergonomics. The targets are
# intentionally minimal: build, test, run, and the docker / k8s
# validation helpers.

IMAGE ?= registry.trips-enjoy.com/geolocation-service:dev
K8S_MANIFEST := k8s/geolocation-service.yaml
DOCKERFILE := Dockerfile

.PHONY: all build test run docker k8s-dryrun k8s-validate lint fmt vet clean help

help: ## Show available targets.
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ {printf "  %-15s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

all: build test ## Build + run all tests.

build: ## Compile the service.
	go build ./...

test: ## Run all tests.
	go test ./...

run: ## Run the service against the local dev stack.
	go run ./cmd/server

docker: ## Build the distroless image (multi-stage, golang 1.22 base).
	docker build -f $(DOCKERFILE) -t $(IMAGE) .

k8s-dryrun: ## Validate the k8s manifest via kubectl --dry-run=client.
	kubectl --dry-run=client apply -f $(K8S_MANIFEST)

k8s-validate: ## Same as k8s-dryrun but with explicit --validate=true (yaml lint).
	kubectl --dry-run=client --validate=true apply -f $(K8S_MANIFEST)

lint: ## gofmt + go vet.
	gofmt -l .
	go vet ./...

fmt: ## Apply gofmt to all source files.
	gofmt -w .

clean: ## Remove build/test cache (does NOT touch bin/).
	go clean -cache -testcache
