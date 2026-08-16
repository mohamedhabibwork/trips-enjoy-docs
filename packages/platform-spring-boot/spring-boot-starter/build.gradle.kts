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
    // platform-spring-boot-test is consumed as testImplementation by services
    testImplementation(project(":platform-spring-boot-test"))
}

kotlin {
    jvmToolchain(21)
}
