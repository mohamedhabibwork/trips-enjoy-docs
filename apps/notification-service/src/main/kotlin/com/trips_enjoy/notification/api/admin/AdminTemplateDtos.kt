package com.trips_enjoy.notification.api.admin

import com.fasterxml.jackson.annotation.JsonInclude
import com.trips_enjoy.notification.domain.TemplateHistory

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateTemplateRequest(
	val name: String,
	val category: String,
	val channel: String,
	val locale: String,
	val subject: String? = null,
	val body: String? = null,
	val template_type: String = "plain", // plain | whatsapp_structured
	val body_structured: Map<String, Any?>? = null,
	val provider_template_id: String? = null,
	val provider_template_language: String? = null,
	val provider_template_status: String = "draft",
	val required_variables: List<String> = emptyList(),
	val metadata: Map<String, Any?> = emptyMap(),
	val status: String = "active",
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UpdateTemplateRequest(
	val subject: String? = null,
	val body: String? = null,
	val body_structured: Map<String, Any?>? = null,
	val required_variables: List<String>? = null,
	val metadata: Map<String, Any?>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TemplateResponse(
	val id: String,
	val name: String,
	val category: String,
	val channel: String,
	val locale: String,
	val subject: String?,
	val body: String?,
	val template_type: String,
	val provider_template_id: String?,
	val provider_template_language: String?,
	val provider_template_status: String,
	val required_variables: List<String>,
	val status: String,
	val version: Int,
	val created_at: String,
	val updated_at: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TemplateHistoryResponse(
	val id: String,
	val template_id: String,
	val revision_no: Int,
	val version: Int,
	val channel: String,
	val locale: String,
	val template_type: String,
	val provider_template_status: String,
	val diff_summary: Map<String, Any?>,
	val published_by: String,
	val approved_by: String?,
	val created_at: String,
) {
	companion object {
		fun from(h: TemplateHistory, diffSummaryMap: Map<String, Any?>): TemplateHistoryResponse = TemplateHistoryResponse(
			id = h.id.toString(),
			template_id = h.templateId.toString(),
			revision_no = h.revisionNo,
			version = h.version,
			channel = h.channel.value,
			locale = h.locale,
			template_type = h.templateType.value,
			provider_template_status = h.providerTemplateStatus.value,
			diff_summary = diffSummaryMap,
			published_by = h.publishedBy.toString(),
			approved_by = h.approvedBy?.toString(),
			created_at = h.createdAt.toString(),
		)
	}
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PublishTemplateRequest(
	val diff_summary: Map<String, Any?> = mapOf("note" to "manual publish"),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApproveTemplateRequest(
	val provider_template_id: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HistoryResponse(
	val template_id: String,
	val history: List<TemplateHistoryResponse>,
)