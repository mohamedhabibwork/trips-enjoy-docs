package com.trips_enjoy.configuration.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * SpringDoc / OpenAPI 3.1 base metadata for the service.
 *
 * The bearerAuth scheme is declared globally so every endpoint is shown as
 * protected in the rendered Swagger UI at /docs.
 */
@Configuration
class OpenApiConfiguration {
    @Bean
    fun configurationOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Trips Enjoy Configuration Service API")
                    .version("v1")
                    .description(
                        "Versioned configuration documents: read (cache + long-poll), write (idempotent), " +
                            "history, snapshot, per-channel subset, and outbox events (configuration.updated.v1 / " +
                            ".rolled_back.v1 / .key.deprecated.v1 / .snapshot.exported.v1). " +
                            "See docs/services/configuration-service/INTEGRATION.md for the contract.",
                    ),
            ).components(
                Components().addSecuritySchemes(
                    "bearerAuth",
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
                ),
            ).addSecurityItem(SecurityRequirement().addList("bearerAuth"))
}
