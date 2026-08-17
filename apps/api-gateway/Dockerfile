# Multi-stage build — produces a static binary in a distroless base,
# matching docs/shared/PLATFORM_BASELINE.md §1.
FROM golang:1.22 AS build
WORKDIR /src
ENV CGO_ENABLED=0
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags='-s -w' -o /out/server ./cmd/server

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /
COPY --from=build /out/server /server
EXPOSE 8080 8081
USER nonroot:nonroot
ENTRYPOINT ["/server"]
