package com.trips_enjoy.admin.config

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
    fun adminServiceOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("admin-service API")
                .version("4.1.0")
                .description(
                    "admin-service is the platform's operations console and the " +
                        "BFF aggregator for every high-value mutation. It owns " +
                        "the SUPER_ADMIN preset (1 × platform.super_admin + 20 × " +
                        "<service>.admin), the break-glass co-signature workflow, " +
                        "and the per-service BFF wrappers. Full contract: " +
                        "docs/services/admin-service/INTEGRATION.md."
                )
        )
        .servers(
            listOf(
                Server().url("https://api.trips-enjoy.com").description("production"),
                Server().url("https://api.stg.trips-enjoy.com").description("staging"),
                Server().url("https://api.dev.trips-enjoy.com").description("dev"),
                Server().url("http://localhost:8080").description("local"),
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