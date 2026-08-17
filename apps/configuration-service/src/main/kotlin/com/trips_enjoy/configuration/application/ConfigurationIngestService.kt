package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.api.ApiException
import com.trips_enjoy.configuration.domain.ConfigurationAuditLog
import com.trips_enjoy.configuration.domain.ConfigurationAuditLogPk
import com.trips_enjoy.configuration.domain.ConfigurationAuditLogRepository
import com.trips_enjoy.configuration.domain.ConfigurationSchema
import com.trips_enjoy.configuration.domain.ConfigurationSchemaRepository
import com.trips_enjoy.configuration.domain.ConfigurationVersion
import com.trips_enjoy.configuration.domain.ConfigurationVersionPk
import com.trips_enjoy.configuration.domain.ConfigurationVersionRepository
import com.trips_enjoy.configuration.domain.Document
import com.trips_enjoy.configuration.domain.DocumentRepository
import com.trips_enjoy.configuration.domain.OutboxEvent
import com.trips_enjoy.configuration.domain.OutboxRepository
import com.trips_enjoy.configuration.util.uuidV7
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Write path for configuration documents: create key, put new version,
 * rollback, deprecate. All writes are atomic across `documents`,
 * `versions`, `audit_log`, and `outbox` (FR-006 / SRS §14).
 *
 * Concurrency: `SELECT ... FOR UPDATE` on the document row (SRS §14)
 * so two concurrent writes to the same key result in one win and one
 * `409 VERSION_CONFLICT`.
 */
