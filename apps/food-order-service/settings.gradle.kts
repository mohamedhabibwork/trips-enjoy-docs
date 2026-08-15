pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "food-order-service"

includeBuild("../../packages/platform-spring-boot")
