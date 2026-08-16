package com.trips_enjoy.ledger.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {
    @Bean
    fun ledgerOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Trips Enjoy Ledger Service API")
                .version("v1")
                .description(
                    "Double-entry financial ledger. Chart of accounts, postings, balances, " +
                        "financial reports, manual journal entries, reconciliation. " +
                        "See docs/services/ledger-service/INTEGRATION.md for the contract.",
                ),
        )
        .components(
            Components().addSecuritySchemes(
                "bearerAuth",
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
}
