package com.trips_enjoy.ledger.api

import com.trips_enjoy.ledger.application.PostingService
import com.trips_enjoy.ledger.application.ReportService
import com.trips_enjoy.ledger.domain.AccountRepository
import com.trips_enjoy.ledger.domain.PostingEntryRepository
import com.trips_enjoy.ledger.domain.PostingRepository
import com.trips_enjoy.ledger.api.toResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The v1 ledger API. Mounted at /v1 per INTEGRATION §1. RBAC per TECH §10 +
 * SRS §11:
 *   - ledger.write → POST /v1/postings
 *   - ledger.admin → POST /v1/journal-entries, /v1/reports/<...>
 *   - ledger.read  → all read endpoints
 */
@RestController
@RequestMapping("/v1")
class LedgerController(
    private val postingService: PostingService,
    private val reportService: ReportService,
    private val accountRepository: AccountRepository,
    private val postingRepository: PostingRepository,
    private val entryRepository: PostingEntryRepository,
) {

    @PostMapping("/postings")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.write', 'ROLE_ledger.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun createPosting(
        @Valid @RequestBody request: CreatePostingRequest,
        @RequestHeader(value = "Idempotency-Key") idempotencyKey: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<PostingResponse> {
        if (idempotencyKey.isBlank()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key header is required")
        }
        val correlationId = correlationId(httpRequest)
        val response = postingService.createPosting(request, idempotencyKey, correlationId)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/journal-entries")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.admin', 'ROLE_platform.super_admin', 'ROLE_platform.admin')")
    fun createJournalEntry(
        @Valid @RequestBody request: CreateJournalEntryRequest,
        @RequestHeader(value = "Idempotency-Key") idempotencyKey: String,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<PostingResponse> {
        if (idempotencyKey.isBlank()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key header is required")
        }
        val actorId = runCatching { UUID.fromString(authentication.name) }.getOrElse { UUID(0, 0) }
        val correlationId = correlationId(httpRequest)
        val response = postingService.createJournalEntry(request, actorId, idempotencyKey, correlationId)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/postings/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.read', 'ROLE_ledger.write', 'ROLE_ledger.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun getPosting(@PathVariable id: UUID): PostingResponse {
        val posting = postingRepository.findLatestById(id).firstOrNull()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Posting $id not found")
        val entries = entryRepository.findAllByPostingId(id)
        return posting.toResponse(entries)
    }

    @GetMapping("/postings")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.read', 'ROLE_ledger.write', 'ROLE_ledger.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun listPostings(
        @RequestParam(required = false) account: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): List<PostingResponse> {
        if (page < 0 || size <= 0 || size > 200) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid pagination")
        }
        val postings = postingRepository.search(account, from, to, PageRequest.of(page, size))
        return postings.map { posting ->
            posting.toResponse(entryRepository.findAllByPostingId(posting.id))
        }
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.read', 'ROLE_ledger.write', 'ROLE_ledger.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun listAccounts(): List<AccountResponse> =
        accountRepository.findAllByValidToIsNullOrderByCode().map { it.toResponse() }

    @GetMapping("/accounts/{code}")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.read', 'ROLE_ledger.write', 'ROLE_ledger.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun getAccount(@PathVariable code: String): AccountResponse {
        val account = accountRepository.findByCodeAndValidToIsNull(code).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Account $code not found")
        }
        return account.toResponse()
    }

    @GetMapping("/accounts/{code}/balance")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.read', 'ROLE_ledger.write', 'ROLE_ledger.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
    fun getBalance(
        @PathVariable code: String,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): BalanceResponse {
        accountRepository.findByCodeAndValidToIsNull(code).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Account $code not found")
        }
        return if (from != null || to != null) {
            reportService.balanceOverRange(code, from, to)
        } else {
            reportService.currentBalance(code)
        }
    }

    @GetMapping("/reports/trial-balance")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.admin', 'ROLE_platform.super_admin', 'ROLE_platform.admin', 'ROLE_platform.finance', 'ROLE_ledger.finance')")
    fun trialBalance(
        @RequestParam(name = "date") date: LocalDate,
    ): TrialBalanceResponse = reportService.trialBalance(date)

    @GetMapping("/reports/balance-sheet")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.admin', 'ROLE_platform.super_admin', 'ROLE_platform.admin', 'ROLE_platform.finance', 'ROLE_ledger.finance')")
    fun balanceSheet(
        @RequestParam(name = "date") date: LocalDate,
    ): BalanceSheetResponse = reportService.balanceSheet(date)

    @GetMapping("/reports/income-statement")
    @PreAuthorize("hasAnyAuthority('ROLE_ledger.admin', 'ROLE_platform.super_admin', 'ROLE_platform.admin', 'ROLE_platform.finance', 'ROLE_ledger.finance')")
    fun incomeStatement(
        @RequestParam(name = "from") from: Instant,
        @RequestParam(name = "to") to: Instant,
    ): IncomeStatementResponse = reportService.incomeStatement(from, to)

    private fun correlationId(request: HttpServletRequest): UUID =
        runCatching { UUID.fromString(request.getAttribute("correlationId")?.toString() ?: "") }.getOrNull()
            ?: UUID.randomUUID()
}
