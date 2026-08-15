package com.trips_enjoy.payment.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI configuration — publishes the canonical payment-service contract
 * at `/openapi.json` + Swagger UI at `/docs`. Mirrors the platform-wide
 * pattern from customer-service / identity-service.
 *
 * The contract is hand-curated via the @Operation/@Schema annotations
 * on the controllers (see docs/services/payment-service/INTEGRATION.md
 * §1 for the full REST surface). Per AGENTS.md every HTTP service MUST
 * publish OpenAPI 3.x at `/openapi.json`.
 */
@Configuration
class OpenApiConfiguration {

    @Bean
    fun paymentServiceOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("payment-service API")
                .version("4.1.0")
                .description(
                    "payment-service is the platform's payment orchestration hub. " +
                        "It owns the 46-gateway registry, the payment intent state machine, " +
                        "the customer wallet double-entry ledger, the driver/courier/merchant " +
                        "earnings aggregates, and the 17 Conductor workflow workers that " +
                        "coordinate ride + food + wallet + settlement sagas. " +
                        "Full contract: docs/services/payment-service/INTEGRATION.md."
                )
                .contact(Contact().name("payment-service team").email("payments@trips-enjoy.com"))
                .license(License().name("Proprietary"))
        )
        .servers(
            listOf(
                Server().url("https://api.trips-enjoy.com").description("production"),
                Server().url("https://api.stg.trips-enjoy.com").description("staging"),
                Server().url("https://api.dev.trips-enjoy.com").description("dev"),
                Server().url("http://localhost:8088").description("local"),
            )
        )
        .components(
            Components()
                .addSecuritySchemes(
                    "bearer-jwt",
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak JWT bearer token, realm platform-internal."),
                )
                .addSecuritySchemes(
                    "service-account",
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Service-to-service JWT bearer token (payment.write scope)."),
                )
        )
        .addSecurityItem(
            SecurityRequirement().addList("bearer-jwt").addList("service-account")
        )
}