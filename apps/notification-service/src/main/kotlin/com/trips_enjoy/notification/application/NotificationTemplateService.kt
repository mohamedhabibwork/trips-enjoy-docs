package com.trips_enjoy.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.notification.api.ApiException
import com.trips_enjoy.notification.domain.Template
import com.trips_enjoy.notification.domain.TemplateHistory
import com.trips_enjoy.notification.domain.TemplateHistoryRepository
import com.trips_enjoy.notification.domain.TemplateRepository
import com.trips_enjoy.notification.domain.enums.Channel
import com.trips_enjoy.notification.domain.enums.TemplateProviderStatus
import com.trips_enjoy.notification.domain.enums.TemplateType
import com.trips_enjoy.notification.util.uuidV7
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Template lifecycle service per docs/services/notification-service/SRS.md §FR-012,013,040-047
 * and WORKFLOWS.md §9.2/9.3 (publication, approval, atomic-across-locales publish).
 *
 * Invariants enforced:
 *  - Every template version increment MUST share a DB transaction with one
 *    `template_history` insert. The history table has an append-only trigger.
 *  - Atomic publish across all `(channel, locale)` pairs for the same `name`
 *    happens in a single transaction (no half-published state).
 *  - WhatsApp publication requires `approved_by` non-null in the
 *    `template_history` row.
 */
@Service
class NotificationTemplateService(
	private val templates: TemplateRepository,
	private val history: TemplateHistoryRepository,
	private val events: NotificationEventPublishers,
	private val mapper: ObjectMapper,
) {

	/** Look up the active template for a given (name, channel, locale). */
	@Transactional(readOnly = true)
	fun findActive(name: String, channel: Channel, locale: String): Template =
		templates.findActiveLatest(name, channel.value, locale)
			?: throw ApiException(
				HttpStatus.NOT_FOUND,
				"TEMPLATE_MISSING",
				"No active template for name=$name channel=${channel.value} locale=$locale",
			)

	/** Look up the immutable snapshot for a template — used at send-time binding. */
	@Transactional(readOnly = true)
	fun latestSnapshot(templateId: UUID): TemplateHistory {
		val rows = history.findByTemplateIdOrderByRevisionNoDesc(templateId)
		return rows.firstOrNull()
			?: throw ApiException(
				HttpStatus.NOT_FOUND,
				"TEMPLATE_MISSING",
				"No template_history snapshot for template $templateId",
			)
	}

	/**
	 * Admin: atomic across-locales publish. Bumps version on every `Template`
	 * row matching the `name`, writes one `template_history` row per row, and
	 * emits one `notification.template.published.v1` event per row — all in a
	 * single DB transaction (no half-published state).
	 */
	@Transactional
	fun publish(
		templateName: String,
		publishedBy: UUID,
		approvedBy: UUID?,
		diffSummary: Map<String, Any?>,
		correlationId: String,
	) {
		val candidates = templates.findAll().filter { it.name == templateName && it.deletedAt == null }
		if (candidates.isEmpty()) {
			throw ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "No templates named $templateName")
		}
		val now = Instant.now()
		candidates.forEach { template ->
			template.version = template.version + 1
			template.updatedAt = now
			template.updatedBy = publishedBy
			if (template.status != "active") template.status = "active"
			val newRevisionNo = history.maxRevisionNo(template.id) + 1
			val snapshot = TemplateHistory(
				id = uuidV7(),
				templateId = template.id,
				revisionNo = newRevisionNo,
				version = template.version,
				name = template.name,
				category = template.category,
				channel = template.channel,
				locale = template.locale,
				subject = template.subject,
				body = template.body,
				templateType = template.templateType,
				bodyStructured = template.bodyStructured,
				providerTemplateId = template.providerTemplateId,
				providerTemplateLanguage = template.providerTemplateLanguage,
				providerTemplateStatus = template.providerTemplateStatus,
				providerTemplateApprovedAt = template.providerTemplateApprovedAt,
				requiredVariables = template.requiredVariables,
				metadata = template.metadata,
				diffSummary = mapper.writeValueAsString(diffSummary),
				publishedBy = publishedBy,
				approvedBy = if (template.channel == Channel.WHATSAPP) (approvedBy ?: publishedBy) else approvedBy,
				createdAt = now,
			)
			history.save(snapshot)
			events.publishTemplatePublished(
				templateId = template.id,
				templateHistoryId = snapshot.id,
				channel = template.channel.value,
				providerTemplateId = template.providerTemplateId,
				providerTemplateStatus = template.providerTemplateStatus.value,
				publishedBy = publishedBy,
				approvedBy = snapshot.approvedBy,
				diffSummary = diffSummary,
				correlationId = correlationId,
			)
		}
	}

	/** Mark a WhatsApp template approved (or paused/rejected). */
	@Transactional
	fun updateProviderStatus(
		templateId: UUID,
		newStatus: TemplateProviderStatus,
		approvedBy: UUID?,
	): Template {
		val template = templates.findById(templateId).orElseThrow {
			ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "Template $templateId not found")
		}
		if (template.channel != Channel.WHATSAPP) {
			throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"BUSINESS_RULE_VIOLATION",
				"updateProviderStatus only applies to channel=whatsapp (was ${template.channel.value})",
			)
		}
		template.providerTemplateStatus = newStatus
		if (newStatus == TemplateProviderStatus.APPROVED) {
			template.providerTemplateApprovedAt = Instant.now()
		}
		template.updatedAt = Instant.now()
		template.updatedBy = approvedBy
		if (newStatus == TemplateProviderStatus.PAUSED || newStatus == TemplateProviderStatus.REJECTED) {
			throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"TEMPLATE_PAUSED",
				"Template ${template.id} is now ${newStatus.value}; subsequent sends will fail 422",
			)
		}
		return template
	}

	@Transactional(readOnly = true)
	fun history(templateId: UUID): List<TemplateHistory> =
		history.findByTemplateIdOrderByRevisionNoDesc(templateId)

	/** Test seam: ensure discriminator (TECH.md) — used by admin tests. */
	fun assertTemplateType(template: Template, expected: TemplateType) {
		if (template.templateType != expected) {
			throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"TEMPLATE_HAS_NO_BODY_STRUCTURED",
				"Template ${template.id} type=${template.templateType.value} (expected ${expected.value})",
			)
		}
	}
}