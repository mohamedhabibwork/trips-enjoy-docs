pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "customer-service"

includeBuild("../../packages/platform-spring-boot")
