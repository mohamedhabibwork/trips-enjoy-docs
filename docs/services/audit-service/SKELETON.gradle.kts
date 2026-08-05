// SKELETON ONLY — documentation, not a working build.
// See ../TECH.md for the platform-default setup.
// Full OSS catalogue (SPDX IDs, license URLs, NOTICE generation) is in
// ../../shared/OSS_DEPENDENCIES.md
//
// Service: audit-service
// Profile: Streaming / event ingest
// Language: Kotlin (Spring Boot 4 + Spring Kafka)
//
// To turn this into a runnable build:
//   1. Generate the Gradle wrapper from the platform template.
//   2. Replace the placeholder coordinates below with the resolved versions
//      from ../RECOMMENDATIONS.md §5.1.
//   3. Either keep the platform-spring-boot-starter (recommended for
//      in-platform run) or remove it and add the equivalent starters
//      individually for a standalone run.

plugins {
    kotlin("jvm") version "2.2.x"
    kotlin("plugin.spring") version "2.2.x"
    id("org.springframework.boot") version "4.x"
    id("io.spring.dependency-management") version "1.1.x"
}

group = "com.trips-enjoy.platform"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // The platform-provided cross-cutting starter (Apache-2.0).
    // Remove for a standalone run and add the equivalent starters below.
    implementation("com.trips-enjoy.platform:spring-boot-starter:VERSION")

    // Spring Boot starters (Apache-2.0)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // Persistence (Hibernate 7 = LGPL-2.1-only; Flyway 11 = Apache-2.0)
    implementation("org.hibernate.orm:hibernate-core:7.x")
    implementation("org.flywaydb:flyway-core:11.x")
    implementation("org.flywaydb:flyway-database-postgresql:11.x")
    implementation("org.postgresql:postgresql:18.x")

    // Cache (Caffeine 3 = Apache-2.0; Lettuce 6 = Apache-2.0)
    implementation("com.github.ben-manes.caffeine:caffeine:3.x")
    implementation("io.lettuce:lettuce-core:6.x")

    // Mapping (MapStruct 1.6 = Apache-2.0)
    implementation("org.mapstruct:mapstruct:1.6.x")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.x")

    // Resilience (Resilience4j 2 = Apache-2.0)
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.x")
    implementation("io.github.resilience4j:resilience4j-reactor:2.x")

    // Observability (Micrometer 1.14 = Apache-2.0; OpenTelemetry 1.40+ = Apache-2.0)
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.x")
    implementation("io.opentelemetry:opentelemetry-api:1.40.x")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.40.x")





    // Test (JUnit 5 = EPL-2.0; Testcontainers 1.21 = MIT; MockK 1.13 = Apache-2.0)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.x")
    testImplementation("org.testcontainers:postgresql:1.21.x")
    testImplementation("org.testcontainers:kafka:1.21.x")
    testImplementation("io.mockk:mockk:1.13.x")

    // ----------------------------------------------------------------------
    // External vendor SDK placeholder
    // ----------------------------------------------------------------------
    // S3 (cold archive)
    //
    // To extract this service, swap or stub the vendor SDK at the driver
    // boundary. The OSS catalogue entry for this dependency is in
    // ../../shared/OSS_DEPENDENCIES.md §7.
    // For the platform run, the vendor SDK is configured via the
    // platform-spring-boot-starter (see ../TECH.md §2).
    // implementation("<vendor-coords>")

}

tasks.withType<Test> {
    useJUnitPlatform()
}
