package com.trips_enjoy.trip.config

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
    fun tripServiceOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("trip-service API")
                .version("4.1.0")
                .description(
                    "trip-service is the platform's ride orchestration service. " +
                        "It owns the full ride saga (request → match → arrive → start → " +
                        "complete / cancel), the location pings, the reward grant + " +
                        "reversal flow, and the multi-stop trip support. It is the " +
                        "downstream consumer of the pricing-service fare snapshot, the " +
                        "driver-service matching, the payment-service capture, and the " +
                        "customer-service rider profile. Full contract: " +
                        "docs/services/trip-service/INTEGRATION.md."
                )
        )
        .servers(
            listOf(
                Server().url("https://api.trips-enjoy.com").description("production"),
                Server().url("https://api.stg.trips-enjoy.com").description("staging"),
                Server().url("https://api.dev.trips-enjoy.com").description("dev"),
                Server().url("http://localhost:8082").description("local"),
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