plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.spring") version "2.4.10" apply false
    kotlin("plugin.jpa") version "2.4.10" apply false
    id("org.springframework.boot") version "4.0.7" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.trips-enjoy.platform"
    version = "4.1.5"

    repositories {
        mavenCentral()
    }

    apply(plugin = "io.spring.dependency-management")

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${project.findProperty("springBootVersion") ?: "4.0.7"}")
            // Spring Boot 4.0.7's BOM pulls kotlin-reflect:2.2.21 which breaks
            // Kotlin 2.4.10's build-tools classloader. Override here.
            mavenBom("org.jetbrains.kotlin:kotlin-bom:2.4.10")
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencies {
            "implementation"("org.jetbrains.kotlin:kotlin-reflect")
            "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core")
        }
    }
}
