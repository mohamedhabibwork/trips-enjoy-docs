package com.trips_enjoy.notification.domain

import com.trips_enjoy.notification.domain.enums.Channel
import com.trips_enjoy.notification.domain.enums.TemplateProviderStatus
import com.trips_enjoy.notification.domain.enums.TemplateType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Immutable, append-only audit snapshot of a Template at a point in time.
 *
 * Per docs/services/notification-service/TEMPLATE_HISTORY.md:
 *  - INSERT-only; UPDATE/DELETE blocked by `template_history_immutable` trigger
 *    (defined in V2).
 *  - One row per `templates.version`; UNIQUE(template_id, revision_no) and
 *    UNIQUE(template_id, version).
 *  - `diff_summary` JSONB captures what changed: added_variables[],
 *    removed_variables[], body_changed, structure_changed, subject_changed,
 *    approver_sub, approved_at, note.
 *  - Every `deliveries` row binds via `template_version_snapshot_id`
 *    → this.id (no DB FK because deliveries is range-partitioned).
 *  - Right-to-erasure does NOT touch this table (no PII — only admin sub UUIDs).
 */
@Entity
@Table(name = "template_history", schema = "notification")
class TemplateHistory(
	@Id
	val id: UUID,

	@Column(name = "template_id", nullable = false)
	val templateId: UUID,

	@Column(name = "revision_no", nullable = false)
	val revisionNo: Int,

	@Column(nullable = false)
	val version: Int,

	@Column(nullable = false)
	val name: String,

	@Column(nullable = false)
	val category: String,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	val channel: Channel,

	@Column(nullable = false)
	val locale: String,

	@Column(name = "subject")
	var subject: String? = null,

	@Column
	var body: String? = null,

	@Enumerated(EnumType.STRING)
	@Column(name = "template_type", nullable = false)
	var templateType: TemplateType,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "body_structured", columnDefinition = "jsonb")
	var bodyStructured: String? = null,

	@Column(name = "provider_template_id")
	var providerTemplateId: String? = null,

	@Column(name = "provider_template_language")
	var providerTemplateLanguage: String? = null,

	@Enumerated(EnumType.STRING)
	@Column(name = "provider_template_status", nullable = false)
	var providerTemplateStatus: TemplateProviderStatus,

	@Column(name = "provider_template_approved_at")
	var providerTemplateApprovedAt: Instant? = null,

	@Column(name = "required_variables", nullable = false)
	var requiredVariables: List<String> = emptyList(),

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	var metadata: String = "{}",

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "diff_summary", nullable = false, columnDefinition = "jsonb")
	var diffSummary: String,

	@Column(name = "published_by", nullable = false)
	var publishedBy: UUID,

	@Column(name = "approved_by")
	var approvedBy: UUID? = null,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant = Instant.now(),
)