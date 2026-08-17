# syntax=docker/dockerfile:1.6
#
# Multi-stage build — produces a static binary in a distroless base
# image, matching docs/shared/PLATFORM_BASELINE.md §1.
#
# The resulting image runs the HTTP server by default. The same image
# runs the golang-migrate CLI when invoked as `/server migrate`
# (handled by cmd/server/main.go's subcommand dispatch; see
# k8s/geolocation-service.yaml for the pre-upgrade Job).

# ----- build stage -----
FROM golang:1.22 AS build
WORKDIR /src
ENV CGO_ENABLED=0
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags='-s -w' -o /out/server ./cmd/server

# ----- migrate binary -----
# Pull the standalone golang-migrate CLI into a separate stage so the
# final image can shell out to it (the Dockerfile is the only place
# that knows about the toolchain — main.go just execs `migrate`). The
# `postgres` source includes the `pgx/v5` driver we register at
# runtime via main.go's `pgx5://` URL scheme.
FROM golang:1.22 AS migrate-build
WORKDIR /src
ENV CGO_ENABLED=0
RUN go install -tags 'postgres' github.com/golang-migrate/migrate/v4/cmd/migrate@v4.17.1

# ----- runtime stage -----
FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=build /out/server /app/server
COPY --from=migrate-build /go/bin/migrate /usr/local/bin/migrate
COPY migrations /app/migrations
EXPOSE 8085 8086
USER nonroot:nonroot
ENTRYPOINT ["/app/server"]