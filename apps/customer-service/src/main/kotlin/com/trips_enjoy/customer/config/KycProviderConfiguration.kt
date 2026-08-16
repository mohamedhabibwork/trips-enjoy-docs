package com.trips_enjoy.customer.config

import com.trips_enjoy.customer.application.KycProviderStub
import com.trips_enjoy.customer.application.KycVerificationResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * Default KYC provider stub — returns the requested tier as the
 * "verified" tier for the v1 scaffold. A real provider (Onfido / Jumio)
 * lands via a follow-up that provides a class implementing the
 * [KycProviderStub] interface with HTTP wiring + credentials.
 */
@Configuration
class KycProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(KycProviderStub::class)
    fun kycProviderStub(): KycProviderStub =
        KycProviderStub { customerId, _, targetTier ->
            KycVerificationResult(
                verificationId = UUID.randomUUID(),
                verifiedTier = targetTier,
            )
        }
}
