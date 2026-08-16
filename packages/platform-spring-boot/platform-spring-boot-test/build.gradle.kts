plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":platform-spring-boot-autoconfigure"))
    api(project(":platform-spring-boot-web"))
    api(project(":platform-spring-boot-security"))
    api(project(":platform-spring-boot-data"))
    api(project(":platform-spring-boot-money"))
    api(project(":platform-spring-boot-caching"))
    api(project(":platform-spring-boot-messaging"))
    api(project(":platform-spring-boot-observability"))
    api(project(":platform-spring-boot-audit"))
    api(project(":platform-spring-boot-error"))
    api(project(":platform-spring-boot-api-docs"))
    api(project(":platform-spring-boot-lookup"))

    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
    api("org.testcontainers:junit-jupiter:1.21.0")
    api("org.testcontainers:postgresql:1.21.0")
    api("org.testcontainers:kafka:1.21.0")
    api("com.github.dasniko:testcontainers-keycloak:3.4.0")

    api("org.junit.jupiter:junit-jupiter")
    api("org.mockito:mockito-core")
    api("org.assertj:assertj-core")
    api("org.awaitility:awaitility")

    api("com.nimbusds:nimbus-jose-jwt")
}

kotlin {
    jvmToolchain(21)
}
