package com.trips_enjoy.payment.application

import com.trips_enjoy.payment.domain.IdempotencyKey
import com.trips_enjoy.payment.domain.IdempotencyKeyRepository
import com.trips_enjoy.payment.domain.OutboxEvent
import com.trips_enjoy.payment.domain.OutboxEventRepository
import com.trips_enjoy.payment.domain.PaymentAttempt
import com.trips_enjoy.payment.domain.PaymentAttemptRepository
import com.trips_enjoy.payment.domain.PaymentIntent
import com.trips_enjoy.payment.domain.PaymentIntentRepository
import com.trips_enjoy.payment.gateway.AuthorizeRequest
import com.trips_enjoy.payment.gateway.CaptureRequest
import com.trips_enjoy.payment.gateway.GatewayOperationException
import com.trips_enjoy.payment.gateway.GatewayRegistry
import com.trips_enjoy.payment.gateway.RefundRequest
import com.trips_enjoy.payment.gateway.VoidRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The payment-intent saga orchestrator. Drives the state machine
 * `created → authorized → captured / voided` and emits the matching
 * `payment.*` events via the transactional outbox. Mirrors the canonical
 * service pattern from notification-service / customer-service / audit-service.
 *
 * Public API methods are idempotent on the Idempotency-Key header.
 * The IdempotencyService writes a row in the same transaction as the
 * aggregate mutation; replays return the cached response.
 */
