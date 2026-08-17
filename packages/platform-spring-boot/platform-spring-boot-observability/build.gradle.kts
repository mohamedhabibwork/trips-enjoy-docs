plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":platform-spring-boot-web"))

    api("org.springframework.boot:spring-boot-starter-actuator")
    api("io.micrometer:micrometer-registry-prometheus")
    api("io.micrometer:micrometer-tracing-bridge-otel")

    api("io.opentelemetry:opentelemetry-sdk")
    api("io.opentelemetry:opentelemetry-api")
    api("io.opentelemetry:opentelemetry-exporter-otlp")
    api("io.opentelemetry:opentelemetry-extension-trace-propagators")

    api("ch.qos.logback:logback-classic")
    api("net.logstash.logback:logstash-logback-encoder:8.0")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

kotlin {
    jvmToolchain(21)
}
