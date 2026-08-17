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
	implementation("com.trips-enjoy.platform:spring-boot-starter:4.1.2")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-cache")
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
	implementation("org.keycloak:keycloak-admin-client:24.0.0")
	implementation("com.nimbusds:nimbus-jose-jwt:9.40")
	implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
	implementation("com.fasterxml.jackson.core:jackson-databind:2.21.4")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.4")
	runtimeOnly("org.postgresql:postgresql")
	// macOS local dev: pull in the native DNS resolver so Netty stops logging
	// "Unable to load io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider"
	// on boot. The `-classes-macos` artefact contains the Java side of the resolver;
	// the explicit classifier (osx-aarch_64 / osx-x86_64) is required because the
	// meta `netty-resolver-dns-native-macos` artefact is empty — Netty's
	// `NativeLibraryLoader` extracts the .dylib from the classifier-specific jar
	// at runtime. Apple Silicon Macs pick `osx-aarch_64`; Intel Macs use
	// `osx-x86_64`. Linux/Windows builds are unaffected — Docker / CI run on
	// eclipse-temurin:25-jre and pick up the matching Linux native resolver
	// separately. Version is managed by the Spring Boot BOM via
	// io.spring.dependency-management.
	runtimeOnly("io.netty:netty-resolver-dns-classes-macos")
	runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.15.Final:osx-aarch_64")
	runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.15.Final:osx-x86_64")
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
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("com.nimbusds:nimbus-jose-jwt:9.40")
	testImplementation("org.awaitility:awaitility-kotlin:4.2.2")
	testImplementation("org.awaitility:awaitility:4.2.2")
	testImplementation("com.github.dasniko:testcontainers-keycloak:3.4.0")
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
