pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "ledger-service"

includeBuild("../../packages/platform-spring-boot")
