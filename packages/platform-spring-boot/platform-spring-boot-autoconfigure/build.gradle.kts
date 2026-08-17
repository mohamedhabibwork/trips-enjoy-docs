plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot")
    api("org.springframework.boot:spring-boot-autoconfigure")
}

kotlin {
    jvmToolchain(21)
}
