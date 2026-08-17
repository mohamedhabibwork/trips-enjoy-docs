package com.trips_enjoy.notification.util

import java.util.UUID

/**
 * Helpers for building the namespaced idempotency keys documented in
 * docs/services/notification-service/INTEGRATION.md.
 *
 *  - `chat:msg:{message_id}:notif:{channel}` — Phase 7.7 chat offline fallback
 *  - `template:publish:{template_id}:{revision_no}` — admin atomic publish
 *  - `trip:event:{trip_id}:{event_type}` — Kafka ingest dedup
 *  - `concom:reward:{workflow_id}:{task_id}` — Conductor task idempotency
 */
object IdempotencyKeys {
	fun chatOffline(messageId: UUID, channel: String): String = "chat:msg:$messageId:notif:$channel"
	fun templatePublish(templateId: UUID, revisionNo: Int): String = "template:publish:$templateId:$revisionNo"
	fun tripEvent(tripId: UUID, eventType: String): String = "trip:event:$tripId:$eventType"
	fun conductorTask(workflowId: String, taskId: String): String = "concom:reward:$workflowId:$taskId"
}