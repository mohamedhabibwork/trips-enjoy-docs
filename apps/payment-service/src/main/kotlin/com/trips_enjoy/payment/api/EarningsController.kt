package com.trips_enjoy.payment.api

import com.trips_enjoy.payment.domain.DriverEarnings
import com.trips_enjoy.payment.domain.DriverEarningsRepository
import com.trips_enjoy.payment.domain.MerchantSettlement
import com.trips_enjoy.payment.domain.MerchantSettlementRepository
import com.trips_enjoy.payment.domain.CourierEarnings
import com.trips_enjoy.payment.domain.CourierEarningsRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The earnings REST controller — exposes driver / courier period
 * earnings + merchant settlement aggregates. Implements
 * docs/services/payment-service/INTEGRATION.md §4:
 *   * GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily
 *   * GET /v1/drivers/{id}/earnings
 *   * GET /v1/courier-earnings?courier_id=...
 *   * GET /v1/merchant-settlements?merchant_id=...
 */
@RestController
@RequestMapping("/v1")
class EarningsController(
    private val driverEarningsRepository: DriverEarningsRepository,
    private val courierEarningsRepository: CourierEarningsRepository,
    private val merchantSettlementRepository: MerchantSettlementRepository,
) {

    @GetMapping("/drivers/{id}/period-eligible-earnings")
    @PreAuthorize("isAuthenticated()")
    fun getDriverPeriodEarnings(
        @PathVariable("id") driverId: String,
        @RequestParam("window") window: String = "daily",
    ): List<DriverEarningsResponse> {
        val now = Instant.now()
        val periodKind = if (window == "hourly") DriverEarnings.PERIOD_HOURLY
                         else if (window == "weekly") DriverEarnings.PERIOD_WEEKLY
                         else DriverEarnings.PERIOD_DAILY
        val periods = driverEarningsRepository.findByDriverIdAndState(
            driverId = UUID.fromString(driverId),
            state = DriverEarnings.STATE_OPEN,
        ).filter { it.periodKind == periodKind && it.periodEnd.isAfter(now.minusSeconds(86400 * 30)) }
        return periods.map { it.toResponse() }
    }

    @GetMapping("/drivers/{id}/earnings")
    @PreAuthorize("isAuthenticated()")
    fun getDriverEarnings(
        @PathVariable("id") driverId: String,
    ): List<DriverEarningsResponse> =
        driverEarningsRepository.findByDriverIdAndState(
            driverId = UUID.fromString(driverId),
            state = DriverEarnings.STATE_OPEN,
        ).map { it.toResponse() }

    @GetMapping("/courier-earnings")
    @PreAuthorize("isAuthenticated()")
    fun getCourierEarnings(@RequestParam("courier_id") courierId: String): List<CourierEarningsResponse> =
        courierEarningsRepository.findByCourierIdAndState(
            courierId = UUID.fromString(courierId),
            state = CourierEarnings.STATE_OPEN,
        ).map { it.toResponse() }

    @GetMapping("/merchant-settlements")
    @PreAuthorize("isAuthenticated()")
    fun getMerchantSettlements(@RequestParam("merchant_id") merchantId: String): List<MerchantSettlementResponse> =
        merchantSettlementRepository.findByMerchantIdAndState(
            merchantId = UUID.fromString(merchantId),
            state = MerchantSettlement.STATE_OPEN,
        ).map { it.toResponse() }
}

private fun DriverEarnings.toResponse() = DriverEarningsResponse(
    earningsId = id.toString(),
    driverId = driverId.toString(),
    periodKind = periodKind,
    periodStart = periodStart.toString(),
    periodEnd = periodEnd.toString(),
    currency = currency,
    ridesCount = ridesCount,
    grossFareMinor = grossFareMinor,
    commissionMinor = commissionMinor,
    tipMinor = tipMinor,
    bonusMinor = bonusMinor,
    guaranteedTopupMinor = guaranteedTopupMinor,
    correctionMinor = correctionMinor,
    netPayMinor = netPayMinor,
    state = state,
)

private fun CourierEarnings.toResponse() = CourierEarningsResponse(
    earningsId = id.toString(),
    courierId = courierId.toString(),
    periodKind = periodKind,
    periodStart = periodStart.toString(),
    periodEnd = periodEnd.toString(),
    currency = currency,
    deliveriesCount = deliveriesCount,
    grossFeeMinor = grossFeeMinor,
    commissionMinor = commissionMinor,
    tipMinor = tipMinor,
    bonusMinor = bonusMinor,
    correctionMinor = correctionMinor,
    netPayMinor = netPayMinor,
    state = state,
)

private fun MerchantSettlement.toResponse() = MerchantSettlementResponse(
    settlementId = id.toString(),
    merchantId = merchantId.toString(),
    periodStart = periodStart.toString(),
    periodEnd = periodEnd.toString(),
    currency = currency,
    ordersCount = ordersCount,
    grossRevenueMinor = grossRevenueMinor,
    commissionMinor = commissionMinor,
    adjustmentsMinor = adjustmentsMinor,
    refundReversalMinor = refundReversalMinor,
    netPayoutMinor = netPayoutMinor,
    state = state,
)

data class CourierEarningsResponse(
    val earningsId: String,
    val courierId: String,
    val periodKind: String,
    val periodStart: String,
    val periodEnd: String,
    val currency: String,
    val deliveriesCount: Int,
    val grossFeeMinor: Long,
    val commissionMinor: Long,
    val tipMinor: Long,
    val bonusMinor: Long,
    val correctionMinor: Long,
    val netPayMinor: Long,
    val state: String,
)