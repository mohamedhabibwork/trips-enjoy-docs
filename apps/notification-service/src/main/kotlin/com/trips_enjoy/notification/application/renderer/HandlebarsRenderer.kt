package com.trips_enjoy.notification.application.renderer

import com.github.jknack.handlebars.Context
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Template
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Plain-template renderer using Handlebars (jknack 4.x).
 *
 * Caches compiled Handlebars templates by their source body; this is safe
 * because `Template.body` is immutable once published — new versions land
 * as new rows in `notification.templates` and `template_history`.
 *
 * Per TECH.md and SKELETON.gradle.kts: Handlebars compile + cache.
 */
@Component
class HandlebarsRenderer {
	private val handlebars = Handlebars()
	private val cache = ConcurrentHashMap<String, Template>()

	fun render(templateSource: String, data: Map<String, Any?>): String {
		val compiled = cache.computeIfAbsent(templateSource) {
			handlebars.compileInline(it)
		}
		return compiled.apply(Context.newContext(data))
	}

	fun renderSubject(templateSource: String?, data: Map<String, Any?>): String? =
		templateSource?.let { render(it, data) }

	/** Test-only seam for cache reset. */
	fun resetCache() = cache.clear()
}