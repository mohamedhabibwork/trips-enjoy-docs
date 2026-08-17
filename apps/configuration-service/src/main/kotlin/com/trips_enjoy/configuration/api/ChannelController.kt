package com.trips_enjoy.configuration.api

import com.trips_enjoy.configuration.application.ChannelSubsetService
import com.trips_enjoy.configuration.util.CorrelationContext
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Per-channel client subset endpoint (FR-014 / INTEGRATION.md §1.8).
 */
@RestController
@RequestMapping("/v1/channels")
class ChannelController(
    private val channelSubsetService: ChannelSubsetService,
) {
    @GetMapping("/{channel}/configurations")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.read', 'ROLE_configuration.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun get(
        @PathVariable channel: String,
        @RequestParam(value = "tenant_id", required = false) tenantId: String?,
        @RequestParam(value = "city", required = false) city: String?,
        @RequestParam(value = "ride_type", required = false) rideType: String?,
        @RequestParam(value = "customer_segment", required = false) customerSegment: String?,
        httpRequest: HttpServletRequest,
    ): ChannelSubsetResponse {
        val context = buildContext(tenantId, city, rideType, customerSegment)
        val values =
            channelSubsetService.subsetForChannel(
                channel = channel,
                context = context,
                correlationId = CorrelationContext.correlationId(httpRequest),
            )
        return ChannelSubsetResponse(
            channel = channel,
            as_of = channelSubsetService.asOf(),
            values = values,
        )
    }

    private fun buildContext(
        tenantId: String?,
        city: String?,
        rideType: String?,
        customerSegment: String?,
    ): Map<String, String> =
        buildMap {
            tenantId?.let { put("tenant", it) }
            rideType?.let { put("ride_type", it) }
            city?.let { put("city", it) }
            customerSegment?.let { put("segment", it) }
        }
}
