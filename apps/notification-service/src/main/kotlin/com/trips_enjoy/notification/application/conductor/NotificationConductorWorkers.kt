package com.trips_enjoy.notification.application.conductor

import com.trips_enjoy.notification.application.NotificationSendService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Conductor worker registrations per
 * docs/services/notification-service/INTEGRATION.md (Conductor Workers §3.x)
 * and docs/shared/CONDUCTOR_WORKFLOWS.md.
 *
 * Workers are colocated in this service's binary (Conductor integration
 * pattern). Each `@ConductorTask`-annotated method handles one task type.
 * The actual `@ConductorTask` annotation and Conductor SDK wiring live in
 * `config/ConductorConfiguration.kt`; this file is the handlers.
 *
 * Per INTEGRATION.md, the 13 tasks are:
 *   - 1 grant-reward template (Phase 7)
 *   - 1 reversal-reward template (Phase 7)
 *   - 6 refund templates (Phase 7.6)
 *   - 2 onboarding approvals (driver + courier)
 *   - 3 deal templates (Phase 7.5)
 */
@Component
class NotificationConductorWorkers(
	private val sendService: NotificationSendService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	// ============================================================
	// Phase 7 — Rewards
	// ============================================================

	@ConductorTask("notification_service_grant_template")
	fun grantTemplate(input: Map<String, Any?>): Map<String, Any?> {
		val userId = UUID.fromString(input["user_id"]?.toString() ?: error("user_id required"))
		val amountMinor = input["amount_minor"]?.toString()?.toLong() ?: 0L
		val currency = input["currency"]?.toString() ?: "EUR"
		val correlationId = UUID.randomUUID() // Conductor supplies workflow id; placeholder for slice
		val taskId = UUID.randomUUID()
		log.info("Conductor grant_template user={} amount={} {} task={}", userId, amountMinor, currency, taskId)
		dispatch(
			userId = userId,
			templateName = "reward.granted",
			data = mapOf("amount_minor" to amountMinor, "currency" to currency),
			dedupKey = "concom:reward:${input["workflow_id"]}:$taskId",
			correlationId = correlationId,
			actorId = userId,
		)
		return mapOf("status" to "ok", "task_id" to taskId.toString(), "occurred_at" to Instant.now().toString())
	}

	@ConductorTask("notification_service_reversal_template")
	fun reversalTemplate(input: Map<String, Any?>): Map<String, Any?> {
		val userId = UUID.fromString(input["user_id"]?.toString() ?: error("user_id required"))
		val amountMinor = input["amount_minor"]?.toString()?.toLong() ?: 0L
		val currency = input["currency"]?.toString() ?: "EUR"
		val taskId = UUID.randomUUID()
		log.info("Conductor reversal_template user={} amount={} {} task={}", userId, amountMinor, currency, taskId)
		dispatch(
			userId = userId,
			templateName = "reward.reversed",
			data = mapOf("amount_minor" to amountMinor, "currency" to currency),
			dedupKey = "concom:reward:${input["workflow_id"]}:$taskId",
			correlationId = UUID.randomUUID(),
			actorId = userId,
		)
		return mapOf("status" to "ok", "task_id" to taskId.toString(), "occurred_at" to Instant.now().toString())
	}

	// ============================================================
	// Phase 7.6 — Refunds (6 variants)
	// ============================================================

	@ConductorTask("notification_service_refund_template_full")
	fun refundTemplateFull(input: Map<String, Any?>): Map<String, Any?> =
		refundHelper(input, templateName = "refund.full")

	@ConductorTask("notification_service_refund_template_partial")
	fun refundTemplatePartial(input: Map<String, Any?>): Map<String, Any?> =
		refundHelper(input, templateName = "refund.partial")

	@ConductorTask("notification_service_refund_template_failed")
	fun refundTemplateFailed(input: Map<String, Any?>): Map<String, Any?> =
		refundHelper(input, templateName = "refund.failed")

	@ConductorTask("notification_service_refund_template_reversed")
	fun refundTemplateReversed(input: Map<String, Any?>): Map<String, Any?> =
		refundHelper(input, templateName = "refund.reversed")

	@ConductorTask("notification_service_refund_template_delayed")
	fun refundTemplateDelayed(input: Map<String, Any?>): Map<String, Any?> =
		refundHelper(input, templateName = "refund.delayed")

	@ConductorTask("notification_service_refund_template_processing")
	fun refundTemplateProcessing(input: Map<String, Any?>): Map<String, Any?> =
		refundHelper(input, templateName = "refund.processing")

	private fun refundHelper(input: Map<String, Any?>, templateName: String): Map<String, Any?> {
		val userId = UUID.fromString(input["user_id"]?.toString() ?: error("user_id required"))
		val taskId = UUID.randomUUID()
		log.info("Conductor refund_template (variant={}) user={} task={}", templateName, userId, taskId)
		dispatch(
			userId = userId,
			templateName = templateName,
			data = mapOf(
				"refund_id" to input["refund_id"]?.toString(),
				"amount_minor" to input["amount_minor"]?.toString()?.toLong(),
				"currency" to input["currency"]?.toString(),
			),
			dedupKey = "concom:reward:${input["workflow_id"]}:$taskId",
			correlationId = UUID.randomUUID(),
			actorId = userId,
		)
		return mapOf("status" to "ok", "task_id" to taskId.toString(), "variant" to templateName, "occurred_at" to Instant.now().toString())
	}

	// ============================================================
	// Onboarding approvals (2)
	// ============================================================

	@ConductorTask("notification_service_approval_driver_template")
	fun approvalDriver(input: Map<String, Any?>): Map<String, Any?> =
		approvalHelper(input, audience = "driver")

	@ConductorTask("notification_service_approval_courier_template")
	fun approvalCourier(input: Map<String, Any?>): Map<String, Any?> =
		approvalHelper(input, audience = "courier")

	private fun approvalHelper(input: Map<String, Any?>, audience: String): Map<String, Any?> {
		val userId = UUID.fromString(input["user_id"]?.toString() ?: error("user_id required"))
		val taskId = UUID.randomUUID()
		log.info("Conductor approval_template audience={} user={} task={}", audience, userId, taskId)
		dispatch(
			userId = userId,
			templateName = "onboarding.$audience.approved",
			data = mapOf("audience" to audience),
			dedupKey = "concom:reward:${input["workflow_id"]}:$taskId",
			correlationId = UUID.randomUUID(),
			actorId = userId,
		)
		return mapOf("status" to "ok", "task_id" to taskId.toString(), "audience" to audience, "occurred_at" to Instant.now().toString())
	}

	// ============================================================
	// Phase 7.5 — Deals (3 flows × 5 templates = event-driven; worker covers 3 timing-critical flows)
	// ============================================================

	@ConductorTask("notification_service_deal_open_template")
	fun dealOpen(input: Map<String, Any?>): Map<String, Any?> = dealHelper(input, "deal.opened")

	@ConductorTask("notification_service_deal_counter_template")
	fun dealCounter(input: Map<String, Any?>): Map<String, Any?> = dealHelper(input, "deal.counter_received")

	@ConductorTask("notification_service_deal_expired_template")
	fun dealExpired(input: Map<String, Any?>): Map<String, Any?> = dealHelper(input, "deal.expired")

	private fun dealHelper(input: Map<String, Any?>, templateName: String): Map<String, Any?> {
		val userId = UUID.fromString(input["user_id"]?.toString() ?: error("user_id required"))
		val taskId = UUID.randomUUID()
		log.info("Conductor deal_template (variant={}) user={} task={}", templateName, userId, taskId)
		dispatch(
			userId = userId,
			templateName = templateName,
			data = mapOf("deal_id" to input["deal_id"]?.toString(), "vertical" to input["vertical"]?.toString()),
			dedupKey = "concom:reward:${input["workflow_id"]}:$taskId",
			correlationId = UUID.randomUUID(),
			actorId = userId,
			priority = if (templateName == "deal.expired") "urgent" else "normal",
		)
		return mapOf("status" to "ok", "task_id" to taskId.toString(), "template" to templateName, "occurred_at" to Instant.now().toString())
	}

	// ============================================================
	// Internal dispatcher — uses the synchronous send orchestrator
	// ============================================================

	private fun dispatch(
		userId: UUID,
		templateName: String,
		data: Map<String, Any?>,
		dedupKey: String,
		correlationId: UUID,
		actorId: UUID,
		priority: String = "normal",
	) {
		sendService.send(
			NotificationSendService.SendRequestInput(
				userId = userId,
				templateName = templateName,
				data = data,
				dedupKey = dedupKey,
				priority = priority,
				actorId = actorId,
				actorIdempotencyKey = null, // workers run with their own idempotency upstream
				correlationId = correlationId,
			),
		)
	}
}

/** Lightweight placeholder annotation — the real one lives in `conductor-spring-boot-starter`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConductorTask(val value: String, val timeoutSeconds: Int = 60)