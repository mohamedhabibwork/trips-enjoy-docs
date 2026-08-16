package com.trips_enjoy.platform.web

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for PII redaction. Field names listed in [fields] are
 * replaced with `***` in request/response logs and in any downstream
 * payload that is run through the [PiiRedactor.redact] helper.
 */
@ConfigurationProperties("platform.logging.pii")
data class PiiRedactionProperties(
    val enabled: Boolean = true,
    val fields: List<String> = listOf(
        "password",
        "pwd",
        "secret",
        "token",
        "access_token",
        "refresh_token",
        "authorization",
        "id_token",
        "client_secret",
        "card",
        "cardNumber",
        "cvv",
        "ssn",
        "national_id",
        "phone",
        "email",
    ),
)

/**
 * PII redaction helper. Replaces values for fields whose key (case-insensitive)
 * matches any name in the configured [fields] list with `***`.
 */
class PiiRedactor(private val properties: PiiRedactionProperties) {

    private val fieldSet: Set<String> = properties.fields.map { it.lowercase() }.toSet()

    fun redactValue(fieldName: String, value: Any?): Any? {
        if (!properties.enabled) return value
        return if (fieldName.lowercase() in fieldSet) "***" else value
    }

    fun redactMap(input: Map<String, Any?>): Map<String, Any?> =
        input.mapValues { (k, v) -> redactValue(k, v) }
}
