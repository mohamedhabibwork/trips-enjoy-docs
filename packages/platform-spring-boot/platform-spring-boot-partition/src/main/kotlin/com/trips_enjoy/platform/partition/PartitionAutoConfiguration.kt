package com.trips_enjoy.platform.partition

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the platform partition module.
 *
 * - Activated by default (`platform.partition.enabled=true`); disable
 *   per service by setting `platform.partition.enabled=false`.
 * - Registers [PartitionProperties] for `platform.partition.*` keys.
 * - Service / health-indicator beans are wrapped in
 *   [ConditionalOnMissingBean] so a service can override them locally.
 */
@Configuration
@EnableConfigurationProperties(PartitionProperties::class)
@ConditionalOnProperty(prefix = "platform.partition", name = ["enabled"], havingValue = "true", matchIfMissing = true)
open class PartitionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    open fun partitionMaintenanceService(
        dataSource: javax.sql.DataSource,
        properties: PartitionProperties,
    ): PartitionMaintenanceService = PartitionMaintenanceService(dataSource, properties)

    @Bean
    @ConditionalOnMissingBean(name = ["partitions"])
    open fun partitionHealthIndicator(
        dataSource: javax.sql.DataSource,
        properties: PartitionProperties,
    ): PartitionHealthIndicator = PartitionHealthIndicator(dataSource, properties)
}
