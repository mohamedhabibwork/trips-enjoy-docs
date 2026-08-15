package com.trips_enjoy.foodorder.config

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
    fun foodOrderServiceOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("food-order-service API")
                .version("4.1.0")
                .description(
                    "food-order-service is the platform's food delivery " +
                        "orchestration hub. It owns the order-request + order " +
                        "lifecycle (placed → accepted → preparing → ready → " +
                        "picked_up → delivered), the courier dispatch, the " +
                        "Make-a-Deal (Phase 7.5) flow, and the order-state " +
                        "transition audit. It is the BFF aggregator for the " +
                        "restaurant + courier + customer + payment + pricing " +
                        "upstream services. Full contract: " +
                        "docs/services/food-order-service/INTEGRATION.md."
                )
        )
        .servers(
            listOf(
                Server().url("https://api.trips-enjoy.com").description("production"),
                Server().url("https://api.stg.trips-enjoy.com").description("staging"),
                Server().url("https://api.dev.trips-enjoy.com").description("dev"),
                Server().url("http://localhost:8083").description("local"),
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