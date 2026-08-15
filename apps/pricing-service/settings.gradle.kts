pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "pricing-service"

includeBuild("../../packages/platform-spring-boot")
