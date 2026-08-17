package com.trips_enjoy.platform.caching

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

@ConfigurationProperties("platform.cache")
data class CacheProperties(
    val defaultTtlSeconds: Long = 30,
    val keyPrefix: String = "",
)

@Configuration
@EnableConfigurationProperties(CacheProperties::class)
internal class RedisConfig {

    @Bean
    @ConditionalOnMissingBean(name = ["platformRedisTemplate"])
    fun platformRedisTemplate(
        connectionFactory: RedisConnectionFactory,
        objectMapper: ObjectMapper,
    ): RedisTemplate<String, Any> {
        val template = RedisTemplate<String, Any>()
        template.connectionFactory = connectionFactory
        template.keySerializer = StringRedisSerializer()
        template.hashKeySerializer = StringRedisSerializer()
        val valueSerializer = JsonRedisSerializer(objectMapper)
        template.valueSerializer = valueSerializer
        template.hashValueSerializer = valueSerializer
        template.afterPropertiesSet()
        return template
    }

    @Bean
    @ConditionalOnMissingBean(name = ["platformObjectMapperJsonRedisSerializer"])
    fun platformObjectMapperJsonRedisSerializer(objectMapper: ObjectMapper): GenericJackson2JsonRedisSerializer =
        GenericJackson2JsonRedisSerializer(objectMapper)

    @Bean
    fun defaultCacheTtl(properties: CacheProperties): Duration = Duration.ofSeconds(properties.defaultTtlSeconds)

    @Bean
    fun cacheKeyPrefix(properties: CacheProperties): String = properties.keyPrefix
}
