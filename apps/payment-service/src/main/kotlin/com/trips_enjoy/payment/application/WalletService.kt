package com.trips_enjoy.payment.application

import com.trips_enjoy.payment.domain.IdempotencyKey
import com.trips_enjoy.payment.domain.OutboxEvent
import com.trips_enjoy.payment.domain.OutboxEventRepository
import com.trips_enjoy.payment.domain.Wallet
import com.trips_enjoy.payment.domain.WalletEntry
import com.trips_enjoy.payment.domain.WalletEntryRepository
import com.trips_enjoy.payment.domain.WalletRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The wallet service — manages the customer (and driver/courier/merchant)
 * wallet aggregate. Each operation writes a paired `WalletEntry` row
 * (the double-entry ledger line) and updates the wallet's denormalised
 * `balance_minor` counter.
 *
 * Idempotency: every wallet operation is keyed on `(event_id, source)`,
 * the canonical unique index on `payment.wallet_entries`. Replays of the
 * same event (e.g. a Kafka redelivery) return the existing entry without
 * mutating the balance.
 */
@Service
class WalletService(
    private val walletRepository: WalletRepository,
    private val entryRepository: WalletEntryRepository,
    private val outboxRepository: OutboxEventRepository,
    private val idemService: IdempotencyService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Find or create a wallet for the given (customer, kind, currency)
     * tuple. Idempotent on the customer+kind+currency triple via the
     * unique index.
     */
    @Transactional
    fun getOrCreate(
        customerId: UUID,
        walletKind: String = Wallet.KIND_CUSTOMER,
        currency: String,
        createdBy: UUID,
    ): Wallet {
        return walletRepository.findByCustomerIdAndWalletKindAndCurrencyAndDeletedAtIsNull(
            customerId, walletKind, currency
        ) ?: run {
            val wallet = Wallet(
                id = UUID.randomUUID(),
                customerId = customerId,
                walletKind = walletKind,
                currency = currency,
                createdBy = createdBy,
                updatedBy = createdBy,
            )
            walletRepository.save(wallet)
        }
    }

    /**
     * Credit the wallet — adds `amountMinor` to the balance, writes
     * a `WalletEntry` row with `direction=credit`. Idempotent on
     * `(eventId, source)`.
     */
    @Transactional
    fun credit(
        walletId: UUID,
        amountMinor: Long,
        eventId: UUID,
        source: String,
        sourceId: UUID?,
        description: String?,
        correlationId: UUID,
        createdBy: UUID,
        at: Instant = Instant.now(),
    ): WalletEntry {
        val existing = entryRepository.findByEventIdAndSource(eventId, source)
        if (existing != null) return existing

        val wallet = walletRepository.findByIdForUpdate(walletId)
            ?: error("wallet $walletId not found")

        wallet.credit(amountMinor, UUID.randomUUID(), at)
        val entry = WalletEntry(
            id = UUID.randomUUID(),
            walletId = wallet.id,
            eventId = eventId,
            direction = WalletEntry.DIRECTION_CREDIT,
            amountMinor = amountMinor,
            balanceAfterMinor = wallet.balanceMinor,
            currency = wallet.currency,
            source = source,
            sourceId = sourceId,
            description = description,
            correlationId = correlationId,
            postedAt = at,
            createdBy = createdBy,
        )
        entryRepository.save(entry)
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Wallet",
                aggregateId = wallet.id,
                eventType = "wallet.credited.v1",
                topic = "wallet.credited.v1",
                payload = mapOf(
                    "wallet_id" to wallet.id.toString(),
                    "amount_minor" to amountMinor,
                    "balance_after_minor" to wallet.balanceMinor,
                    "currency" to wallet.currency,
                    "source" to source,
                    "correlation_id" to correlationId.toString(),
                ),
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
        return entry
    }

    /**
     * Debit the wallet — subtracts `amountMinor` from the balance, writes
     * a `WalletEntry` row with `direction=debit`. Throws if the balance
     * would go below 0 (the Wallet.debit invariant).
     */
    @Transactional
    fun debit(
        walletId: UUID,
        amountMinor: Long,
        eventId: UUID,
        source: String,
        sourceId: UUID?,
        description: String?,
        correlationId: UUID,
        createdBy: UUID,
        at: Instant = Instant.now(),
    ): WalletEntry {
        val existing = entryRepository.findByEventIdAndSource(eventId, source)
        if (existing != null) return existing

        val wallet = walletRepository.findByIdForUpdate(walletId)
            ?: error("wallet $walletId not found")
        val newBalance = wallet.debit(amountMinor, UUID.randomUUID(), at)
        val entry = WalletEntry(
            id = UUID.randomUUID(),
            walletId = wallet.id,
            eventId = eventId,
            direction = WalletEntry.DIRECTION_DEBIT,
            amountMinor = amountMinor,
            balanceAfterMinor = newBalance,
            currency = wallet.currency,
            source = source,
            sourceId = sourceId,
            description = description,
            correlationId = correlationId,
            postedAt = at,
            createdBy = createdBy,
        )
        entryRepository.save(entry)
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Wallet",
                aggregateId = wallet.id,
                eventType = "wallet.debited.v1",
                topic = "wallet.debited.v1",
                payload = mapOf(
                    "wallet_id" to wallet.id.toString(),
                    "amount_minor" to amountMinor,
                    "balance_after_minor" to newBalance,
                    "currency" to wallet.currency,
                    "source" to source,
                    "correlation_id" to correlationId.toString(),
                ),
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
        return entry
    }

    /**
     * Freeze a wallet — prevents further credit/debit until unfrozen.
     */
    @Transactional
    fun freeze(walletId: UUID, createdBy: UUID, at: Instant = Instant.now()) {
        val wallet = walletRepository.findByIdForUpdate(walletId)
            ?: error("wallet $walletId not found")
        wallet.freeze(at)
    }

    /**
     * Unfreeze a wallet.
     */
    @Transactional
    fun unfreeze(walletId: UUID, createdBy: UUID, at: Instant = Instant.now()) {
        val wallet = walletRepository.findByIdForUpdate(walletId)
            ?: error("wallet $walletId not found")
        wallet.unfreeze(at)
    }

    @Transactional(readOnly = true)
    fun balance(walletId: UUID): Long? =
        walletRepository.findById(walletId).orElse(null)?.balanceMinor

    @Transactional(readOnly = true)
    fun entries(walletId: UUID): List<WalletEntry> =
        entryRepository.findByWalletIdOrderByPostedAtDesc(walletId)
}