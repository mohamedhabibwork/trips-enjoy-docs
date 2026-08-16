.PHONY: build test fmt vet tidy run docker clean help

GOFLAGS ?=
BINARY  := bin/server
PKG     := ./...

help: ## Show available commands.
	@awk 'BEGIN {FS = ":.*?##"} /^[a-zA-Z_-]+:.*?##/ {printf "  %-12s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

build: ## Compile the api-gateway binary.
	mkdir -p bin
	go build $(GOFLAGS) -o $(BINARY) ./cmd/server

test: ## Run all tests.
	go test $(GOFLAGS) -race -count=1 ./...

fmt: ## Run gofmt on all source files.
	gofmt -l -w .

vet: ## Run go vet.
	go vet ./...

tidy: ## Clean go.mod / go.sum.
	go mod tidy

run: build ## Run the gateway against the local environment (uses .env if present).
	@if [ -f .env ]; then set -a && . ./.env && set +a; fi; ./$(BINARY)

docker: ## Build the distroless container image (tag: api-gateway:dev).
	docker build -t api-gateway:dev .

clean: ## Remove build artifacts.
	rm -rf bin
