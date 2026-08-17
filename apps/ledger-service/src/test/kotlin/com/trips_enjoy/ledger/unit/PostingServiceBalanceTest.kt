package com.trips_enjoy.ledger.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.ledger.api.ApiException
import com.trips_enjoy.ledger.api.CreateJournalEntryRequest
import com.trips_enjoy.ledger.api.CreatePostingRequest
import com.trips_enjoy.ledger.api.PostingEntryDto
import com.trips_enjoy.ledger.application.PostingService
import com.trips_enjoy.ledger.domain.Account
import com.trips_enjoy.ledger.domain.AccountBalance
import com.trips_enjoy.ledger.domain.AccountBalanceRepository
import com.trips_enjoy.ledger.domain.AccountRepository
import com.trips_enjoy.ledger.domain.JournalEntryRepository
import com.trips_enjoy.ledger.domain.OutboxEventRepository
import com.trips_enjoy.ledger.domain.PostingEntry
import com.trips_enjoy.ledger.domain.PostingEntryRepository
import com.trips_enjoy.ledger.domain.PostingRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for the PostingService. These tests pin the double-entry
 * invariants (sum debits == sum credits, single currency, account must
 * exist, audit_note length, etc.) without bringing up Spring or the DB.
 * The 100% coverage on these invariants is part of SRS §23 acceptance.
 */
class PostingServiceBalanceTest {

    private val postingRepository: PostingRepository = mock(PostingRepository::class.java)
    private val postingEntryRepository: PostingEntryRepository = mock(PostingEntryRepository::class.java)
    private val accountRepository: AccountRepository = mock(AccountRepository::class.java)
    private val balanceRepository: AccountBalanceRepository = mock(AccountBalanceRepository::class.java)
    private val outboxRepository: OutboxEventRepository = mock(OutboxEventRepository::class.java)
    private val journalEntryRepository: JournalEntryRepository = mock(JournalEntryRepository::class.java)

