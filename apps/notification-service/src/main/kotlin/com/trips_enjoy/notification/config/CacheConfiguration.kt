package com.trips_enjoy.notification.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

/**
 * Redis cache manager per docs/services/notification-service/TECH.md §3
 * ("Redis — dedup window, suppression rules, quiet hours") and
 * docs/shared/PLATFORM_BASELINE.md §caching.
 *
 *  - `notification-preferences` (5 min)
 *  - `notification-suppressions` (1 min)
 */
@Configuration
class CacheConfiguration {

	@Bean
	fun notificationCacheManager(
		connectionFactory: RedisConnectionFactory,
		mapper: ObjectMapper,
		@Value("\${notification-service.cache.preferences-ttl-seconds:300}") preferencesTtl: Long,
		@Value("\${notification-service.cache.suppressions-ttl-seconds:60}") suppressionsTtl: Long,
	): RedisCacheManager {
		val base = RedisCacheConfiguration.defaultCacheConfig()
			.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer()))
			.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(GenericJackson2JsonRedisSerializer(mapper)))
			.disableCachingNullValues()
		return RedisCacheManager.builder(connectionFactory)
			.cacheDefaults(base.entryTtl(Duration.ofMinutes(1)))
			.withCacheConfiguration("notification-preferences", base.entryTtl(Duration.ofSeconds(preferencesTtl)))
			.withCacheConfiguration("notification-suppressions", base.entryTtl(Duration.ofSeconds(suppressionsTtl)))
			.build()
	}
}