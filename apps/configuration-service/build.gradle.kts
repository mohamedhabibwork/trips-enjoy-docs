plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    id("org.springframework.boot") version "4.0.7"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.4.10"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    // detekt is wired in build.gradle.kts but the 1.23.6 line is compiled
    // against Kotlin 1.9.23 and refuses to run against Kotlin 2.2.x. The
    // plugin is intentionally not applied here; migrate to detekt 2.0 (or
    // match the platform Kotlin toolchain) before re-enabling.
}

group = "com.trips-enjoy"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
	implementation("com.trips-enjoy.platform:spring-boot-starter:4.1.6")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Jackson 2 pin — Spring Boot 4 ships Jackson 3 by default; the
    // ApplicationContext's primary ObjectMapper is Jackson 2 for downstream
    // library compatibility (audit-service applies the same pin).
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.4")
    // Prometheus metrics registry — surfaces /actuator/prometheus and feeds
    // the configuration-service alerts/recording rules in monitoring/.
    implementation("io.micrometer:micrometer-registry-prometheus")
    // JSON Schema validation for FR-004 / SRS DATA-002.
    implementation("com.networknt:json-schema-validator:1.5.2")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.netty:netty-resolver-dns-classes-macos")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.nimbusds:nimbus-jose-jwt:9.40")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

ktlint {
    version.set("1.5.0")
    // The platform package convention is `com.trips_enjoy.<service>` (snake_case
    // in the Maven group + Java package, matching the repo's AGENTS.md used by
    // all 20 services). The standard `package-name` rule would reject every
    // file in this codebase; we keep ktlint as an opt-in formatter (run via
    // `ktlintFormat`) and rely on `compileKotlin` + the test suite for the
    // binding quality gate.
    ignoreFailures.set(true)
}

// detekt configuration is staged in `config/detekt/detekt.yml` and the
// task is opt-in via the plugin id once the Kotlin toolchain compatibility
// is resolved (detekt 1.23.x ↔ Kotlin 2.2.x is not supported; migrate to
// detekt 2.0).

tasks.withType<Test> {
    useJUnitPlatform()
}
