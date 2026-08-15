package com.trips_enjoy.restaurant.config

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
    fun restaurantServiceOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("restaurant-service API")
                .version("4.1.0")
                .description(
                    "restaurant-service is the platform's source of truth for the " +
                        "restaurant brand — name/slug/type, cuisines + tags, " +
                        "and the 8-state lifecycle (draft → pending_review → " +
                        "approved → online ↔ offline → suspended / closed / " +
                        "rejected). Full contract: " +
                        "docs/services/restaurant-service/INTEGRATION.md."
                )
        )
        .servers(
            listOf(
                Server().url("https://api.trips-enjoy.com").description("production"),
                Server().url("https://api.stg.trips-enjoy.com").description("staging"),
                Server().url("https://api.dev.trips-enjoy.com").description("dev"),
                Server().url("http://localhost:8084").description("local"),
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