@Service
class ConfigurationIngestService(
    private val documentRepository: DocumentRepository,
    private val versionRepository: ConfigurationVersionRepository,
    private val schemaRepository: ConfigurationSchemaRepository,
    private val auditLogRepository: ConfigurationAuditLogRepository,
    private val outboxRepository: OutboxRepository,
    private val schemaValidationService: SchemaValidationService,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Result returned to the controller for the three write endpoints.
     */
    data class WriteResult(
        val documentId: UUID,
        val key: String,
        val version: Long,
        val value: JsonNode,
        val matchedScopeType: String,
        val matchedScopeId: String?,
        val consumerReload: List<String>,
        val correlationId: UUID,
    )

    /**
     * Create a new key (POST /v1/configurations).
     */
    @Transactional
    fun createKey(
        key: String,
        schema: JsonNode,
        value: JsonNode,
        scopeType: String,
        scopeId: String?,
        reason: String,
        actorId: UUID,
        actorIp: String?,
        correlationId: UUID,
    ): WriteResult {
        if (documentRepository.findByKey(key).isPresent) {
            throw ApiException(HttpStatus.CONFLICT, "CONFIG_KEY_EXISTS", "Configuration key '$key' already exists")
        }
        // Schema row.
        val schemaId = UUID.randomUUID()
        val schemaRow =
            ConfigurationSchema(
                id = schemaId,
                key = key,
                version = 1,
                jsonSchema = schema.toString(),
                createdAt = Instant.now(),
                createdBy = actorId,
            )
        schemaRepository.save(schemaRow)

        // Validate the value against the new schema.
        val errors = schemaValidationService.validate(schemaRow.jsonSchema, value)
        if (errors.isNotEmpty()) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_FAILED",
                "Value does not match schema",
                details = errors.map { mapOf("field" to it.field, "message" to it.message) },
            )
        }

        val documentId = UUID.randomUUID()
        val now = Instant.now()
        val document =
            Document(
                id = documentId,
                key = key,
                tenantId = "global",
                currentVersion = 1,
                schemaId = schemaId,
                value = mapper.writeValueAsString(value),
                valueType = detectValueType(value),
                deactivatedAt = null,
                createdAt = now,
                updatedAt = now,
                createdBy = actorId,
                updatedBy = actorId,
            )
        documentRepository.save(document)

        val versionRow =
            ConfigurationVersion(
                pk = ConfigurationVersionPk(id = uuidV7(), createdAt = now),
                documentId = documentId,
                version = 1,
                value = mapper.writeValueAsString(value),
                scopeType = scopeType,
                scopeId = scopeId,
                cohort = null,
                effectiveFrom = null,
                effectiveTo = null,
                reason = reason,
                correlationId = correlationId,
                actorId = actorId,
                clientIp = actorIp,
            )
        versionRepository.save(versionRow)

        recordAudit(
            documentId = documentId,
            version = 1,
            action = "create",
            oldValue = null,
            newValue = versionRow.value,
            actorId = actorId,
            reason = reason,
            correlationId = correlationId,
            clientIp = actorIp,
        )

        publishUpdated(documentId, key, 1, null, 1, value, scopeType, scopeId, actorId, reason, correlationId)

        return WriteResult(
            documentId = documentId,
            key = key,
            version = 1,
            value = value,
            matchedScopeType = scopeType,
            matchedScopeId = scopeId,
            consumerReload = consumersForKey(key),
            correlationId = correlationId,
        )
    }

    /**
     * Put a new version (PUT /v1/configurations/{key}/versions).
     */
    @Transactional
    fun putVersion(
        key: String,
        value: JsonNode,
        scopeType: String,
        scopeId: String?,
        cohort: JsonNode?,
        effectiveFrom: Instant?,
        effectiveTo: Instant?,
        expectedCurrentVersion: Long,
        reason: String,
        actorId: UUID,
        actorIp: String?,
        correlationId: UUID,
    ): WriteResult {
        val document =
            documentRepository.lockByKey(key).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CONFIG_KEY_NOT_FOUND", "Configuration key '$key' not found")
            }
        if (document.currentVersion != expectedCurrentVersion) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "Expected current version $expectedCurrentVersion but found ${document.currentVersion}",
            )
        }
        val schema =
            schemaRepository
                .findByKeyAndVersion(key, latestSchemaVersion(key))
                .orElseThrow {
                    ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Schema missing for key '$key'")
                }
        val errors = schemaValidationService.validate(schema.jsonSchema, value)
        if (errors.isNotEmpty()) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_FAILED",
                "Value does not match schema",
                details = errors.map { mapOf("field" to it.field, "message" to it.message) },
            )
        }

        val newVersion = document.currentVersion + 1
        val now = Instant.now()
        val versionRow =
            ConfigurationVersion(
                pk = ConfigurationVersionPk(id = uuidV7(), createdAt = now),
                documentId = document.id,
                version = newVersion,
                value = mapper.writeValueAsString(value),
                scopeType = scopeType,
                scopeId = scopeId,
                cohort = cohort?.let { mapper.writeValueAsString(it) },
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                reason = reason,
                correlationId = correlationId,
                actorId = actorId,
                clientIp = actorIp,
            )
        versionRepository.save(versionRow)

        val oldValue = document.value
        document.value = versionRow.value
        document.currentVersion = newVersion
        document.updatedAt = now
        document.updatedBy = actorId
        documentRepository.save(document)

        recordAudit(
            documentId = document.id,
            version = newVersion,
            action = "update",
            oldValue = oldValue,
            newValue = versionRow.value,
            actorId = actorId,
            reason = reason,
            correlationId = correlationId,
            clientIp = actorIp,
        )

        publishUpdated(
            documentId = document.id,
            key = key,
            version = newVersion,
            oldVersion = expectedCurrentVersion,
            newVersionForEvent = newVersion,
            value = value,
            scopeType = scopeType,
            scopeId = scopeId,
            actorId = actorId,
            reason = reason,
            correlationId = correlationId,
        )

        return WriteResult(
            documentId = document.id,
            key = key,
            version = newVersion,
            value = value,
            matchedScopeType = scopeType,
            matchedScopeId = scopeId,
            consumerReload = consumersForKey(key),
            correlationId = correlationId,
        )
    }

    /**
     * Rollback to a prior version (POST /v1/configurations/{key}/rollback).
     */
    @Transactional
    fun rollback(
        key: String,
        toVersion: Long,
        reason: String,
        actorId: UUID,
        actorIp: String?,
        correlationId: UUID,
    ): WriteResult {
        val document =
            documentRepository.lockByKey(key).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CONFIG_KEY_NOT_FOUND", "Configuration key '$key' not found")
            }
        val target =
            versionRepository.findByDocumentAndVersion(document.id, toVersion).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "Version $toVersion not found for key '$key'")
            }
        val newVersion = document.currentVersion + 1
        val now = Instant.now()
        val versionRow =
            ConfigurationVersion(
                pk = ConfigurationVersionPk(id = uuidV7(), createdAt = now),
                documentId = document.id,
                version = newVersion,
                value = target.value,
                scopeType = target.scopeType,
                scopeId = target.scopeId,
                cohort = target.cohort,
                effectiveFrom = target.effectiveFrom,
                effectiveTo = target.effectiveTo,
                reason = reason,
                correlationId = correlationId,
                actorId = actorId,
                clientIp = actorIp,
            )
        versionRepository.save(versionRow)

        val oldValue = document.value
        document.value = target.value
        document.currentVersion = newVersion
        document.updatedAt = now
        document.updatedBy = actorId
        documentRepository.save(document)

        recordAudit(
            documentId = document.id,
            version = newVersion,
            action = "rollback",
            oldValue = oldValue,
            newValue = target.value,
            actorId = actorId,
            reason = reason,
            correlationId = correlationId,
            clientIp = actorIp,
        )

        // Publish configuration.rolled_back.v1.
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "event_id" to UUID.randomUUID().toString(),
                    "event_name" to "configuration.rolled_back.v1",
                    "occurred_at" to now.toString(),
                    "schema_version" to 1,
                    "producer" to "configuration-service",
                    "tenant_id" to document.tenantId,
                    "correlation_id" to correlationId.toString(),
                    "causation_id" to null,
                    "aggregate_type" to "ConfigurationDocument",
                    "aggregate_id" to document.id.toString(),
                    "data" to
                        mapOf(
                            "key" to key,
                            "version" to newVersion,
                            "from_version" to toVersion,
                            "to_version" to newVersion,
                            "value" to mapper.readTree(target.value),
                            "scope_type" to target.scopeType,
                            "scope_id" to target.scopeId,
                            "actor_id" to actorId.toString(),
                            "reason" to reason,
                        ),
                ),
            )
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.rolled_back",
                eventId = UUID.randomUUID(),
                payload = payload,
            ),
        )
        log.info("Rolled back key={} to version={} as new version={}", key, toVersion, newVersion)

        val value = mapper.readTree(target.value)
        return WriteResult(
            documentId = document.id,
            key = key,
            version = newVersion,
            value = value,
            matchedScopeType = target.scopeType,
            matchedScopeId = target.scopeId,
            consumerReload = consumersForKey(key),
            correlationId = correlationId,
        )
    }

    /**
     * Mark a key deprecated (POST /v1/configurations/{key}/deprecate).
     */
    @Transactional
    fun deprecate(
        key: String,
        replacementKey: String?,
        reason: String,
        actorId: UUID,
        actorIp: String?,
        correlationId: UUID,
    ) {
        val document =
            documentRepository.lockByKey(key).orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, "CONFIG_KEY_NOT_FOUND", "Configuration key '$key' not found")
            }
        val now = Instant.now()
        recordAudit(
            documentId = document.id,
            version = document.currentVersion,
            action = "deprecate",
            oldValue = document.value,
            newValue = null,
            actorId = actorId,
            reason = reason,
            correlationId = correlationId,
            clientIp = actorIp,
        )
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "event_id" to UUID.randomUUID().toString(),
                    "event_name" to "configuration.key.deprecated.v1",
                    "occurred_at" to now.toString(),
                    "schema_version" to 1,
                    "producer" to "configuration-service",
                    "tenant_id" to document.tenantId,
                    "correlation_id" to correlationId.toString(),
                    "causation_id" to null,
                    "aggregate_type" to "ConfigurationDocument",
                    "aggregate_id" to document.id.toString(),
                    "data" to
                        mapOf(
                            "key" to key,
                            "reason" to reason,
                            "replacement_key" to replacementKey,
                        ),
                ),
            )
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.key.deprecated",
                eventId = UUID.randomUUID(),
                payload = payload,
            ),
        )
        log.info("Deprecated key={}", key)
    }

    private fun publishUpdated(
        documentId: UUID,
        key: String,
        version: Long,
        oldVersion: Long?,
        newVersionForEvent: Long,
        value: JsonNode,
        scopeType: String,
        scopeId: String?,
        actorId: UUID,
        reason: String,
        correlationId: UUID,
    ) {
        val data =
            mutableMapOf<String, Any?>(
                "key" to key,
                "version" to newVersionForEvent,
                "old_version" to oldVersion,
                "value" to value,
                "scope_type" to scopeType,
                "scope_id" to scopeId,
                "actor_id" to actorId.toString(),
                "reason" to reason,
            )
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "event_id" to UUID.randomUUID().toString(),
                    "event_name" to "configuration.updated.v1",
                    "occurred_at" to Instant.now().toString(),
                    "schema_version" to 1,
                    "producer" to "configuration-service",
                    "tenant_id" to "global",
                    "correlation_id" to correlationId.toString(),
                    "causation_id" to null,
                    "aggregate_type" to "ConfigurationDocument",
                    "aggregate_id" to documentId.toString(),
                    "data" to data,
                ),
            )
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.updated",
                eventId = UUID.randomUUID(),
                payload = payload,
            ),
        )
    }

    private fun recordAudit(
        documentId: UUID,
        version: Long,
        action: String,
        oldValue: String?,
        newValue: String?,
        actorId: UUID,
        reason: String,
        correlationId: UUID,
        clientIp: String?,
        requestSignature: String? = null,
    ) {
        auditLogRepository.save(
            ConfigurationAuditLog(
                pk = ConfigurationAuditLogPk(id = uuidV7(), createdAt = Instant.now()),
                documentId = documentId,
                version = version,
                action = action,
                oldValue = oldValue,
                newValue = newValue,
                actorId = actorId,
                reason = reason,
                correlationId = correlationId,
                clientIp = clientIp,
                requestSignature = requestSignature,
            ),
        )
    }

    private fun latestSchemaVersion(key: String): Int = schemaRepository.maxVersionForKey(key) ?: 1

    private fun consumersForKey(key: String): List<String> =
        when {
            key.startsWith("trip.reward.") -> listOf("trip-service")
            key.startsWith("pricing.rating_density.") -> listOf("pricing-service")
            key.startsWith("pricing.loyalty.") -> listOf("pricing-service")
            key.startsWith("pricing.geo_overrides.") -> listOf("pricing-service")
            key.startsWith("payment.gateway.") -> listOf("payment-service")
            key.startsWith("deal.") -> listOf("trip-service", "food-order-service", "driver-service", "courier-service")
            else -> listOf("all-subscribers")
        }

    private fun detectValueType(node: JsonNode): String =
        when {
            node.isTextual -> "string"
            node.isInt || node.isLong || node.isShort || node.isBigInteger -> "number"
            node.isFloatingPointNumber || node.isDouble || node.isFloat || node.isBigDecimal -> "number"
            node.isBoolean -> "boolean"
            node.isObject -> "object"
            node.isArray -> "array"
            node.isNull -> "null"
            else -> "string"
        }
}
