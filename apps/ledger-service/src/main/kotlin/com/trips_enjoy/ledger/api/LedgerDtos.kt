package com.trips_enjoy.ledger.api

import com.trips_enjoy.ledger.domain.Account
import com.trips_enjoy.ledger.domain.Posting
import com.trips_enjoy.ledger.domain.PostingEntry
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---------------------------------------------------------------------------
// Inbound API DTOs — request shapes per docs/services/ledger-service/INTEGRATION.md §1
// ---------------------------------------------------------------------------

/** POST /v1/postings — INTEGRATION §1.1 */
data class CreatePostingRequest(
    @field:NotBlank val description: String,
    val posted_at: Instant? = null,
    val source_event_id: UUID,
    @field:NotBlank val source_event_name: String,
    @field:NotEmpty @field:Valid val entries: List<PostingEntryDto>,
)

/** POST /v1/journal-entries — INTEGRATION §1.2 */
data class CreateJournalEntryRequest(
    @field:NotBlank val description: String,
    @field:NotBlank @field:Size(min = 10) val audit_note: String,
    @field:NotEmpty @field:Valid val entries: List<PostingEntryDto>,
)

/** Single entry line shared by Create + Journal entry requests. */
data class PostingEntryDto(
    @field:NotBlank val account_code: String,
    /** `debit` or `credit`. */
    @field:NotBlank val side: String,
    @field:Min(1) val amount_minor: Long,
    @field:NotBlank val currency: String,
)

// ---------------------------------------------------------------------------
// Response DTOs
// ---------------------------------------------------------------------------

/** Response from POST /v1/postings + POST /v1/journal-entries. */
data class PostingResponse(
    val posting_id: UUID,
    val posted_at: Instant,
    val description: String,
    val source_event_id: UUID,
    val source_event_name: String,
    val tenant_id: String,
    val actor_type: String,
    val actor_id: UUID?,
    val audit_note: String?,
    val entries: List<PostingEntryResponse>,
    val correlation_id: UUID,
)

data class PostingEntryResponse(
    val account_code: String,
    val account_version: Int,
    val side: String,
    val amount_minor: Long,
    val currency: String,
)

/** GET /v1/accounts/{code} response. */
data class AccountResponse(
    val code: String,
    val name: String,
    val type: String,
    val currency: String,
    val parent_code: String?,
    val version: Int,
    val valid_from: Instant,
    val valid_to: Instant?,
)

/** GET /v1/accounts/{code}/balance response. */
data class BalanceResponse(
    val account_code: String,
    val currency: String,
    val debit_total_minor: Long,
    val credit_total_minor: Long,
    val balance_minor: Long,
    val as_of: Instant?,
    val from: Instant? = null,
    val to: Instant? = null,
)

/** GET /v1/reports/trial-balance?date=… response. */
data class TrialBalanceResponse(
    val as_of: Instant,
    val currency: String,
    val accounts: List<TrialBalanceLine>,
    val totals: TrialBalanceTotals,
)

data class TrialBalanceLine(
    val code: String,
    val name: String,
    val type: String,
    val currency: String,
    val debit_minor: Long,
    val credit_minor: Long,
    val balance_minor: Long,
)

data class TrialBalanceTotals(
    val debit_minor: Long,
    val credit_minor: Long,
    val balanced: Boolean,
    val drift_minor: Long,
)

/** GET /v1/reports/balance-sheet?date=… response. */
data class BalanceSheetResponse(
    val as_of: Instant,
    val assets: List<ReportLine>,
    val liabilities: List<ReportLine>,
    val equity: List<ReportLine>,
    val totals: BalanceSheetTotals,
)

data class ReportLine(
    val code: String,
    val balance_minor: Long,
)

data class BalanceSheetTotals(
    val assets_minor: Long,
    val liabilities_plus_equity_minor: Long,
    val balanced: Boolean,
)

/** GET /v1/reports/income-statement?from=…&to=… response. */
data class IncomeStatementResponse(
    val from: Instant,
    val to: Instant,
    val currency: String,
    val revenue: List<ReportLine>,
    val expenses: List<ReportLine>,
    val totals: IncomeStatementTotals,
)

data class IncomeStatementTotals(
    val revenue_minor: Long,
    val expense_minor: Long,
    val net_income_minor: Long,
)

// ---------------------------------------------------------------------------
// Reconciliation trigger response (admin)
// ---------------------------------------------------------------------------

data class ReconciliationRunResponse(
    val run_date: LocalDate,
    val started_at: Instant,
    val ended_at: Instant?,
    val wallet_total: Long,
    val earnings_total: Long,
    val settlement_total: Long,
    val ledger_total: Long,
    val drift_minor: Long,
    val status: String,
)

// ---------------------------------------------------------------------------
// Mappers (top-level helpers, used by services + controllers)
// ---------------------------------------------------------------------------

fun Posting.toResponse(entries: List<PostingEntry>): PostingResponse = PostingResponse(
    posting_id = id,
    posted_at = postedAt,
    description = description,
    source_event_id = sourceEventId,
    source_event_name = sourceEventName,
    tenant_id = tenantId,
    actor_type = actorType,
    actor_id = actorId,
    audit_note = auditNote,
    entries = entries.map { it.toResponse() },
    correlation_id = correlationId,
)

fun PostingEntry.toResponse(): PostingEntryResponse = PostingEntryResponse(
    account_code = accountCode,
    account_version = accountVersion,
    side = side,
    amount_minor = amountMinor,
    currency = currency,
)

fun Account.toResponse(): AccountResponse = AccountResponse(
    code = code,
    name = name,
    type = type,
    currency = currency,
    parent_code = parentCode,
    version = version,
    valid_from = validFrom,
    valid_to = validTo,
)

fun PostingEntryDto.toEntity(
    postingId: UUID,
    accountVersion: Int,
    postedAt: Instant,
    correlationId: UUID,
): PostingEntry = PostingEntry(
    postingId = postingId,
    accountCode = account_code,
    accountVersion = accountVersion,
    side = side.lowercase(),
    amountMinor = amount_minor,
    currency = currency.uppercase(),
    postedAt = postedAt,
    correlationId = correlationId,
)
