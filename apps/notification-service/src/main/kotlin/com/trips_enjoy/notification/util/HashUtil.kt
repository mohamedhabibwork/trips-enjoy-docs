package com.trips_enjoy.notification.util

import java.security.MessageDigest

/**
 * SHA-256 hex digest of any value (stringified via `toString()`). Used by the
 * idempotency helper to detect `Idempotency-Key` reuse with a different body
 * (docs/architecture/API_STANDARDS.md §9).
 */
fun sha256Hex(value: Any?): String {
	val bytes = MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8))
	return bytes.joinToString("") { "%02x".format(it) }
}