plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.spring") version "2.4.10"
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.4.10"
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
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
	implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.4")
	// Prometheus scrape registry — required so that /actuator/prometheus
	// returns 200 and exposes the audit_* custom metrics emitted via the
	// Micrometer MeterRegistry (AuditIngestService, ExportService, etc.).
	implementation("io.micrometer:micrometer-registry-prometheus")
	// Keycloak Admin Client — required by KeycloakSeeder to provision the
	// platform's realms / roles / clients on first boot. Only loaded when
	// `audit-service.keycloak-seed.enabled=true`; the seeder guards on
	// missing client id/secret so the dep is harmless in production.
	implementation("org.keycloak:keycloak-admin-client:24.0.0")
	runtimeOnly("org.postgresql:postgresql")
	// macOS local dev: pin Netty DNS native resolver for the JRE classifier on
	// darwin arm64. The matching Linux native artefact ships separately on CI.
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

tasks.withType<Test> {
	useJUnitPlatform()
}
