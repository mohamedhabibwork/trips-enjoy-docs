pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "payment-service"

includeBuild("../../packages/platform-spring-boot")
