plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":platform-spring-boot-web"))
    api(project(":platform-spring-boot-security"))
    api(project(":platform-spring-boot-data"))
    api(project(":platform-spring-boot-caching"))
    api(project(":platform-spring-boot-messaging"))
    api(project(":platform-spring-boot-audit"))

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")

    api("jakarta.persistence:jakarta.persistence-api")
    api("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.assertj:assertj-core")
}

kotlin {
    jvmToolchain(21)
}