@Service
class PaymentIntentService(
    private val intentRepository: PaymentIntentRepository,
    private val attemptRepository: PaymentAttemptRepository,
    private val idempotencyRepository: IdempotencyKeyRepository,
    private val outboxRepository: OutboxEventRepository,
    private val registry: GatewayRegistry,
    private val idemService: IdempotencyService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Create a payment intent. The gateway is resolved by the
     * GatewayRegistry per GATEWAYS.md §6 precedence. The created intent
     * starts in state `created`; a subsequent call to `authorize()`
     * moves it to `authorized`.
     */
    @Transactional
    fun create(
        customerId: UUID,
        requestId: UUID,
        service: String,
        amountMinor: Long,
        currency: String,
        gatewayRegion: String,
        captureMode: String,
        cityId: UUID?,
        description: String?,
        metadata: Map<String, Any?>?,
        correlationId: UUID,
        pin: String? = null,
        tenantId: String? = null,
        method: String = "card",
        createdBy: UUID,
        idempotencyKey: String,
        requestHash: String,
    ): PaymentIntent {
        // Idempotency check
        val existing = idemService.findExisting(
            scope = IdempotencyKey.SCOPE_PAYMENT_INTENT,
            idemKey = idempotencyKey,
        )
        if (existing != null) {
            require(existing.requestHash == requestHash) {
                "idempotency key $idempotencyKey already used with a different request body"
            }
            // Find the intent we previously created
            val prev = intentRepository.findByRequestIdAndService(requestId, service).firstOrNull()
                ?: error("idempotency key recorded but no matching payment intent for request $requestId")
            return prev
        }

        val resolved = registry.resolve(
            pin = pin,
            tenantId = tenantId,
            region = gatewayRegion,
            currency = currency,
            method = method,
            paymentIntentId = UUID.randomUUID(),  // placeholder; replaced after save
            createdBy = createdBy,
        )

        val intent = PaymentIntent(
            id = UUID.randomUUID(),
            customerId = customerId,
            requestId = requestId,
            service = service,
            amountMinor = amountMinor,
            currency = currency,
            gatewayId = resolved.gateway.id,
            gatewayRegion = gatewayRegion,
            captureMode = captureMode,
            cityId = cityId,
            description = description,
            metadata = metadata,
            correlationId = correlationId,
            createdBy = createdBy,
            updatedBy = createdBy,
        )

        intentRepository.save(intent)
        idemService.record(
            scope = IdempotencyKey.SCOPE_PAYMENT_INTENT,
            idemKey = idempotencyKey,
            requestHash = requestHash,
            responseStatus = 201,
            responseBody = mapOf("payment_intent_id" to intent.id.toString()),
            createdBy = createdBy,
        )
        emitCreated(intent, resolved, createdBy)
        return intent
    }

    /**
     * Authorize a payment intent against the gateway. On success, the
     * intent moves to state `authorized`; on failure it moves to
     * `failed` with the gateway error code/message preserved.
     */
    @Transactional
    fun authorize(
        intentId: UUID,
        gatewayToken: String,
        correlationId: UUID,
        idempotencyKey: String,
        requestHash: String,
        createdBy: UUID,
    ): PaymentIntent {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_PAYMENT_INTENT, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key $idempotencyKey body mismatch" }
            return intentRepository.findById(intentId).orElseThrow()
        }

        val intent = intentRepository.findById(intentId).orElseThrow()
        val driver = registry.driverFor(intent.gatewayId)

        val attempt = PaymentAttempt(
            id = UUID.randomUUID(),
            paymentIntentId = intent.id,
            operation = PaymentAttempt.OP_AUTHORIZE,
            gatewayId = intent.gatewayId,
            state = PaymentAttempt.STATE_STARTED,
            amountMinor = intent.amountMinor,
            requestPayload = mapOf("gateway_token_prefix" to gatewayToken.take(8)),
            correlationId = correlationId,
            createdBy = createdBy,
        )
        attemptRepository.save(attempt)

        val authResult = try {
            driver.authorize(
                AuthorizeRequest(
                    paymentIntentId = intent.id,
                    customerId = intent.customerId,
                    amountMinor = intent.amountMinor,
                    currency = intent.currency,
                    gatewayToken = gatewayToken,
                    gatewayRegion = intent.gatewayRegion,
                    captureMode = intent.captureMode,
                    correlationId = correlationId,
                    idempotencyKey = idempotencyKey,
                    metadata = intent.metadata,
                ),
            )
        } catch (e: GatewayOperationException) {
            attempt.markFailed(e.errorCode, e.gatewayMessage, Instant.now())
            intent.fail(e.errorCode, e.gatewayMessage)
            emitFailed(intent, e.errorCode, e.gatewayMessage, createdBy)
            throw e
        }

        attempt.markSucceeded(authResult.rawResponse, authResult.authorizedAt)
        intent.authorize(authResult.authorizedAt, authResult.gatewayIntentId)
        idemService.record(
            IdempotencyKey.SCOPE_PAYMENT_INTENT,
            idempotencyKey,
            requestHash,
            200,
            mapOf("payment_intent_id" to intent.id.toString(), "state" to intent.state),
            createdBy,
        )
        emitAuthorized(intent, createdBy)
        return intent
    }

    /**
     * Capture an authorized intent. Idempotent on the same Idempotency-Key.
     */
    @Transactional
    fun capture(
        intentId: UUID,
        amountMinor: Long?,
        correlationId: UUID,
        idempotencyKey: String,
        requestHash: String,
        createdBy: UUID,
    ): PaymentIntent {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_PAYMENT_CAPTURE, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key $idempotencyKey body mismatch" }
            return intentRepository.findById(intentId).orElseThrow()
        }

        val intent = intentRepository.findById(intentId).orElseThrow()
        val driver = registry.driverFor(intent.gatewayId)
        val captureAmount = amountMinor ?: intent.amountMinor

        val attempt = PaymentAttempt(
            id = UUID.randomUUID(),
            paymentIntentId = intent.id,
            operation = PaymentAttempt.OP_CAPTURE,
            gatewayId = intent.gatewayId,
            state = PaymentAttempt.STATE_STARTED,
            amountMinor = captureAmount,
            correlationId = correlationId,
            createdBy = createdBy,
        )
        attemptRepository.save(attempt)

        val captureResult = try {
            driver.capture(
                CaptureRequest(
                    paymentIntentId = intent.id,
                    gatewayIntentId = intent.gatewayIntentId ?: error("intent has no gateway_intent_id"),
                    amountMinor = amountMinor,
                    currency = intent.currency,
                    correlationId = correlationId,
                    idempotencyKey = idempotencyKey,
                ),
            )
        } catch (e: GatewayOperationException) {
            attempt.markFailed(e.errorCode, e.gatewayMessage, Instant.now())
            intent.fail(e.errorCode, e.gatewayMessage)
            emitFailed(intent, e.errorCode, e.gatewayMessage, createdBy)
            throw e
        }

        attempt.markSucceeded(captureResult.rawResponse, captureResult.capturedAt)
        intent.capture(captureResult.capturedAt, captureResult.capturedMinor)
        idemService.record(
            IdempotencyKey.SCOPE_PAYMENT_CAPTURE,
            idempotencyKey,
            requestHash,
            200,
            mapOf("payment_intent_id" to intent.id.toString(), "state" to intent.state),
            createdBy,
        )
        emitCaptured(intent, createdBy)
        return intent
    }

    @Transactional
    fun void(
        intentId: UUID,
        reason: String?,
        correlationId: UUID,
        idempotencyKey: String,
        requestHash: String,
        createdBy: UUID,
    ): PaymentIntent {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_PAYMENT_VOID, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key $idempotencyKey body mismatch" }
            return intentRepository.findById(intentId).orElseThrow()
        }

        val intent = intentRepository.findById(intentId).orElseThrow()
        val driver = registry.driverFor(intent.gatewayId)

        val attempt = PaymentAttempt(
            id = UUID.randomUUID(),
            paymentIntentId = intent.id,
            operation = PaymentAttempt.OP_VOID,
            gatewayId = intent.gatewayId,
            state = PaymentAttempt.STATE_STARTED,
            correlationId = correlationId,
            createdBy = createdBy,
        )
        attemptRepository.save(attempt)

        val voidResult = try {
            driver.void(
                VoidRequest(
                    paymentIntentId = intent.id,
                    gatewayIntentId = intent.gatewayIntentId ?: error("intent has no gateway_intent_id"),
                    correlationId = correlationId,
                    idempotencyKey = idempotencyKey,
                    reason = reason,
                ),
            )
        } catch (e: GatewayOperationException) {
            attempt.markFailed(e.errorCode, e.gatewayMessage, Instant.now())
            throw e
        }

        attempt.markSucceeded(voidResult.rawResponse, voidResult.voidedAt)
        intent.voidAt(voidResult.voidedAt, reason)
        idemService.record(
            IdempotencyKey.SCOPE_PAYMENT_VOID,
            idempotencyKey,
            requestHash,
            200,
            mapOf("payment_intent_id" to intent.id.toString(), "state" to intent.state),
            createdBy,
        )
        emitVoided(intent, createdBy)
        return intent
    }

    @Transactional
    fun refund(
        intentId: UUID,
        refundAmountMinor: Long,
        reason: String?,
        correlationId: UUID,
        idempotencyKey: String,
        requestHash: String,
        createdBy: UUID,
    ): PaymentIntent {
        val existing = idemService.findExisting(IdempotencyKey.SCOPE_PAYMENT_REFUND, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key $idempotencyKey body mismatch" }
            return intentRepository.findById(intentId).orElseThrow()
        }

        val intent = intentRepository.findById(intentId).orElseThrow()
        val driver = registry.driverFor(intent.gatewayId)

        val attempt = PaymentAttempt(
            id = UUID.randomUUID(),
            paymentIntentId = intent.id,
            operation = PaymentAttempt.OP_REFUND,
            gatewayId = intent.gatewayId,
            state = PaymentAttempt.STATE_STARTED,
            amountMinor = refundAmountMinor,
            correlationId = correlationId,
            createdBy = createdBy,
        )
        attemptRepository.save(attempt)

        val refundResult = try {
            driver.refund(
                RefundRequest(
                    paymentIntentId = intent.id,
                    gatewayIntentId = intent.gatewayIntentId ?: error("intent has no gateway_intent_id"),
                    gatewayCaptureId = "cap_${intent.gatewayIntentId?.take(12)}",
                    refundAmountMinor = refundAmountMinor,
                    currency = intent.currency,
                    reason = reason,
                    correlationId = correlationId,
                    idempotencyKey = idempotencyKey,
                ),
            )
        } catch (e: GatewayOperationException) {
            attempt.markFailed(e.errorCode, e.gatewayMessage, Instant.now())
            throw e
        }

        attempt.markSucceeded(refundResult.rawResponse, refundResult.refundedAt)
        intent.recordRefund(refundAmountMinor)
        idemService.record(
            IdempotencyKey.SCOPE_PAYMENT_REFUND,
            idempotencyKey,
            requestHash,
            200,
            mapOf("payment_intent_id" to intent.id.toString(), "state" to intent.state),
            createdBy,
        )
        emitRefunded(intent, createdBy)
        return intent
    }

    // Event publication via the outbox.
    private fun emitCreated(intent: PaymentIntent, resolved: com.trips_enjoy.payment.gateway.ResolvedGateway, createdBy: UUID) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "PaymentIntent",
                aggregateId = intent.id,
                eventType = "payment.intent.created.v1",
                topic = "payment.intent.created.v1",
                payload = mapOf(
                    "payment_intent_id" to intent.id.toString(),
                    "customer_id" to intent.customerId.toString(),
                    "amount_minor" to intent.amountMinor,
                    "currency" to intent.currency,
                    "gateway_id" to intent.gatewayId,
                    "correlation_id" to intent.correlationId.toString(),
                    "resolved_via" to resolved.source,
                ),
                correlationId = intent.correlationId,
                createdBy = createdBy,
            ),
        )
    }

    private fun emitAuthorized(intent: PaymentIntent, createdBy: UUID) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "PaymentIntent",
                aggregateId = intent.id,
                eventType = "payment.intent.authorized.v1",
                topic = "payment.intent.authorized.v1",
                payload = mapOf(
                    "payment_intent_id" to intent.id.toString(),
                    "gateway_id" to intent.gatewayId,
                    "gateway_intent_id" to intent.gatewayIntentId,
                    "correlation_id" to intent.correlationId.toString(),
                ),
                correlationId = intent.correlationId,
                createdBy = createdBy,
            ),
        )
    }

    private fun emitCaptured(intent: PaymentIntent, createdBy: UUID) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "PaymentIntent",
                aggregateId = intent.id,
                eventType = "payment.intent.captured.v1",
                topic = "payment.intent.captured.v1",
                payload = mapOf(
                    "payment_intent_id" to intent.id.toString(),
                    "amount_minor" to (intent.capturedMinor ?: intent.amountMinor),
                    "currency" to intent.currency,
                    "correlation_id" to intent.correlationId.toString(),
                ),
                correlationId = intent.correlationId,
                createdBy = createdBy,
            ),
        )
    }

    private fun emitVoided(intent: PaymentIntent, createdBy: UUID) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "PaymentIntent",
                aggregateId = intent.id,
                eventType = "payment.intent.voided.v1",
                topic = "payment.intent.voided.v1",
                payload = mapOf(
                    "payment_intent_id" to intent.id.toString(),
                    "correlation_id" to intent.correlationId.toString(),
                ),
                correlationId = intent.correlationId,
                createdBy = createdBy,
            ),
        )
    }

    private fun emitRefunded(intent: PaymentIntent, createdBy: UUID) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "PaymentIntent",
                aggregateId = intent.id,
                eventType = "payment.intent.refunded.v1",
                topic = "payment.intent.refunded.v1",
                payload = mapOf(
                    "payment_intent_id" to intent.id.toString(),
                    "refunded_minor" to intent.refundedMinor,
                    "correlation_id" to intent.correlationId.toString(),
                ),
                correlationId = intent.correlationId,
                createdBy = createdBy,
            ),
        )
    }

    private fun emitFailed(intent: PaymentIntent, errorCode: String, message: String, createdBy: UUID) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "PaymentIntent",
                aggregateId = intent.id,
                eventType = "payment.intent.failed.v1",
                topic = "payment.intent.failed.v1",
                payload = mapOf(
                    "payment_intent_id" to intent.id.toString(),
                    "error_code" to errorCode,
                    "error_message" to message,
                    "correlation_id" to intent.correlationId.toString(),
                ),
                correlationId = intent.correlationId,
                createdBy = createdBy,
            ),
        )
    }
}