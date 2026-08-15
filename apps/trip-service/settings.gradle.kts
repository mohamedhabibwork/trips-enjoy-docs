pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "trip-service"

includeBuild("../../packages/platform-spring-boot")
