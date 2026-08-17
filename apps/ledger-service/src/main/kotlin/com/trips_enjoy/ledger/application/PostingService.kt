package com.trips_enjoy.ledger.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.ledger.api.ApiException
import com.trips_enjoy.ledger.api.CreateJournalEntryRequest
import com.trips_enjoy.ledger.api.CreatePostingRequest
import com.trips_enjoy.ledger.api.PostingResponse
import com.trips_enjoy.ledger.api.toEntity
import com.trips_enjoy.ledger.api.toResponse
import com.trips_enjoy.ledger.domain.Account
import com.trips_enjoy.ledger.domain.AccountBalance
import com.trips_enjoy.ledger.domain.AccountBalanceRepository
import com.trips_enjoy.ledger.domain.AccountRepository
import com.trips_enjoy.ledger.domain.JournalEntry
import com.trips_enjoy.ledger.domain.JournalEntryRepository
import com.trips_enjoy.ledger.domain.OutboxEvent
import com.trips_enjoy.ledger.domain.OutboxEventRepository
import com.trips_enjoy.ledger.domain.Posting
import com.trips_enjoy.ledger.domain.PostingEntry
import com.trips_enjoy.ledger.domain.PostingEntryRepository
import com.trips_enjoy.ledger.domain.PostingRepository
import com.trips_enjoy.ledger.util.uuidV7
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The core write path. Implements the double-entry invariant
 * (sum debits == sum credits, all entries share one currency, every account
 * exists, clock-skew guard) and persists posting + entries + balances +
 * outbox row + (for manual) journal_entry in a single transaction. Per
 * docs/services/ledger-service/SRS §14, account rows are locked
 * PESSIMISTIC_WRITE so concurrent postings to the same account serialize.
 *
 * Per ERD §9 + SRS FR--018, the application role has INSERT-only privileges
 * on postings / posting_entries (the database trigger rejects UPDATE/DELETE
 * as a belt-and-suspenders guard).
 */
