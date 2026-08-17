plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    // The autoconfigured cron + health endpoints share the web module's
    // actuator base (HealthIndicator, /actuator/health contract).
    api(project(":platform-spring-boot-web"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-jdbc")
}

kotlin {
    jvmToolchain(21)
}
