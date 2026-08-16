pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "identity-service"

includeBuild("../../packages/platform-spring-boot")
