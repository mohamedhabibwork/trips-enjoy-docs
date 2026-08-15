package com.trips_enjoy.driver.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {
    @Bean
    fun driverServiceOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("driver-service API")
                .version("4.1.0")
                .description(
                    "driver-service is the platform's source of truth for the " +
                        "driver profile — KYC documents, vehicle, city eligibility, " +
                        "rating history, and the driver state machine " +
                        "(pending_review → approved | rejected | suspended | " +
                        "inactive | erased). Full contract: " +
                        "docs/services/driver-service/INTEGRATION.md."
                )
        )
        .servers(
            listOf(
                Server().url("https://api.trips-enjoy.com").description("production"),
                Server().url("https://api.stg.trips-enjoy.com").description("staging"),
                Server().url("https://api.dev.trips-enjoy.com").description("dev"),
                Server().url("http://localhost:8089").description("local"),
            )
        )
        .components(
            Components().addSecuritySchemes(
                "bearer-jwt",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Keycloak JWT bearer token, realm platform-internal."),
            )
        )
        .addSecurityItem(SecurityRequirement().addList("bearer-jwt"))
}