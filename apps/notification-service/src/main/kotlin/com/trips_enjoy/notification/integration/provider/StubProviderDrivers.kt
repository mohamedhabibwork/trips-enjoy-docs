package com.trips_enjoy.notification.integration.provider

import com.trips_enjoy.notification.domain.enums.Channel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * One per channel. Logging + deterministic synthetic provider_message_id.
 *
 * In production these are replaced with the real Twilio / Meta Cloud /
 * FCM / SMTP clients. The interface stays identical so application code
 * doesn't change.
 */

@Component
class NoopPushProvider : ProviderDriver {
	override val name = "noop-push"
	override val channel = Channel.PUSH
	override fun send(request: SendRequest): SendResult {
		log.info("noop push send template={} dedup={} correlation={}",
			request.templateName, request.dedupKey, request.correlationId)
		return SendResult(success = true, providerMessageId = "fcm_${UUID.randomUUID()}", rawStatusCode = 200)
	}
	private val log = LoggerFactory.getLogger(javaClass)
}

@Component
class NoopSmsProvider : ProviderDriver {
	override val name = "noop-sms"
	override val channel = Channel.SMS
	override fun send(request: SendRequest): SendResult {
		log.info("noop sms send template={} dedup={} correlation={}",
			request.templateName, request.dedupKey, request.correlationId)
		return SendResult(success = true, providerMessageId = "sms_${UUID.randomUUID()}", rawStatusCode = 200)
	}
	private val log = LoggerFactory.getLogger(javaClass)
}

@Component
class NoopEmailProvider : ProviderDriver {
	override val name = "noop-email"
	override val channel = Channel.EMAIL
	override fun send(request: SendRequest): SendResult {
		log.info("noop email send template={} subject={} dedup={} correlation={}",
			request.templateName, request.subject, request.dedupKey, request.correlationId)
		return SendResult(success = true, providerMessageId = "em_${UUID.randomUUID()}", rawStatusCode = 202)
	}
	private val log = LoggerFactory.getLogger(javaClass)
}

@Component
class NoopInAppProvider : ProviderDriver {
	override val name = "noop-in_app"
	override val channel = Channel.IN_APP
	override fun send(request: SendRequest): SendResult {
		log.info("noop in_app send template={} dedup={} correlation={}",
			request.templateName, request.dedupKey, request.correlationId)
		return SendResult(success = true, providerMessageId = "ia_${UUID.randomUUID()}", rawStatusCode = 200)
	}
	private val log = LoggerFactory.getLogger(javaClass)
}

/** Returns a Meta Cloud–shaped `wamid.HBgN...` id so callers can recognise the stub. */
@Component
class NoopWhatsappProvider : ProviderDriver {
	override val name = "noop-whatsapp"
	override val channel = Channel.WHATSAPP
	override fun send(request: SendRequest): SendResult {
		val synthetic = "wamid.HBgN" + UUID.randomUUID().toString().replace("-", "").take(20)
		log.info("noop whatsapp send template={} provider_tpl_id={} provider_lang={} vars={} correlation={}",
			request.templateName, request.providerTemplateId, request.providerTemplateLanguage,
			request.whatsappVariables.keys, request.correlationId)
		return SendResult(success = true, providerMessageId = synthetic, rawStatusCode = 202)
	}
	private val log = LoggerFactory.getLogger(javaClass)
}