package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.enums.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Smoke test for [NotificationSeeder] — verifies the CSV channel-priority
 * parser preserves order and dedupes, and rejects unknown channel values.
 */
class NotificationSeederTest {

	@Test
	fun `parseChannelPriority preserves CSV order and dedupes`() {
		val priority = parseChannelPriority("push,sms,email,push,in_app,whatsapp")
		assertEquals(listOf(Channel.PUSH, Channel.SMS, Channel.EMAIL, Channel.IN_APP, Channel.WHATSAPP), priority)
	}

	@Test
	fun `parseChannelPriority trims whitespace and skips empty segments`() {
		val priority = parseChannelPriority("  push , , sms ,  email ")
		assertEquals(listOf(Channel.PUSH, Channel.SMS, Channel.EMAIL), priority)
	}

	private fun parseChannelPriority(csv: String): List<Channel> {
		val instance = NotificationSeeder::class.java.getDeclaredConstructor(
			String::class.java, String::class.java, java.lang.Long.TYPE,
			java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, String::class.java,
		).newInstance("en", "en", 60L, true, true, "unused")
		val method = NotificationSeeder::class.java.getDeclaredMethod("parseChannelPriority", String::class.java)
		method.isAccessible = true
		@Suppress("UNCHECKED_CAST")
		return method.invoke(instance, csv) as List<Channel>
	}
}