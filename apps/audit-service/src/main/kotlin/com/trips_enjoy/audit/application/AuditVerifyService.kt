package com.trips_enjoy.audit.application

import com.trips_enjoy.audit.api.ApiException
import com.trips_enjoy.audit.api.VerifyResponse
import com.trips_enjoy.audit.domain.AuditEvent
import com.trips_enjoy.audit.domain.AuditEventRepository
import com.trips_enjoy.audit.util.HashChain
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Implements INTEGRATION §1.3 / WORKFLOWS §3 — recomputes the hash chain from
 * genesis up to (and including) the target event and compares the computed
 * hash against the stored one. Returns the chain length on success, or
 * `HASH_MISMATCH` (422) on tamper.
 */
@Service
class AuditVerifyService(
    private val events: AuditEventRepository,
    private val metrics: IngestionMetrics,
    @Value("\${audit-service.hash.algo:sha256}") private val hashAlgo: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun verify(targetId: UUID): VerifyResponse {
        val target = events.findByEventId(targetId)
            .orElseThrow { ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event $targetId not found") }
        val result = verifyInternal(target)
        metrics.setChainStatus(result.verified)
        return result
    }

    /**
     * Daily-job friendly entrypoint — verify the full chain end-to-end. Used
     * by `AuditHashChainJob` to emit `audit.hash_chain.verified.v1`.
     */
    @Transactional(readOnly = true)
    fun verifyChainLength(): Long = events.findFirstByOrderByCreatedAtDescIdDesc()?.let {
        val result = verifyInternal(it)
        metrics.setChainStatus(result.verified)
        result.chain_length
    } ?: 0L

    private fun verifyInternal(target: AuditEvent): VerifyResponse {
        val rows = events.findUpToIncluding(target.id, target.createdAt)
        if (rows.isEmpty()) {
            return VerifyResponse(
                verified = false,
                verified_at = Instant.now(),
                chain_length = 0,
                target_id = target.id,
                target_hash = target.hash,
                recomputed_hash = null,
                mismatch_id = target.id,
            )
        }
        var prevHash: String? = null
        var computedHash: String? = null
        var mismatchId: UUID? = null
        rows.forEachIndexed { _, row ->
            val dataJson = row.data
            val canonical = HashChain.canonicalize(
                eventId = row.eventId.toString(),
                eventName = row.eventName,
                schemaVersion = row.schemaVersion,
                occurredAtIso = row.occurredAt.toString(),
                producer = row.producer,
                tenantId = row.tenantId,
                correlationId = row.correlationId.toString(),
                aggregateType = row.aggregateType,
                aggregateId = row.aggregateId?.toString(),
                subjectType = row.subjectType,
                subjectId = row.subjectId?.toString(),
                dataJson = dataJson,
            )
            val expected = HashChain.nextHash(prevHash, canonical, hashAlgo)
            if (expected != row.hash || row.prevHash != prevHash) {
                mismatchId = row.id
                computedHash = expected
                log.error(
                    "Hash chain mismatch at id={} expected={} stored={} prev_expected={} prev_stored={}",
                    row.id,
                    expected,
                    row.hash,
                    prevHash,
                    row.prevHash,
                )
                return@forEachIndexed
            }
            prevHash = expected
            computedHash = expected
        }
        val verified = mismatchId == null
        return VerifyResponse(
            verified = verified,
            verified_at = Instant.now(),
            chain_length = rows.size.toLong(),
            target_id = target.id,
            target_hash = target.hash,
            recomputed_hash = computedHash,
            mismatch_id = mismatchId,
        )
    }
}
