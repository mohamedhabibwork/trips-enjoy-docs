plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":platform-spring-boot-error"))
    api(project(":platform-spring-boot-money"))
    api(project(":platform-spring-boot-messaging"))
    api(project(":platform-spring-boot-web"))

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
}

kotlin {
    jvmToolchain(21)
}
