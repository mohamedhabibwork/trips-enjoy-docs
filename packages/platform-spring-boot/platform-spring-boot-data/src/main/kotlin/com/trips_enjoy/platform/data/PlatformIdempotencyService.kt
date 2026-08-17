package com.trips_enjoy.platform.data

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Configuration for the canonical idempotency service.
 *
 * Properties (application.yml `platform.idempotency.*`):
 * - [ttlSeconds]        - how long a claim is valid before cleanup
 *                         (default 86400 = 24h per ADR-0027)
 */
@ConfigurationProperties("platform.idempotency")
data class PlatformIdempotencyProperties(
    val ttlSeconds: Long = 86_400L,
)

/**
 * Spring Data repository for [IdempotencyRecordCanonical].
 *
 * The unique key `(actor_id, idempotency_key)` is the platform contract
 * from ADR-0027. [findByActorAndKey] is the lookup used by [tryClaim]
 * to detect replays before inserting a new row.
 */
interface IdempotencyRepositoryCanonical : JpaRepository<IdempotencyRecordCanonical, UUID> {
    fun findByActorIdAndIdempotencyKey(actorId: UUID, idempotencyKey: UUID): IdempotencyRecordCanonical?
}

/**
 * Result of [PlatformIdempotencyService.tryClaim].
 *
 * - [Claimed]              - new claim inserted; caller proceeds.
 * - [Replay]               - existing completed claim; caller returns the
 *                            cached response.
 * - [KeyReused]            - existing pending claim with a different
 *                            request hash (a 422 IDEMPOTENCY_KEY_REUSED
 *                            violation per ADR-0027).
 * - [InFlight]             - existing pending claim with the same hash;
 *                            caller should return 409 IDEMPOTENCY_IN_FLIGHT.
 */
sealed interface IdempotencyClaimResult {
    data class Claimed(val claimId: UUID, val record: IdempotencyRecordCanonical) : IdempotencyClaimResult
    data class Replay(val record: IdempotencyRecordCanonical) : IdempotencyClaimResult
    data class KeyReused(val existingHash: String) : IdempotencyClaimResult
    data class InFlight(val record: IdempotencyRecordCanonical) : IdempotencyClaimResult
}

/**
 * Canonical idempotency service per ADR-0027.
 *
 * API:
 * - [tryClaim] - look up or insert a claim for `(actorId, key)`. The
 *   caller invokes this once per mutating request.
 * - [complete] - record the response on a successful claim.
 * - [release]  - mark a claim as RELEASED (handler crashed, do not
 *                satisfy future replays).
 *
 * Per ADR-0027, the request hash must be SHA-256 (64 hex chars).
 */
@Service
open class PlatformIdempotencyService(
    private val repository: IdempotencyRepositoryCanonical,
    private val properties: PlatformIdempotencyProperties,
) {
    /**
     * Attempt to claim the `(actorId, idempotencyKey)` namespace.
     *
     * - If no row exists: insert a PENDING claim and return [IdempotencyClaimResult.Claimed].
     * - If a COMPLETED row exists with the same hash: return [IdempotencyClaimResult.Replay]
     *   (the caller returns the cached response).
     * - If a PENDING row exists with the same hash: return [IdempotencyClaimResult.InFlight]
     *   (caller returns 409).
     * - If a row exists with a different hash: return [IdempotencyClaimResult.KeyReused]
     *   (caller returns 422).
     */
    @Transactional
    open fun tryClaim(
        actorId: UUID,
        idempotencyKey: UUID,
        requestHash: String,
    ): IdempotencyClaimResult {
        require(requestHash.length == 64) { "request_hash must be a SHA-256 hex (64 chars)" }

        val existing = repository.findByActorIdAndIdempotencyKey(actorId, idempotencyKey)
        if (existing != null) {
            // Hash mismatch is a hard violation per ADR-0027.
            if (existing.requestHash != requestHash) {
                return IdempotencyClaimResult.KeyReused(existing.requestHash)
            }
            return when (existing.state) {
                IdempotencyRecordCanonical.State.COMPLETED.name ->
                    IdempotencyClaimResult.Replay(existing)
                IdempotencyRecordCanonical.State.PENDING.name ->
                    IdempotencyClaimResult.InFlight(existing)
                else ->
                    // RELEASED — treat as if claim is gone; caller may proceed
                    // (and we re-insert a new PENDING claim below).
                    IdempotencyClaimResult.InFlight(existing)
            }
        }

        val newClaim = IdempotencyRecordCanonical(
            id = UUID.randomUUID(),
            actorId = actorId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            state = IdempotencyRecordCanonical.State.PENDING.name,
            expiresAt = Instant.now().plus(Duration.ofSeconds(properties.ttlSeconds)),
        )
        return try {
            val saved = repository.save(newClaim)
            IdempotencyClaimResult.Claimed(saved.id, saved)
        } catch (e: DataIntegrityViolationException) {
            // Concurrent insert won the race; re-read.
            val raceWinner = repository.findByActorIdAndIdempotencyKey(actorId, idempotencyKey)
                ?: throw IllegalStateException("idempotency race lost and row missing", e)
            if (raceWinner.requestHash != requestHash) {
                IdempotencyClaimResult.KeyReused(raceWinner.requestHash)
            } else {
                IdempotencyClaimResult.InFlight(raceWinner)
            }
        }
    }

    /** Record the response on a successful claim. */
    @Transactional
    open fun complete(claimId: UUID, status: Int, body: String?) {
        val record = repository.findById(claimId).orElseThrow {
            NoSuchElementException("idempotency claim $claimId not found")
        }
        record.complete(status, body, Instant.now())
        repository.save(record)
    }

    /** Mark a claim as RELEASED (handler abandoned it). */
    @Transactional
    open fun release(claimId: UUID) {
        val record = repository.findById(claimId).orElseThrow {
            NoSuchElementException("idempotency claim $claimId not found")
        }
        record.release()
        repository.save(record)
    }
}