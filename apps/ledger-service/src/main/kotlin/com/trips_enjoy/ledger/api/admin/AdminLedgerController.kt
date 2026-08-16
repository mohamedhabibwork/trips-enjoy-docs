package com.trips_enjoy.ledger.api.admin

import com.trips_enjoy.ledger.api.ReconciliationRunResponse
import com.trips_enjoy.ledger.application.AdminAuditPublisher
import com.trips_enjoy.ledger.application.ReconciliationJob
import com.trips_enjoy.ledger.application.ReportService
import com.trips_enjoy.ledger.domain.ReconciliationRun
import com.trips_enjoy.ledger.domain.ReconciliationRunRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

/**
 * Admin endpoints mounted at `/admin/v1/ledger/<...>` per TECH.md §10.4.
 *
 * Network: only reachable from `admin-service`, `platform-ops`, and
 * `platform-engineering` namespaces + bastion. mTLS via linkerd.
 *
 * Pattern: Spring Security 7 method security + audit emission per call.
 */
@RestController
@RequestMapping("/admin/v1/ledger")
class AdminLedgerController(
    private val reconciliationJob: ReconciliationJob,
    private val reconciliationRepository: ReconciliationRunRepository,
    private val reportService: ReportService,
    private val adminAuditPublisher: AdminAuditPublisher,
) {

    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.admin', 'ROLE_platform.super_admin', 'ROLE_platform.admin')")
    fun runReconciliation(
        authentication: Authentication,
        @RequestHeader(value = "X-Reason-Code", required = false) reasonCode: String?,
        @RequestHeader(value = "X-Request-Id", required = false) requestId: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<ReconciliationRunResponse> {
        val startedAt = Instant.now()
        val date = LocalDate.now()
        val run = reconciliationJob.run(date)
        val response = run.toResponse()
        adminAuditPublisher.record(
            authentication = authentication,
            endpoint = "POST /admin/v1/ledger/reconciliation/run",
            targetResource = "reconciliation/${run.runDate}",
            action = "reconciliation.run",
            reasonCode = reasonCode,
            requestId = requestId,
            traceId = null,
            result = run.status,
            durationMs = java.time.Duration.between(startedAt, Instant.now()).toMillis(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/reconciliation/last")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.admin', 'ROLE_ledger.finance', 'ROLE_platform.super_admin', 'ROLE_platform.admin')")
    fun lastReconciliation(
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ReconciliationRunResponse {
        val startedAt = Instant.now()
        val run = reconciliationRepository.findFirstByOrderByRunDateDesc()
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "no reconciliation runs yet")
        adminAuditPublisher.record(
            authentication = authentication,
            endpoint = "GET /admin/v1/ledger/reconciliation/last",
            targetResource = run.id.toString(),
            action = "reconciliation.read",
            reasonCode = null,
            requestId = httpRequest.getHeader("X-Request-Id"),
            traceId = null,
            result = "ok",
            durationMs = java.time.Duration.between(startedAt, Instant.now()).toMillis(),
        )
        return run.toResponse()
    }

    @GetMapping("/accounts/{code}/balance")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.finance', 'ROLE_ledger.admin', 'ROLE_platform.super_admin', 'ROLE_platform.admin')")
    fun accountBalance(
        @PathVariable code: String,
        authentication: Authentication,
        @RequestHeader(value = "X-Reason-Code", required = false) reasonCode: String?,
        httpRequest: HttpServletRequest,
    ): com.trips_enjoy.ledger.api.BalanceResponse {
        val startedAt = Instant.now()
        val balance = reportService.currentBalance(code)
        adminAuditPublisher.record(
            authentication = authentication,
            endpoint = "GET /admin/v1/ledger/accounts/{code}/balance",
            targetResource = code,
            action = "balance.read",
            reasonCode = reasonCode,
            requestId = httpRequest.getHeader("X-Request-Id"),
            traceId = null,
            result = "ok",
            durationMs = java.time.Duration.between(startedAt, Instant.now()).toMillis(),
        )
        return balance
    }

    private fun ReconciliationRun.toResponse(): ReconciliationRunResponse = ReconciliationRunResponse(
        run_date = runDate,
        started_at = startedAt,
        ended_at = endedAt,
        wallet_total = walletTotal,
        earnings_total = earningsTotal,
        settlement_total = settlementTotal,
        ledger_total = ledgerTotal,
        drift_minor = driftMinor,
        status = status,
    )
}
