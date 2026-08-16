package com.trips_enjoy.identity.config

import com.trips_enjoy.identity.integration.keycloak.SeedRealmSpec
import com.trips_enjoy.identity.integration.keycloak.SeedSpec
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.OAuthFlow
import io.swagger.v3.oas.models.security.OAuthFlows
import io.swagger.v3.oas.models.security.Scopes
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * OpenAPI surface for the identity-service. When the Keycloak seeder is
 * enabled (and therefore a `SeedSpec` bean exists in the context), this
 * bean consumes it to surface the seeded realm graph: one server URL, one
 * oauth2 `SecurityScheme` per channel client, and one `tags` entry per realm.
 * When the seeder is disabled, the bean falls back to a minimal contract
 * with just `bearerAuth` + Contact.
 */
@Configuration
class OpenApiConfiguration {

    @Bean
    fun identityOpenApi(env: Environment, specProvider: ObjectProvider<SeedSpec>): OpenAPI =
        buildOpenApi(env, specProvider.ifAvailable)

    companion object {
        /**
         * Builds the public identity-service contract. Keycloak is an internal
         * upstream implementation detail and must not appear as a Swagger call
         * target. The service's own URL is therefore used for the server and
         * OAuth authorization/token endpoints; the configured Keycloak base URL
         * is only consumed server-side by the OIDC BFF and admin integrations.
         */
        fun buildOpenApi(env: Environment, spec: SeedSpec?): OpenAPI {
            val serviceUrl = env.getProperty("identity.public-url") ?: "http://localhost:8082"
            val defaultRealm = env.getProperty("identity.keycloak.default-realm", "platform-services")

            val openApi = OpenAPI()
                .info(
                    Info()
                        .title("Trips Enjoy Identity Service API")
                        .version("v1")
                        .description(
                            if (spec == null) {
                                "Internal Keycloak identity adapter API."
                            } else {
                                val realmNames = spec.realms.map { it.realm }
                                "Internal Keycloak identity adapter API. " +
                                    "Auto-seeded realms on boot (when identity.keycloak.seed.enabled=true): " +
                                    realmNames.joinToString() + ". Default realm: $defaultRealm. " +
                                    "Per-service tokens carry <service>.scopes (string[]), <service>.level (int 0..4), and <service>.tenant claims — see INTEGRATION.md §8.11."
                            },
                        )
                        .contact(Contact().name("Identity Service Team").email("identity@trips-enjoy.com")),
                )
                .components(Components().addSecuritySchemes("bearerAuth", SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(SecurityRequirement().addList("bearerAuth"))

            if (spec == null) return openApi

            val realmNames = spec.realms.map { it.realm }
            val channelClients = spec.realms.flatMap { realm: SeedRealmSpec -> realm.channelClients }
            openApi
                .servers(listOf(Server().url(serviceUrl).description("Identity service public API")))
                .tags(
                    realmNames.map { realm ->
                        Tag().name(realm).description("Seeded Keycloak realm: $realm").apply {
                            extensions = mapOf("x-seed-default" to (realm == defaultRealm).toString())
                        }
                    },
                )

            val components = openApi.components ?: Components().also { openApi.components = it }
            channelClients.forEach { cc ->
                components.addSecuritySchemes(
                    "kc-${cc.realm}-${cc.clientId}",
                    SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .description("Identity-service OAuth2 authorization-code flow for ${cc.clientId} on ${cc.realm}")
                        .flows(
                            OAuthFlows().authorizationCode(
                                OAuthFlow()
                                    .authorizationUrl("$serviceUrl/oauth2/authorize?realm=${cc.realm}")
                                    .tokenUrl("$serviceUrl/oauth2/token?realm=${cc.realm}")
                                    .scopes(Scopes().addString("openid", "OIDC").addString("profile", "OIDC profile").addString("email", "OIDC email")),
                            ),
                        ),
                )
            }
            return openApi
        }
    }
}
