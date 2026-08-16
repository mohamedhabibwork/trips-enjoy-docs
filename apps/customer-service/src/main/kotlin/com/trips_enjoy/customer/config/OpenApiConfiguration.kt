package com.trips_enjoy.customer.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * SpringDoc / OpenAPI 3.1 base metadata for customer-service.
 *
 * The bearerAuth scheme is declared globally so every endpoint is shown
 * as protected in the rendered Swagger UI at /docs.
 */
@Configuration
class OpenApiConfiguration {
    @Bean
    fun customerOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Trips Enjoy Customer Service API")
                    .version("v1")
                    .description(
                        "Customer profile aggregate: KYC, LTV, segment, default payment method / address, " +
                            "and the customer state machine (active / suspended / disabled / erased). " +
                            "Plus the absorbed cross-persona profile, addresses, and loyalty account surfaces. " +
                            "See docs/services/customer-service/INTEGRATION.md for the contract.",
                    ),
            ).components(
                Components().addSecuritySchemes(
                    "bearerAuth",
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
                ),
            ).addSecurityItem(SecurityRequirement().addList("bearerAuth"))
}
