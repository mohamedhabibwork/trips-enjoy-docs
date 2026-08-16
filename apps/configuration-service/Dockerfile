# Multi-stage Dockerfile for configuration-service (Kotlin 2.2 / Spring Boot 4).
#
# Stage 1 — gradle:9.5.1-jdk21: warm the dependency cache, then compile.
# Stage 2 — eclipse-temurin:25-jre-jammy: JRE-only final image, non-root,
#           readOnlyRootFilesystem-friendly (heapdumps via tmpfs).
#
# The same image runs as the Deployment AND as the migrate / snapshot
# CronJob / Job (subcommand dispatch via args[]).

# ----------------------------------------------------------------------------
# Stage 1: build
# ----------------------------------------------------------------------------
FROM gradle:9.5.1-jdk21 AS build

WORKDIR /workspace

# Copy the Gradle wrapper + manifests first so the dependency cache
# is warm on incremental builds.
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Now copy the source and build the bootJar.
COPY src src
RUN ./gradlew --no-daemon bootJar

# ----------------------------------------------------------------------------
# Stage 2: runtime
# ----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-jammy

# Non-root user (uid 10001 matches the K8s Deployment securityContext).
RUN useradd --system --create-home --uid 10001 configuration

USER configuration

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

EXPOSE 8081

# `migrate` subcommand is dispatched in the migrate / snapshot Jobs;
# the default entrypoint runs the HTTP server.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]