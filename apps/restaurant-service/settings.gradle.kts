pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "restaurant-service"

includeBuild("../../packages/platform-spring-boot")
