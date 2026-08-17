package com.trips_enjoy.identity.config

import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import java.time.Duration

@Configuration
class CacheConfiguration {
    @Bean
    fun identityCacheConfiguration(connectionFactory: RedisConnectionFactory): CacheManager = RedisCacheManager.builder(connectionFactory)
        .withCacheConfiguration("identity-by-id", RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofSeconds(300)))
        .withCacheConfiguration("identity-by-subject", RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofSeconds(300)))
        .build()
}
