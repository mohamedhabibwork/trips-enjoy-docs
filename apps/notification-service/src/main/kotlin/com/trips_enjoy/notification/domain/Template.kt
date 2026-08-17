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
 * Logical (mutable) notification template per docs/services/notification-service/ERD.md.
 *
 * Discriminator: `template_type = plain` → `body` non-null, `body_structured` null;
 *                `template_type = whatsapp_structured` → opposite. Enforced by the
 *                `templates_body_discriminator_chk` constraint in V2.
 *
 * Every state transition (publish / approve / pause / retire) MUST be paired with
 * an append-only `template_history` insert in the same DB transaction
 * (TEMPLATE_HISTORY.md). The application service layer is responsible for this
 * invariant; the trigger blocks UPDATE/DELETE on the history table.
 */
@Entity
@Table(name = "templates", schema = "notification")
class Template(
	@Id
	val id: UUID,

	@Column(nullable = false)
	val name: String,

	@Column(nullable = false)
	val category: String,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	val channel: Channel,

	@Column(nullable = false)
	val locale: String,

	@Column
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
	var providerTemplateStatus: TemplateProviderStatus = TemplateProviderStatus.DRAFT,

	@Column(name = "provider_template_approved_at")
	var providerTemplateApprovedAt: Instant? = null,

	@Column(name = "provider_template_reject_reason")
	var providerTemplateRejectReason: String? = null,

	@Column(name = "required_variables", nullable = false)
	var requiredVariables: List<String> = emptyList(),

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	var metadata: String = "{}",

	@Column(nullable = false)
	var status: String = "active",

	@Column(nullable = false)
	var version: Int = 1,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant = Instant.now(),

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.now(),

	@Column(name = "created_by")
	val createdBy: UUID? = null,

	@Column(name = "updated_by")
	var updatedBy: UUID? = null,

	@Column(name = "deleted_at")
	var deletedAt: Instant? = null,
)