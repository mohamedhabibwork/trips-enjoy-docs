package com.trips_enjoy.notification.config

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
	fun notificationOpenApi(): OpenAPI = OpenAPI()
		.info(
			Info()
				.title("Trips Enjoy Notification Service API")
				.version("v1")
				.description(
					"User-visible messaging orchestrator. Channels: push, sms, email, " +
						"in_app, whatsapp. Templates + immutable template_history + delivery state. " +
						"See docs/services/notification-service/INTEGRATION.md for the contract.",
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