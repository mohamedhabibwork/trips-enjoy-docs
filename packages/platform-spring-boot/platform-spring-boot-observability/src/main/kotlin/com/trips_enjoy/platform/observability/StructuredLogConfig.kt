package com.trips_enjoy.platform.observability

import org.springframework.context.annotation.Configuration

/**
 * Marker that the platform installs structured JSON logging via the
 * `logback-spring.xml` placed under `src/main/resources/`. The XML file
 * configures the Logback `LogstashEncoder` (from `logstash-logback-encoder`)
 * so that every log line is JSON with MDC fields, traceId, spanId, and
 * the platform `requestId` baked in.
 */
@Configuration
internal class StructuredLogConfig
