package com.trips_enjoy.platform.observability

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("platform.observability")
data class ObservabilityProperties(
    val service: String = "unknown",
    val env: String = "dev",
    val region: String = "local",
    val tenant: String = "global",
)