    private val objectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        registerModule(KotlinModule.Builder().build())
    }

    private val service = PostingService(
        postingRepository = postingRepository,
        postingEntryRepository = postingEntryRepository,
        accountRepository = accountRepository,
        balanceRepository = balanceRepository,
        outboxRepository = outboxRepository,
        journalEntryRepository = journalEntryRepository,
        objectMapper = objectMapper,
        meters = SimpleMeterRegistry(),
    )

    private val cashAccount = Account(
        id = UUID.randomUUID(),
        code = "1100_cash_eur",
        name = "Cash (EUR)",
        type = "asset",
        currency = "EUR",
        parentCode = "1000_assets",
        version = 1,
        validFrom = Instant.now(),
        validTo = null,
        createdBy = UUID(0, 0),
    )
    private val receivableAccount = Account(
        id = UUID.randomUUID(),
        code = "2100_customer_receivable",
        name = "Customer receivable",
        type = "liability",
        currency = "EUR",
        parentCode = "2000_liabilities",
        version = 1,
        validFrom = Instant.now(),
        validTo = null,
        createdBy = UUID(0, 0),
    )

    private fun validRequest(balanced: Boolean = true) = CreatePostingRequest(
        description = "test",
        posted_at = Instant.now(),
        source_event_id = UUID.randomUUID(),
        source_event_name = "payment.captured.v1",
        entries = if (balanced) {
            listOf(
                PostingEntryDto("1100_cash_eur", "debit", 1000, "EUR"),
                PostingEntryDto("2100_customer_receivable", "credit", 1000, "EUR"),
            )
        } else {
            listOf(
                PostingEntryDto("1100_cash_eur", "debit", 1000, "EUR"),
                PostingEntryDto("2100_customer_receivable", "credit", 999, "EUR"),
            )
        },
    )

    private fun stubHappyPath() {
        `when`(postingRepository.findByIdempotencyKey("test-key-1")).thenReturn(Optional.empty())
        `when`(postingRepository.save(org.mockito.ArgumentMatchers.any(com.trips_enjoy.ledger.domain.Posting::class.java))).thenAnswer { it.arguments[0] }
        `when`(accountRepository.lockCurrentByCode("1100_cash_eur")).thenReturn(Optional.of(cashAccount))
        `when`(accountRepository.lockCurrentByCode("2100_customer_receivable")).thenReturn(Optional.of(receivableAccount))
        `when`(balanceRepository.findById("1100_cash_eur")).thenReturn(Optional.empty())
        `when`(balanceRepository.findById("2100_customer_receivable")).thenReturn(Optional.empty())
        `when`(balanceRepository.save(org.mockito.ArgumentMatchers.any(AccountBalance::class.java))).thenAnswer { it.arguments[0] }
        `when`(postingEntryRepository.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            it.arguments[0] as List<PostingEntry>
        }
        `when`(outboxRepository.save(org.mockito.ArgumentMatchers.any(com.trips_enjoy.ledger.domain.OutboxEvent::class.java))).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `balanced posting is accepted`() {
        stubHappyPath()

        val response = service.createPosting(
            request = validRequest(),
            idempotencyKey = "test-key-1",
            correlationId = UUID.randomUUID(),
        )

        assertEquals(1000L, response.entries.first { it.side == "debit" }.amount_minor)
        assertEquals(1000L, response.entries.first { it.side == "credit" }.amount_minor)
    }

    @Test
    fun `unbalanced posting is rejected with UNBALANCED_POSTING`() {
        `when`(postingRepository.findByIdempotencyKey("test-key-2")).thenReturn(Optional.empty())
        val exception = assertThrows(ApiException::class.java) {
            service.createPosting(
                request = validRequest(balanced = false),
                idempotencyKey = "test-key-2",
                correlationId = UUID.randomUUID(),
            )
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status)
        assertEquals("UNBALANCED_POSTING", exception.code)
    }

    @Test
    fun `currency mismatch is rejected with CURRENCY_MISMATCH`() {
        `when`(postingRepository.findByIdempotencyKey("test-key-3")).thenReturn(Optional.empty())
        val mixed = CreatePostingRequest(
            description = "test",
            posted_at = Instant.now(),
            source_event_id = UUID.randomUUID(),
            source_event_name = "payment.captured.v1",
            entries = listOf(
                PostingEntryDto("1100_cash_eur", "debit", 1000, "EUR"),
                PostingEntryDto("2100_customer_receivable", "credit", 1000, "USD"),
            ),
        )
        val exception = assertThrows(ApiException::class.java) {
            service.createPosting(mixed, "test-key-3", UUID.randomUUID())
        }
        assertEquals("CURRENCY_MISMATCH", exception.code)
    }

    @Test
    fun `unknown account is rejected with ACCOUNT_NOT_FOUND`() {
        `when`(postingRepository.findByIdempotencyKey("test-key-4")).thenReturn(Optional.empty())
        `when`(accountRepository.lockCurrentByCode("1100_cash_eur")).thenReturn(Optional.of(cashAccount))
        `when`(accountRepository.lockCurrentByCode("2100_customer_receivable")).thenReturn(Optional.empty())

        val exception = assertThrows(ApiException::class.java) {
            service.createPosting(validRequest(), "test-key-4", UUID.randomUUID())
        }
        assertEquals("ACCOUNT_NOT_FOUND", exception.code)
    }

    @Test
    fun `clock skew beyond 5 minutes is rejected with TIMESTAMP_OUT_OF_BOUNDS`() {
        `when`(postingRepository.findByIdempotencyKey("test-key-5")).thenReturn(Optional.empty())
        val skewed = CreatePostingRequest(
            description = "test",
            posted_at = Instant.now().minusSeconds(3600),
            source_event_id = UUID.randomUUID(),
            source_event_name = "payment.captured.v1",
            entries = listOf(
                PostingEntryDto("1100_cash_eur", "debit", 1000, "EUR"),
                PostingEntryDto("2100_customer_receivable", "credit", 1000, "EUR"),
            ),
        )
        val exception = assertThrows(ApiException::class.java) {
            service.createPosting(skewed, "test-key-5", UUID.randomUUID())
        }
        assertEquals("TIMESTAMP_OUT_OF_BOUNDS", exception.code)
    }

    @Test
    fun `journal entry with short audit_note is rejected with AUDIT_NOTE_REQUIRED`() {
        val bad = CreateJournalEntryRequest(
            description = "test",
            audit_note = "short",
            entries = listOf(
                PostingEntryDto("1100_cash_eur", "debit", 1000, "EUR"),
                PostingEntryDto("2100_customer_receivable", "credit", 1000, "EUR"),
            ),
        )
        val exception = assertThrows(ApiException::class.java) {
            service.createJournalEntry(bad, UUID.randomUUID(), "test-key-6", UUID.randomUUID())
        }
        assertEquals("AUDIT_NOTE_REQUIRED", exception.code)
    }

    @Test
    fun `single-entry posting is rejected with UNBALANCED_POSTING`() {
        `when`(postingRepository.findByIdempotencyKey("test-key-7")).thenReturn(Optional.empty())
        val tooFew = CreatePostingRequest(
            description = "test",
            posted_at = Instant.now(),
            source_event_id = UUID.randomUUID(),
            source_event_name = "payment.captured.v1",
            entries = listOf(
                PostingEntryDto("1100_cash_eur", "debit", 1000, "EUR"),
            ),
        )
        val exception = assertThrows(ApiException::class.java) {
            service.createPosting(tooFew, "test-key-7", UUID.randomUUID())
        }
        assertEquals("UNBALANCED_POSTING", exception.code)
    }
}
