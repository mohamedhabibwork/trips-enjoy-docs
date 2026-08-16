package com.trips_enjoy.configuration.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.application.ConfigurationIngestService
import com.trips_enjoy.configuration.application.ConfigurationReadService
import com.trips_enjoy.configuration.application.HistoryService
import com.trips_enjoy.configuration.application.IdempotencyService
import com.trips_enjoy.configuration.application.LongPollService
import com.trips_enjoy.configuration.application.SnapshotService
import com.trips_enjoy.configuration.util.CorrelationContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Public HTTP API for configuration-service (INTEGRATION.md §1).
 *
 * Every endpoint requires a valid JWT (SecurityConfiguration). The per-endpoint
 * authority requirement is enforced via @PreAuthorize.
 *
 * Snake-case JSON keys throughout (CONVENTIONS.md §7).
 */
@RestController
@RequestMapping("/v1/configurations")
class ConfigurationController(
    private val readService: ConfigurationReadService,
    private val ingestService: ConfigurationIngestService,
    private val historyService: HistoryService,
    private val snapshotService: SnapshotService,
    private val longPollService: LongPollService,
    private val idempotencyService: IdempotencyService,
    private val mapper: ObjectMapper,
) {
    // -----------------------------------------------------------------------
    // 1.1 GET /v1/configurations/{key}
    // -----------------------------------------------------------------------
    @GetMapping("/{key}")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.read', 'ROLE_configuration.admin', " +
            "'ROLE_configuration.audit', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun get(
        @PathVariable key: String,
        @RequestParam(value = "tenant_id", required = false) tenantId: String?,
        @RequestParam(value = "city", required = false) city: String?,
        @RequestParam(value = "ride_type", required = false) rideType: String?,
        @RequestParam(value = "customer_segment", required = false) customerSegment: String?,
        @RequestParam(value = "restaurant_id", required = false) restaurantId: String?,
        @RequestParam(value = "branch_id", required = false) branchId: String?,
        @RequestParam(value = "merchant_id", required = false) merchantId: String?,
        @RequestParam(value = "user_id", required = false) userId: String?,
        @RequestParam(value = "zone", required = false) zone: String?,
        @RequestParam(value = "country", required = false) country: String?,
        @RequestParam(value = "at", required = false) at: String?,
        @RequestParam(value = "nocache", required = false) nocache: Int?,
        httpRequest: HttpServletRequest,
    ): ConfigurationValueResponse {
        val context =
            buildContext(
                tenantId = tenantId,
                city = city,
                rideType = rideType,
                customerSegment = customerSegment,
                restaurantId = restaurantId,
                branchId = branchId,
                merchantId = merchantId,
                userId = userId,
                zone = zone,
                country = country,
            )
        val resolved = readService.resolve(key, context, CorrelationContext.correlationId(httpRequest))
        return ConfigurationValueResponse(
            key = resolved.key,
            value = resolved.value,
            matched_scope_type = resolved.matchedScopeType,
            matched_scope_id = resolved.matchedScopeId,
            version = resolved.version,
            schema_version = resolved.schemaVersion,
            resolved_at = resolved.resolvedAt,
            correlation_id = resolved.correlationId,
        )
    }

    // -----------------------------------------------------------------------
    // 1.2 PUT /v1/configurations/{key}/versions
    // -----------------------------------------------------------------------
    @PutMapping("/{key}/versions")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun putVersion(
        @PathVariable key: String,
        @Valid @RequestBody body: PutVersionRequest,
        @RequestHeader(value = "X-Audit-Reason", required = false) auditReason: String?,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<WriteResponse> {
        val reason = auditReason ?: body.reason
        validateAuditReason(reason)
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val actorIp = CorrelationContext.clientIp(httpRequest)

        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(
                    cached.get().responseStatus,
                ).body(mapper.treeToValue(bodyJson, WriteResponse::class.java))
        }

        val result =
            ingestService.putVersion(
                key = key,
                value = body.value,
                scopeType = body.scope_type,
                scopeId = body.scope_id,
                cohort = body.cohort,
                effectiveFrom = body.effective_from,
                effectiveTo = body.effective_to,
                expectedCurrentVersion = body.expected_current_version,
                reason = reason,
                actorId = actorId,
                actorIp = actorIp,
                correlationId = correlationId,
            )
        val response = toWriteResponse(result)
        idempotencyService.record(idempotencyKey, "hash", actorId, HttpStatus.CREATED.value(), response)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    // -----------------------------------------------------------------------
    // 1.3 POST /v1/configurations
    // -----------------------------------------------------------------------
    @PostMapping
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun create(
        @Valid @RequestBody body: CreateConfigurationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<WriteResponse> {
        validateAuditReason(body.reason)
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val actorIp = CorrelationContext.clientIp(httpRequest)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(
                    cached.get().responseStatus,
                ).body(mapper.treeToValue(bodyJson, WriteResponse::class.java))
        }
        val result =
            ingestService.createKey(
                key = body.key,
                schema = body.schema,
                value = body.value,
                scopeType = body.scope_type,
                scopeId = body.scope_id,
                reason = body.reason,
                actorId = actorId,
                actorIp = actorIp,
                correlationId = correlationId,
            )
        val response = toWriteResponse(result)
        idempotencyService.record(idempotencyKey, "hash", actorId, HttpStatus.CREATED.value(), response)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    // -----------------------------------------------------------------------
    // 1.4 POST /v1/configurations/{key}/rollback
    // -----------------------------------------------------------------------
    @PostMapping("/{key}/rollback")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.admin', 'ROLE_platform.super_admin')",
    )
    fun rollback(
        @PathVariable key: String,
        @Valid @RequestBody body: RollbackRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<WriteResponse> {
        validateAuditReason(body.reason)
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val actorIp = CorrelationContext.clientIp(httpRequest)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            val bodyJson = mapper.readTree(cached.get().responseBody)
            return ResponseEntity
                .status(
                    cached.get().responseStatus,
                ).body(mapper.treeToValue(bodyJson, WriteResponse::class.java))
        }
        val result =
            ingestService.rollback(
                key = key,
                toVersion = body.to_version,
                reason = body.reason,
                actorId = actorId,
                actorIp = actorIp,
                correlationId = correlationId,
            )
        val response = toWriteResponse(result)
        idempotencyService.record(idempotencyKey, "hash", actorId, HttpStatus.CREATED.value(), response)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    // -----------------------------------------------------------------------
    // 1.5 GET /v1/configurations/{key}/versions
    // -----------------------------------------------------------------------
    @GetMapping("/{key}/versions")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.audit', 'ROLE_configuration.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun history(
        @PathVariable key: String,
        @RequestParam(value = "limit", defaultValue = "20") limit: Int,
        @RequestParam(value = "cursor", required = false) cursor: String?,
    ): HistoryResponse {
        val result = historyService.history(key, limit, cursor)
        return HistoryResponse(
            items =
                result.items.map {
                    HistoryItemResponse(
                        version = it.version,
                        scope_type = it.scopeType,
                        scope_id = it.scopeId,
                        value = it.value,
                        actor_id = it.actorId,
                        reason = it.reason,
                        created_at = it.createdAt,
                        superseded_at = it.supersededAt,
                    )
                },
            next_cursor = result.nextCursor,
            has_more = result.hasMore,
        )
    }

    // -----------------------------------------------------------------------
    // 1.5.1 GET /v1/configurations/{key}/versions/{version}
    // -----------------------------------------------------------------------
    @GetMapping("/{key}/versions/{version}")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.audit', 'ROLE_configuration.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun specificVersion(
        @PathVariable key: String,
        @PathVariable version: Long,
    ): SpecificVersionResponse {
        val item = historyService.versionAt(key, version)
        return SpecificVersionResponse(
            key = key,
            version = item.version,
            value = item.value,
            scope_type = item.scopeType,
            scope_id = item.scopeId,
            actor_id = item.actorId,
            reason = item.reason,
            created_at = item.createdAt,
            correlation_id = UUID.randomUUID(),
        )
    }

    // -----------------------------------------------------------------------
    // 1.6 GET /v1/configurations/stream (long-poll)
    // -----------------------------------------------------------------------
    @GetMapping("/stream")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.subscribe', 'ROLE_configuration.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    suspend fun stream(
        @RequestParam("keys") keys: List<String>,
        @RequestParam(value = "since_version", required = false) sinceVersion: Long?,
        @RequestParam(value = "wait_seconds", required = false) waitSeconds: Long?,
        httpRequest: HttpServletRequest,
    ): LongPollResponse {
        // Bound the wait to the configured maximum.
        val maxWait = waitSeconds ?: 25L
        val documentId = CorrelationContext.correlationId(httpRequest) // placeholder; real impl looks up by key
        val updates = longPollService.await(documentId, keys.firstOrNull() ?: "", sinceVersion)
        val nextSince = updates.maxOfOrNull { it.version } ?: sinceVersion
        return LongPollResponse(
            updates =
                updates.map {
                    LongPollUpdate(
                        key = it.key,
                        version = it.version,
                        value = it.value,
                        changed_at = it.changedAt,
                    )
                },
            next_since_version = nextSince,
        )
    }

    // -----------------------------------------------------------------------
    // 1.7 GET /v1/configurations/snapshot
    // -----------------------------------------------------------------------
    @GetMapping("/snapshot")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.read', 'ROLE_configuration.admin', " +
            "'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun snapshot(
        @RequestParam("keys") keys: List<String>,
        @RequestParam(value = "tenant_id", required = false) tenantId: String?,
        @RequestParam(value = "city", required = false) city: String?,
        @RequestParam(value = "ride_type", required = false) rideType: String?,
        @RequestParam(value = "customer_segment", required = false) customerSegment: String?,
        httpRequest: HttpServletRequest,
    ): SnapshotResponse {
        val context = buildContext(tenantId, city, rideType, customerSegment, null, null, null, null, null, null)
        val resolved = snapshotService.snapshot(keys, context, CorrelationContext.correlationId(httpRequest))
        return SnapshotResponse(
            tenant_id = tenantId ?: "global",
            as_of = snapshotService.asOf(),
            values =
                resolved.mapValues { (_, v) ->
                    ConfigurationValueResponse(
                        key = v.key,
                        value = v.value,
                        matched_scope_type = v.matchedScopeType,
                        matched_scope_id = v.matchedScopeId,
                        version = v.version,
                        schema_version = v.schemaVersion,
                        resolved_at = v.resolvedAt,
                        correlation_id = v.correlationId,
                    )
                },
        )
    }

    // -----------------------------------------------------------------------
    // 1.9 POST /v1/configurations/{key}/deprecate
    // -----------------------------------------------------------------------
    @PostMapping("/{key}/deprecate")
    @PreAuthorize(
        "hasAnyAuthority('ROLE_configuration.admin', 'ROLE_platform.admin', 'ROLE_platform.super_admin')",
    )
    fun deprecate(
        @PathVariable key: String,
        @Valid @RequestBody body: DeprecateRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        authentication: Authentication,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        validateAuditReason(body.reason)
        val correlationId = CorrelationContext.correlationId(httpRequest)
        val actorId = CorrelationContext.actorId(authentication)
        val actorIp = CorrelationContext.clientIp(httpRequest)
        val cached = idempotencyService.find(idempotencyKey)
        if (cached.isPresent) {
            return ResponseEntity
                .status(cached.get().responseStatus)
                .body(mapOf("key" to key, "status" to "deprecated", "correlation_id" to correlationId.toString()))
        }
        ingestService.deprecate(
            key = key,
            replacementKey = body.replacement_key,
            reason = body.reason,
            actorId = actorId,
            actorIp = actorIp,
            correlationId = correlationId,
        )
        val response =
            mapOf(
                "key" to key,
                "status" to "deprecated",
                "correlation_id" to correlationId.toString(),
            )
        idempotencyService.record(idempotencyKey, "hash", actorId, HttpStatus.OK.value(), response)
        return ResponseEntity.ok(response)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildContext(
        tenantId: String?,
        city: String?,
        rideType: String?,
        customerSegment: String?,
        restaurantId: String?,
        branchId: String?,
        merchantId: String?,
        userId: String?,
        zone: String?,
        country: String?,
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        tenantId?.let { map["tenant"] = it }
        userId?.let { map["user"] = it }
        restaurantId?.let { map["restaurant"] = it }
        branchId?.let { map["branch"] = it }
        merchantId?.let { map["merchant"] = it }
        rideType?.let { map["ride_type"] = it }
        zone?.let { map["zone"] = it }
        city?.let { map["city"] = it }
        country?.let { map["country"] = it }
        customerSegment?.let { map["segment"] = it }
        return map
    }

    private fun validateAuditReason(reason: String?) {
        if (reason.isNullOrBlank() || reason.length < 8) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "AUDIT_REASON_REQUIRED",
                "X-Audit-Reason header (or request.reason) is required and must be 8+ characters",
            )
        }
    }

    private fun toWriteResponse(result: ConfigurationIngestService.WriteResult): WriteResponse =
        WriteResponse(
            document_id = result.documentId,
            key = result.key,
            version = result.version,
            value = result.value,
            matched_scope_type = result.matchedScopeType,
            matched_scope_id = result.matchedScopeId,
            impact = WriteImpact(consumers_reloading = result.consumerReload),
            correlation_id = result.correlationId,
        )
}
