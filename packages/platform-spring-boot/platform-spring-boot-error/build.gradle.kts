plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("jakarta.validation:jakarta.validation-api")

    api("org.springframework.boot:spring-boot-actuator-autoconfigure")

    api("io.opentelemetry:opentelemetry-api:1.50.0")

    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
}

kotlin {
    jvmToolchain(21)
}
