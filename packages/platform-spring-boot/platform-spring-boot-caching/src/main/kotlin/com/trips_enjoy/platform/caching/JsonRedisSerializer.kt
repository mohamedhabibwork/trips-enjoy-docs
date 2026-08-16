package com.trips_enjoy.platform.caching

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer

/**
 * Redis serializer that uses the platform's primary [ObjectMapper] so cached
 * payloads round-trip through the same JSON configuration as the HTTP layer.
 */
class JsonRedisSerializer(objectMapper: ObjectMapper) : GenericJackson2JsonRedisSerializer(objectMapper)
