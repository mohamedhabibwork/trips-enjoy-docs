pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "audit-service"

includeBuild("../../packages/platform-spring-boot")
