package com.trips_enjoy.notification.application.renderer

import com.trips_enjoy.notification.domain.Template
import com.trips_enjoy.notification.domain.TemplateHistory
import java.time.Instant

/**
 * Renderer output bundle. Always populated; for plain templates `subject` and
 * `body` carry the Handlebars-substituted text; for WhatsApp structured
 * `bodyStructuredJson` carries the substituted components verbatim
 * (post `{index}` substitution, ready for Meta Cloud / 360dialog).
 *
 * `encryptedSubject` / `encryptedBody` are pgcrypto ciphertext blobs written
 * onto the `notification.deliveries` row (PII protection; right-to-erasure
 * NULLs them).
 */
data class RenderedDelivery(
	val template: Template,
	val snapshot: TemplateHistory,
	val renderedSubject: String?,
	val renderedBody: String?,
	val renderedBodyStructuredJson: String?,
	val encryptedSubject: ByteArray?,
	val encryptedBody: ByteArray?,
	val renderedAt: Instant = Instant.now(),
)