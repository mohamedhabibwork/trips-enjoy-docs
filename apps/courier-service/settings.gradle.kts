pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "courier-service"

includeBuild("../../packages/platform-spring-boot")