@Service
class PostingService(
    private val postingRepository: PostingRepository,
    private val postingEntryRepository: PostingEntryRepository,
    private val accountRepository: AccountRepository,
    private val balanceRepository: AccountBalanceRepository,
    private val outboxRepository: OutboxEventRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val objectMapper: ObjectMapper,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Synchronous write path used by `POST /v1/postings`.
     */
    @Transactional
    fun createPosting(
        request: CreatePostingRequest,
        idempotencyKey: String,
        correlationId: UUID,
    ): PostingResponse {
        val now = Instant.now()
        val postedAt = request.posted_at ?: now

        // Clock-skew guard (SRS FR--021): ±5 minutes from the server wall clock.
        if (Duration.between(postedAt, now).abs() > Duration.ofMinutes(5)) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TIMESTAMP_OUT_OF_BOUNDS",
                "posted_at must be within ±5 minutes of server wall clock")
        }

        // Idempotency replay (SRS §15).
        val existing = postingRepository.findByIdempotencyKey(idempotencyKey).orElse(null)
        if (existing != null) {
            val entries = postingEntryRepository.findAllByPostingId(existing.id)
            log.info("Idempotency replay for key={} -> posting_id={}", idempotencyKey, existing.id)
            return existing.toResponse(entries)
        }

        val posting = buildAndPersist(
            description = request.description,
            postedAt = postedAt,
            sourceEventId = request.source_event_id,
            sourceEventName = request.source_event_name,
            idempotencyKey = idempotencyKey,
            actorType = "service",
            actorId = null,
            auditNote = null,
            entries = request.entries,
            correlationId = correlationId,
            tenantId = "global",
        )

        // Metrics: posting counter + balance gauge refresh
        posting.entryResponses.forEach { entry ->
            meters.counter(
                "ledger_postings_total",
                "account_type", entry.accountCode.substringBefore('_', "unknown"),
                "currency", entry.currency,
            ).increment()
        }
        meters.timer("ledger_posting_seconds").record(Duration.between(now, Instant.now()))
        return posting.posting.toResponse(posting.entryResponses)
    }

    /**
     * Manual journal entry path used by `POST /v1/journal-entries`. Identical
     * write semantics as [createPosting] plus an audit_note and a
     * journal_entries row.
     */
    @Transactional
    fun createJournalEntry(
        request: CreateJournalEntryRequest,
        actorId: UUID,
        idempotencyKey: String,
        correlationId: UUID,
    ): PostingResponse {
        if (request.audit_note.length < 10) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AUDIT_NOTE_REQUIRED",
                "audit_note must be at least 10 characters")
        }
        val now = Instant.now()

        val existing = postingRepository.findByIdempotencyKey(idempotencyKey).orElse(null)
        if (existing != null) {
            val entries = postingEntryRepository.findAllByPostingId(existing.id)
            return existing.toResponse(entries)
        }

        val posting = buildAndPersist(
            description = request.description,
            postedAt = now,
            sourceEventId = uuidV7(),
            sourceEventName = "ledger.audit.journal_entry_logged.v1",
            idempotencyKey = idempotencyKey,
            actorType = "admin",
            actorId = actorId,
            auditNote = request.audit_note,
            entries = request.entries,
            correlationId = correlationId,
            tenantId = "global",
        )

        // Manual entries also record a journal_entries row + the audit event in the outbox.
        journalEntryRepository.save(
            JournalEntry(
                id = uuidV7(),
                description = request.description,
                actorId = actorId,
                auditNote = request.audit_note,
                correlationId = correlationId,
            ),
        )
        return posting.posting.toResponse(posting.entryResponses)
    }

    /**
     * Common write kernel shared by both entry points. Validates the
     * double-entry invariant, resolves accounts, locks rows, writes the
     * posting + entries + balance updates + outbox row in one transaction.
     */
    private fun buildAndPersist(
        description: String,
        postedAt: Instant,
        sourceEventId: UUID,
        sourceEventName: String,
        idempotencyKey: String,
        actorType: String,
        actorId: UUID?,
        auditNote: String?,
        entries: List<com.trips_enjoy.ledger.api.PostingEntryDto>,
        correlationId: UUID,
        tenantId: String,
    ): PostingAggregate {
        if (entries.size < 2) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNBALANCED_POSTING",
                "A posting must have at least two entries")
        }
        val currencies = entries.map { it.currency.uppercase() }.toSet()
        if (currencies.size != 1) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH",
                "All entries in a posting must share the same currency (got $currencies)")
        }
        val currency = currencies.first()

        val debitSum = entries.filter { it.side.equals("debit", ignoreCase = true) }.sumOf { it.amount_minor }
        val creditSum = entries.filter { it.side.equals("credit", ignoreCase = true) }.sumOf { it.amount_minor }
        if (debitSum != creditSum) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNBALANCED_POSTING",
                "sum of debits ($debitSum) must equal sum of credits ($creditSum) for currency $currency")
        }
        if (debitSum <= 0) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNBALANCED_POSTING",
                "A posting must move a non-zero amount")
        }

        // Resolve every account row in the current version + acquire a row-level
        // write lock. We sort by code so the lock acquisition order is stable
        // and the worst-case deadlock window is bounded.
        val accountsByCode = entries
            .map { it.account_code }
            .toSortedSet()
            .map { code ->
                accountRepository.lockCurrentByCode(code).orElseThrow {
                    ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_NOT_FOUND",
                        "Account code '$code' does not exist (or is not the current version)")
                }
            }
            .associateBy { it.code }

        val postingId = uuidV7()
        val posting = Posting(
            id = postingId,
            postedAt = postedAt,
            description = description,
            sourceEventId = sourceEventId,
            sourceEventName = sourceEventName,
            correlationId = correlationId,
            tenantId = tenantId,
            idempotencyKey = idempotencyKey,
            actorType = actorType,
            actorId = actorId,
            auditNote = auditNote,
        )
        postingRepository.save(posting)

        val entryEntities = entries.map { dto ->
            val account: Account = accountsByCode.getValue(dto.account_code)
            if (account.currency != currency) {
                throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH",
                    "Entry ${dto.account_code} currency (${account.currency}) does not match posting currency ($currency)")
            }
            dto.toEntity(
                postingId = postingId,
                accountVersion = account.version,
                postedAt = postedAt,
                correlationId = correlationId,
            )
        }
        postingEntryRepository.saveAll(entryEntities)

        // Update the materialised balance per account. We lock the balance row
        // (or create it) and apply the new entry, then refresh the gauge.
        entries.forEach { dto ->
            val account: Account = accountsByCode.getValue(dto.account_code)
            val balance = balanceRepository.findById(dto.account_code).orElseGet {
                AccountBalance(
                    accountCode = dto.account_code,
                    currency = account.currency,
                )
            }
            when (dto.side.lowercase()) {
                "debit" -> balance.debitTotalMinor += dto.amount_minor
                "credit" -> balance.creditTotalMinor += dto.amount_minor
            }
            balance.balanceMinor = balance.debitTotalMinor - balance.creditTotalMinor
            balance.lastPostingAt = postedAt
            balance.updatedAt = Instant.now()
            balanceRepository.save(balance)
            meters.gauge(
                "ledger_balance_total",
                listOf(
                    io.micrometer.core.instrument.Tag.of("account_type", account.type),
                    io.micrometer.core.instrument.Tag.of("currency", account.currency),
                ),
                balance,
            ) { it.balanceMinor.toDouble() }
        }

        // Emit `ledger.posted.v1` (and the journal-entry audit event for manual).
        val envelope = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to "ledger.posted.v1",
                "occurred_at" to postedAt.toString(),
                "schema_version" to 1,
                "producer" to "ledger-service",
                "tenant_id" to tenantId,
                "correlation_id" to correlationId.toString(),
                "aggregate_type" to "Posting",
                "aggregate_id" to postingId.toString(),
                "data" to mapOf(
                    "posting_id" to postingId.toString(),
                    "posted_at" to postedAt.toString(),
                    "description" to description,
                    "source_event_id" to sourceEventId.toString(),
                    "source_event_name" to sourceEventName,
                    "tenant_id" to tenantId,
                    "actor_type" to actorType,
                    "actor_id" to actorId?.toString(),
                    "audit_note" to auditNote,
                    "entries" to entryEntities.map { e ->
                        mapOf(
                            "account_code" to e.accountCode,
                            "account_version" to e.accountVersion,
                            "side" to e.side,
                            "amount_minor" to e.amountMinor,
                            "currency" to e.currency,
                        )
                    },
                    "correlation_id" to correlationId.toString(),
                ),
            ),
        )
        outboxRepository.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "Posting",
                aggregateId = postingId,
                topic = "ledger.posted",
                eventName = "ledger.posted.v1",
                payload = envelope,
            ),
        )

        // Manual entries also emit the journal-entry audit event (100% sampling).
        if (actorType == "admin" && auditNote != null) {
            val auditEnvelope = objectMapper.writeValueAsString(
                mapOf(
                    "event_id" to uuidV7().toString(),
                    "event_name" to "ledger.audit.journal_entry_logged.v1",
                    "occurred_at" to postedAt.toString(),
                    "schema_version" to 1,
                    "producer" to "ledger-service",
                    "tenant_id" to tenantId,
                    "correlation_id" to correlationId.toString(),
                    "aggregate_type" to "Posting",
                    "aggregate_id" to postingId.toString(),
                    "data" to mapOf(
                        "posting_id" to postingId.toString(),
                        "actor_id" to actorId?.toString(),
                        "audit_note" to auditNote,
                        "correlation_id" to correlationId.toString(),
                    ),
                ),
            )
            outboxRepository.save(
                OutboxEvent(
                    id = uuidV7(),
                    aggregateType = "Posting",
                    aggregateId = postingId,
                    topic = "ledger.audit.journal_entry_logged",
                    eventName = "ledger.audit.journal_entry_logged.v1",
                    payload = auditEnvelope,
                ),
            )
        }

        log.info("Posting committed: id={} amount={} currency={} entries={}",
            postingId, debitSum, currency, entryEntities.size)

        return PostingAggregate(posting, entryEntities)
    }

    /** Helper container so we can return the posting + entries to the controller. */
    private data class PostingAggregate(
        val posting: Posting,
        val entryResponses: List<PostingEntry>,
    )
}
