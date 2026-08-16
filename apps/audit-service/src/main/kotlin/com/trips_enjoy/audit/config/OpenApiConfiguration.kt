package com.trips_enjoy.audit.config

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
    fun auditOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Trips Enjoy Audit Service API")
                .version("v1")
                .description(
                    "Immutable audit log: ingest (Kafka), search, verify, litigation hold. " +
                        "See docs/services/audit-service/INTEGRATION.md for the contract.",
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
