package com.trips_enjoy.platform.apidocs

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("platform.api-docs")
data class ApiDocsProperties(
    val title: String = "Platform API",
    val version: String = "1.0.0",
    val description: String = "",
    val contactName: String = "Platform Team",
    val contactEmail: String = "platform@trips-enjoy.com",
)

@Configuration
@EnableConfigurationProperties(ApiDocsProperties::class)
internal class OpenApiConfiguration {

    @Bean
    fun platformOpenApi(properties: ApiDocsProperties): OpenAPI {
        val securityScheme = SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description("Keycloak/OAuth2 bearer token")
        return OpenAPI()
            .info(
                Info()
                    .title(properties.title)
                    .version(properties.version)
                    .description(properties.description)
                    .contact(Contact().name(properties.contactName).email(properties.contactEmail))
            )
            .components(Components().addSecuritySchemes("bearerAuth", securityScheme))
            .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
    }
}
