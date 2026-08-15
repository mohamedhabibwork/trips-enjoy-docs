package com.trips_enjoy.pricing.config

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
    fun pricingServiceOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("pricing-service API")
                .version("4.1.0")
                .description(
                    "pricing-service is the platform's quote engine — " +
                        "computes ride + food prices using the B0 base_fare + " +
                        "B1 rating-density + B2 loyalty + B3 geo-config + " +
                        "B4 surge pipeline. Owns the rule_bindings table " +
                        "(sourced from admin-service), the per-zone surge + " +
                        "rating-density caches, and the loyalty_frequent_cache. " +
                        "Full contract: docs/services/pricing-service/INTEGRATION.md."
                )
        )
        .servers(
            listOf(
                Server().url("https://api.trips-enjoy.com").description("production"),
                Server().url("https://api.stg.trips-enjoy.com").description("staging"),
                Server().url("https://api.dev.trips-enjoy.com").description("dev"),
                Server().url("http://localhost:8096").description("local"),
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