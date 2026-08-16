package com.trips_enjoy.platform.audit

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("platform.audit")
data class AuditProperties(
    val api: AuditEnabled = AuditEnabled(),
    val admin: AuditEnabled = AuditEnabled(),
    val service: String = "unknown",
)

data class AuditEnabled(
    val enabled: Boolean = false,
    val captureBodyPaths: List<String> = emptyList(),
)

@Configuration
@EnableConfigurationProperties(AuditProperties::class)
internal class AuditAutoConfiguration {

    @Bean
    fun requestAuditFilter(publisher: AuditEventPublisher, properties: AuditProperties): RequestAuditFilter =
        RequestAuditFilter(
            publisher = publisher,
            serviceName = properties.service,
            enabled = properties.api.enabled,
        )
}
