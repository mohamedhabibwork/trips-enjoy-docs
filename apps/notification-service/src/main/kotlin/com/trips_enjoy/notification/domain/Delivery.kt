package com.trips_enjoy.notification.domain

import com.trips_enjoy.notification.domain.enums.Channel
import com.trips_enjoy.notification.domain.enums.DeliveryStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.Objects
import java.util.UUID

/**
 * Append-mostly delivery row. Composite PK `(id, created_at)` is required
 * because the table is RANGE-partitioned on `created_at`
 * (DATABASE_ARCHITECTURE.md §6 canonical template).
 *
 *  - `template_version_snapshot_id` is the immutable binding to
 *    `template_history.id`. NULL on legacy pre-v1.1 rows; NOT NULL on every
 *    new delivery (SRS.md §FR--046).
 *  - `correlation_id` is the gateway-issued X-Request-Id from ADR-0019.
 *    Stable across retries and downstream calls.
 *  - `dedup_key` enables Redis-side SETNX dedup window (TECH.md).
 *  - `request_idempotency_key` ties this delivery back to a
 *    `notification.idempotency_records` row when the row was created via
 *    `POST /v1/notifications`.
 *  - PII columns (`rendered_subject_encrypted`, `rendered_body_encrypted`)
 *    store pgcrypto ciphertext; right-to-erasure NULLs them.
 *  - Optimistic locking via `@Version version: Long` (SRS.md §FR--046).
 */
@Entity
@Table(name = "deliveries", schema = "notification")
@IdClass(Delivery.Pk::class)
class Delivery(
	@Id
	val id: UUID,

	@Id
	@Column(name = "created_at", nullable = false)
	val createdAt: Instant = Instant.now(),

	@Column(name = "user_id", nullable = false)
	var userId: UUID,

	@Column(name = "template_id", nullable = false)
	val templateId: UUID,

	@Column(name = "template_version_snapshot_id")
	var templateVersionSnapshotId: UUID? = null,

	@Column(name = "rendered_template_version")
	var renderedTemplateVersion: Int? = null,

	@Column(name = "rendered_template_type")
	var renderedTemplateType: String? = null,

	@Column(name = "rendered_provider_template_id")
	var renderedProviderTemplateId: String? = null,

	@Column(name = "rendered_provider_template_language")
	var renderedProviderTemplateLanguage: String? = null,

	@Column(name = "template_name", nullable = false)
	val templateName: String,

	@Column(nullable = false)
	val category: String,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	var channel: Channel,

	@Column(nullable = false)
	val locale: String,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	var status: DeliveryStatus = DeliveryStatus.QUEUED,

	@Column(nullable = false)
	var attempt: Int = 0,

	@Column(name = "rendered_subject_encrypted")
	var renderedSubjectEncrypted: ByteArray? = null,

	@Column(name = "rendered_body_encrypted")
	var renderedBodyEncrypted: ByteArray? = null,

	@Column(name = "dedup_key", nullable = false)
	val dedupKey: String,

	@Column(name = "request_idempotency_key")
	var requestIdempotencyKey: UUID? = null,

	@Column(name = "correlation_id", nullable = false)
	val correlationId: UUID,

	@Column(name = "gateway_request_id")
	var gatewayRequestId: String? = null,

	@Column(name = "gateway_response_status")
	var gatewayResponseStatus: Int? = null,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "gateway_response_body", columnDefinition = "jsonb")
	var gatewayResponseBody: String? = null,

	@Column(name = "failure_reason")
	var failureReason: String? = null,

	@Column(name = "request_id")
	val requestId: UUID? = null,

	@Column
	val service: String? = null,

	@Column(name = "payment_id")
	val paymentId: UUID? = null,

	@Column(name = "sent_at")
	var sentAt: Instant? = null,

	@Column(name = "delivered_at")
	var deliveredAt: Instant? = null,

	@Column(name = "read_at")
	var readAt: Instant? = null,

	@Column(name = "failed_at")
	var failedAt: Instant? = null,

	@Column(name = "suppressed_at")
	var suppressedAt: Instant? = null,

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.now(),

	@Version
	@Column(nullable = false)
	var version: Long = 0,
) {
	data class Pk(val id: UUID, val createdAt: Instant) : Serializable {
		companion object {
			private const val serialVersionUID: Long = 1L
		}
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is Delivery) return false
		return id == other.id && createdAt == other.createdAt
	}

	override fun hashCode(): Int = Objects.hash(id, createdAt)
}