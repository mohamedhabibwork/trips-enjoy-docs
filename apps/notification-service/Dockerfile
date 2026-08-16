# syntax=docker/dockerfile:1.6
#
# Multi-stage Dockerfile for notification-service.
#
# Mirrors the pattern established by file-service / ledger-service / identity-service
# for Kotlin/Spring Boot services:
#   - build stage: gradle:8.10-jdk21, build the bootJar
#   - migrate tool: Alpine `curl + bash` so we can pull Flyway CLI from Apache
#     (no extra JDK install in the runtime image)
#   - runtime: eclipse-temurin:25-jre-jammy (multi-stage, non-root, distroless
#     JRE-only final). Notification-service targets Java 21 per build.gradle.kts
#     so we use eclipse-temurin:21-jre-jammy for parity with the build toolchain
#     — the eck-distroless JRE image carries only one LTS at a time.
#
# Resulting image runs the HTTP server by default. When invoked as
# `/app/notification-service migrate` the main() shim exits after Flyway
# completes (see src/main/kotlin/.../NotificationServiceApplication.kt).

# ----- build stage -----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --version
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# ----- migrate CLI stage -----
# Pull the standalone Flyway CLI from Apache so the runtime image can shell
# out to it. Notification-service uses Flyway 11.x which matches the
# version declared transitively via Spring Boot 4.
FROM eclipse-temurin:21-jre-jammy AS migrate-build
WORKDIR /tmp
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline/11.5.0/flyway-commandline-11.5.0-linux-x64.tar.gz \
    | tar -xz -C /opt && mv /opt/flyway-11.5.0 /opt/flyway \
    && rm -rf /var/lib/apt/lists/*

# ----- runtime stage -----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd -r notification && useradd -r -g notification -u 65532 notification
COPY --from=build /src/build/libs/notification-service-0.0.1-SNAPSHOT.jar /app/notification-service.jar
COPY --from=migrate-build /opt/flyway /opt/flyway
RUN ln -s /opt/flyway/flyway /usr/local/bin/flyway
COPY src/main/resources/db/migration /app/db/migration
EXPOSE 8086
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/notification-service.jar"]