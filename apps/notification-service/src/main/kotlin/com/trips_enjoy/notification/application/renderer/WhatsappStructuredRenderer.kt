package com.trips_enjoy.notification.application.renderer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.trips_enjoy.notification.api.ApiException
import com.trips_enjoy.notification.domain.enums.TemplateType
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * WhatsApp structured renderer per docs/services/notification-service/WHATSAPP_TEMPLATES.md.
 *
 *   - `whatsapp_variables["{index}"]` is substituted into every `{{index}}`
 *     placeholder inside `body_structured.text` and into `header.text`,
 *     `footer.text`, `buttons[].text`, `buttons[].url`, `buttons[].code`.
 *   - `key` is OUR logical variable name (validated against `required_variables[]`
 *     at publish time).
 *   - `variables[].index` is the provider-side position (1-based).
 *   - Missing index for a referenced placeholder → 422 `RENDER_MISSING_INDEX`
 *     and NO delivery (TECH.md §9.6 failure path).
 *   - Literal `{{{{` → `{{` (WhatsApp escape rule).
 */
@Component
class WhatsappStructuredRenderer(private val mapper: ObjectMapper) {

	fun render(
		templateSourceJson: String,
		whatsappVariables: Map<String, String>,
	): String {
		val root = mapper.readTree(templateSourceJson) as? ObjectNode
			?: throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_HAS_NO_BODY_STRUCTURED", "body_structured must be a JSON object")
		// header / body / footer / buttons / variables
		renderComponent(root.get("header"), whatsappVariables)?.let { root.set<JsonNode>("header", it) }
		renderComponent(root.get("body"), whatsappVariables)?.let { root.set<JsonNode>("body", it) }
		renderComponent(root.get("footer"), whatsappVariables)?.let { root.set<JsonNode>("footer", it) }
		renderButtons(root.get("buttons"), whatsappVariables)?.let { root.set<JsonNode>("buttons", it) }
		return mapper.writeValueAsString(root)
	}

	private fun renderComponent(node: JsonNode?, variables: Map<String, String>): JsonNode? {
		if (node == null || node.isNull) return node
		val text = node.get("text")?.asText() ?: return node
		val updated = (node as ObjectNode).deepCopy()
		updated.put("text", substitute(text, variables))
		return updated
	}

	private fun renderButtons(node: JsonNode?, variables: Map<String, String>): JsonNode? {
		if (node == null || node.isNull) return node
		val arr = node as ArrayNode
		arr.forEachIndexed { idx, btn ->
			val obj = btn as ObjectNode
			obj.put("text", substitute(obj.get("text")?.asText().orEmpty(), variables))
			obj.get("url")?.asText()?.let { obj.put("url", substitute(it, variables)) }
			obj.get("code")?.asText()?.let { obj.put("code", substitute(it, variables)) }
			arr.set(idx, obj)
		}
		return arr
	}

	/**
	 * Substitutes both `{{n}}` provider-indexed placeholders AND `{{key}}` named
	 * placeholders drawn from the same `variables` map (the test fixture
	 * passes `platform_brand = "TripsEnjoy"` alongside the indexed values).
	 *
	 * Missing index for a referenced `{{n}}` → 422 `RENDER_MISSING_INDEX`
	 * and NO delivery (TECH.md §9.6 failure path).
	 */
	private fun substitute(text: String, variables: Map<String, String>): String {
		if (!text.contains("{{")) return text
		val numbered = Regex("""\{\{(\d+)\}\}""")
		var result = numbered.replace(text) { match ->
			val key = match.groupValues[1]
			variables["{$key}"] ?: throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"RENDER_MISSING_INDEX",
				"whatsapp_variables is missing placeholder {{{$key}}}",
			)
		}
		// Named keys (only when literal alphanumeric — avoid re-touching indexed placeholders).
		val named = Regex("""\{\{([a-zA-Z_][a-zA-Z0-9_]*)\}\}""")
		result = named.replace(result) { match ->
			val key = match.groupValues[1]
			variables[key] ?: match.value
		}
		return result
	}

	/** Test-only validation helper for the publish-time discriminator. */
	fun validateRequiredVariables(
		templateSourceJson: String,
		requiredVariables: List<String>,
	) {
		// Read with explicit ObjectNode+ArrayNode typing; if any cast fails,
		// the template is malformed and we report it (no silent return).
		val root: com.fasterxml.jackson.databind.node.ObjectNode = try {
			mapper.readTree(templateSourceJson) as? com.fasterxml.jackson.databind.node.ObjectNode
				?: throw ApiException(
					HttpStatus.UNPROCESSABLE_ENTITY,
					"TEMPLATE_VALIDATION_FAILED",
					"body_structured root is not an object",
				)
		} catch (e: ApiException) {
			throw e
		} catch (e: Exception) {
			throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"TEMPLATE_VALIDATION_FAILED",
				"invalid body_structured JSON: ${e.message}",
			)
		}
		val vars: com.fasterxml.jackson.databind.node.ArrayNode = root.get("variables") as? com.fasterxml.jackson.databind.node.ArrayNode
			?: throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"TEMPLATE_VALIDATION_FAILED",
				"body_structured.variables[] is missing or not an array",
			)
		val declared = mutableSetOf<String>()
		vars.forEach { v ->
			val k = v.get("key")
			if (k != null && k.isTextual) declared.add(k.asText())
		}
		val missing = requiredVariables.filter { it !in declared }
		if (missing.isNotEmpty()) {
			throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"TEMPLATE_VALIDATION_FAILED",
				"required_variables[] missing from body_structured.variables[]: $missing",
			)
		}
	}

	companion object {
		/** Convenience for callers that need to assert the discriminator. */
		fun assertStructured(templateType: TemplateType) {
			if (templateType != TemplateType.WHATSAPP_STRUCTURED) {
				throw ApiException(
					HttpStatus.UNPROCESSABLE_ENTITY,
					"TEMPLATE_HAS_NO_BODY_STRUCTURED",
					"Template is not whatsapp_structured (type=${templateType.value})",
				)
			}
		}
	}
}