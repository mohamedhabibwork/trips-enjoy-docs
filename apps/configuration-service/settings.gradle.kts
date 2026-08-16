pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "configuration-service"

includeBuild("../../packages/platform-spring-boot")
