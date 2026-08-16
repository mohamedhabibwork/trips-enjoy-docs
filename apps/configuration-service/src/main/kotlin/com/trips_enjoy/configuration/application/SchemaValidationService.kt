package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import org.springframework.stereotype.Service

/**
 * JSON Schema validation for incoming configuration values (FR-004 / DATA-002).
 *
 * The schema is stored as a JSON Schema 2020-12 document in
 * `configuration.schemas.json_schema`. This service compiles it once,
 * caches by `(key, version)`, and validates the candidate value on write.
 *
 * On failure, returns a summary list suitable for the `VALIDATION_FAILED`
 * error envelope's `details[]` array.
 */
@Service
class SchemaValidationService(
    private val mapper: ObjectMapper,
) {
    private val schemaFactory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val cache: MutableMap<String, JsonSchema> = HashMap()

    data class SchemaFailure(
        val field: String,
        val message: String,
    )

    fun validate(
        schemaJson: String,
        valueNode: JsonNode,
    ): List<SchemaFailure> {
        val schema = compile(schemaJson)
        val errors: Set<ValidationMessage> = schema.validate(valueNode)
        return errors.map { SchemaFailure(field = it.instanceLocation.toString(), message = it.message) }
    }

    private fun compile(schemaJson: String): JsonSchema {
        val schemaNode = mapper.readTree(schemaJson)
        return cache.getOrPut(schemaJson) {
            schemaFactory.getSchema(schemaNode)
        }
    }
}
