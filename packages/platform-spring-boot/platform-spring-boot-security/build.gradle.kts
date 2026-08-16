plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":platform-spring-boot-web"))

    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-actuator")

    api("org.keycloak:keycloak-admin-client:24.0.0")
    api("com.nimbusds:nimbus-jose-jwt:9.40")

    api("io.opentelemetry:opentelemetry-api")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
}

kotlin {
    jvmToolchain(21)
}
