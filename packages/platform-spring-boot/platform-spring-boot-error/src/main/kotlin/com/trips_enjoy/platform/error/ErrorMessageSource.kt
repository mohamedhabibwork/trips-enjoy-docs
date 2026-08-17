package com.trips_enjoy.platform.error

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.support.ResourceBundleMessageSource

/**
 * i18n message source for the platform error envelope. Default locale EN,
 * with AR + FR bundles available.
 */
@Bean
@ConditionalOnMissingBean(name = ["errorMessageSource"])
internal fun errorMessageSource(): MessageSource =
    ResourceBundleMessageSource().apply {
        setBasename("errors")
        setDefaultEncoding("UTF-8")
        setUseCodeAsDefaultMessage(true)
    }
