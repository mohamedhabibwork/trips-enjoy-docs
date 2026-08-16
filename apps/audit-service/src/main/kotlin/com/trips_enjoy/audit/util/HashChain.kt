package com.trips_enjoy.audit.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Cryptographic hash chain for the audit log per SRS §5 FR--003 and
 * ERD §3 column `events.hash`. The chain is sha256(prev_hash || canonical_event)
 * where `canonical_event` is a stable, deterministic JSON serialization.
 *
 * The genesis row uses the well-known genesis hash so verifiers can start the
 * recompute without a separate "first row" sentinel.
 */
object HashChain {
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    private const val SHA256 = "SHA-256"

    /**
     * Compute the next hash given the previous row's hash (or genesis) and
     * the canonical event payload bytes.
     */
    fun nextHash(prevHash: String?, canonical: String, algo: String = SHA256): String {
        val prev = prevHash ?: GENESIS_HASH
        val md = MessageDigest.getInstance(algo)
        md.update(prev.toByteArray(StandardCharsets.UTF_8))
        md.update("|".toByteArray(StandardCharsets.UTF_8))
        md.update(canonical.toByteArray(StandardCharsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the canonical JSON payload used both at ingest (to compute the
     * row's hash) and at verify time (to recompute and compare). Field order
     * is fixed: keys MUST NOT be re-ordered between calls.
     */
    fun canonicalize(
        eventId: String,
        eventName: String,
        schemaVersion: Int,
        occurredAtIso: String,
        producer: String,
        tenantId: String,
        correlationId: String,
        aggregateType: String,
        aggregateId: String?,
        subjectType: String?,
        subjectId: String?,
        dataJson: String,
    ): String = buildString {
        append('{')
        appendField(this, "event_id", eventId); append(',')
        appendField(this, "event_name", eventName); append(',')
        appendField(this, "schema_version", schemaVersion.toString()); append(',')
        appendField(this, "occurred_at", occurredAtIso); append(',')
        appendField(this, "producer", producer); append(',')
        appendField(this, "tenant_id", tenantId); append(',')
        appendField(this, "correlation_id", correlationId); append(',')
        appendField(this, "aggregate_type", aggregateType); append(',')
        appendField(this, "aggregate_id", aggregateId, nullable = true); append(',')
        appendField(this, "subject_type", subjectType, nullable = true); append(',')
        appendField(this, "subject_id", subjectId, nullable = true); append(',')
        appendField(this, "data", dataJson, asJson = true)
        append('}')
    }

    private fun appendField(buf: StringBuilder, key: String, value: String?, nullable: Boolean = false, asJson: Boolean = false) {
        buf.append('"').append(key).append('"').append(':')
        if (nullable && value == null) {
            buf.append("null")
        } else if (asJson) {
            buf.append(value)
        } else {
            buf.append('"').append(escape(value!!)).append('"')
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
