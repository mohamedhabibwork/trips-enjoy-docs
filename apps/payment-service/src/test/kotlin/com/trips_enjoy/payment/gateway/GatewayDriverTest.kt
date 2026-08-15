package com.trips_enjoy.payment.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for the PaymentGatewayDriver port + the 6 real adapters +
 * the no-op fallback. Covers:
 *   * authorize returns a deterministic gateway intent id
 *   * capture returns the captured amount
 *   * void returns the gateway void id
 *   * refund returns the gateway refund id
 *   * webhook signature "valid:" prefix is accepted, anything else throws
 *   * NoOpGatewayDriver rejects every operation with GatewayNotConfiguredException
 *   * SupportedGateways.ALL_GATEWAY_IDS has exactly 46 ids
 *   * SupportedGateways.REAL_DRIVER_IDS has exactly 6 ids
 *   * Real drivers report HEALTHY
 *   * No-op drivers report UNREACHABLE
 */
class GatewayDriverTest {

    @Test
    fun `stripe driver has gatewayId stripe`() {
        assertEquals("stripe", StripeDriver().gatewayId)
    }

    @Test
    fun `paypal driver has gatewayId paypal`() {
        assertEquals("paypal", PaypalDriver().gatewayId)
    }

    @Test
    fun `binance driver has gatewayId binance`() {
        assertEquals("binance", BinanceDriver().gatewayId)
    }

    @Test
    fun `paymob driver has gatewayId paymob`() {
        assertEquals("paymob", PaymobDriver().gatewayId)
    }

    @Test
    fun `now_payments driver has gatewayId now_payments`() {
        assertEquals("now_payments", NowPaymentsDriver().gatewayId)
    }

    @Test
    fun `manual driver has gatewayId manual`() {
        assertEquals("manual", ManualDriver().gatewayId)
    }

    @Test
    fun `supported gateways catalog has exactly 46 entries`() {
        assertEquals(46, SupportedGateways.ALL_GATEWAY_IDS.size)
    }

    @Test
    fun `real driver catalog has exactly 6 entries`() {
        assertEquals(6, SupportedGateways.REAL_DRIVER_IDS.size)
    }

    @Test
    fun `supported gateways contains all 6 real driver ids`() {
        for (id in SupportedGateways.REAL_DRIVER_IDS) {
            assertTrue(
                SupportedGateways.ALL_GATEWAY_IDS.contains(id),
                "expected $id in catalog",
            )
        }
    }

    @Test
    fun `driverFor resolves real driver for stripe`() {
        val driver = SupportedGateways.driverFor("stripe")
        assertEquals("stripe", driver.gatewayId)
        assertTrue(driver is StripeDriver)
    }

    @Test
    fun `driverFor resolves no-op for unconfigured gateway`() {
        val driver = SupportedGateways.driverFor("adyen")
        assertTrue(driver is NoOpGatewayDriver)
        assertEquals("adyen", driver.gatewayId)
    }

    @Test
    fun `stripe authorize returns deterministic gateway intent id`() {
        val driver = StripeDriver()
        val intentId = UUID.randomUUID()
        val req = AuthorizeRequest(
            paymentIntentId = intentId,
            customerId = UUID.randomUUID(),
            amountMinor = 2350L,
            currency = "EUR",
            gatewayToken = "tok_test",
            gatewayRegion = "eu-west",
            captureMode = "manual",
            correlationId = UUID.randomUUID(),
            idempotencyKey = "idem_$intentId",
        )
        val result = driver.authorize(req)
        assertNotNull(result.gatewayIntentId)
        assertTrue(result.gatewayIntentId.startsWith("stripe_"))
        assertEquals("requires_capture", result.rawResponse["status"])
    }

    @Test
    fun `stripe capture returns full amount`() {
        val driver = StripeDriver()
        val intentId = UUID.randomUUID()
        val req = CaptureRequest(
            paymentIntentId = intentId,
            gatewayIntentId = "stripe_${intentId.toString().take(12)}",
            amountMinor = 2350L,
            currency = "EUR",
            correlationId = UUID.randomUUID(),
            idempotencyKey = "cap_$intentId",
        )
        val result = driver.capture(req)
        assertEquals(2350L, result.capturedMinor)
        assertTrue(result.gatewayCaptureId.startsWith("cap_"))
    }

    @Test
    fun `stripe void returns gateway void id`() {
        val driver = StripeDriver()
        val intentId = UUID.randomUUID()
        val req = VoidRequest(
            paymentIntentId = intentId,
            gatewayIntentId = "stripe_${intentId.toString().take(12)}",
            correlationId = UUID.randomUUID(),
            idempotencyKey = "void_$intentId",
            reason = "customer_cancelled",
        )
        val result = driver.void(req)
        assertTrue(result.gatewayVoidId.startsWith("void_"))
        assertEquals("canceled", result.rawResponse["status"])
    }

    @Test
    fun `stripe refund returns gateway refund id`() {
        val driver = StripeDriver()
        val intentId = UUID.randomUUID()
        val req = RefundRequest(
            paymentIntentId = intentId,
            gatewayIntentId = "stripe_${intentId.toString().take(12)}",
            gatewayCaptureId = "cap_${intentId.toString().take(12)}",
            refundAmountMinor = 1000L,
            currency = "EUR",
            correlationId = UUID.randomUUID(),
            idempotencyKey = "re_$intentId",
        )
        val result = driver.refund(req)
        assertEquals(1000L, result.refundedMinor)
        assertTrue(result.gatewayRefundId.startsWith("re_"))
    }

    @Test
    fun `webhook signature with valid prefix is accepted`() {
        val driver = StripeDriver()
        val result = driver.verifyWebhook(
            payload = "{\"id\":\"evt_test\"}".toByteArray(),
            signature = "valid:signature_value",
            headers = mapOf(
                "X-Gateway-Event-Id" to "evt_test",
                "X-Gateway-Event-Type" to "payment_intent.succeeded",
            ),
        )
        assertEquals("evt_test", result.gatewayEventId)
        assertEquals("payment_intent.succeeded", result.eventType)
    }

    @Test
    fun `webhook signature without valid prefix throws`() {
        val driver = StripeDriver()
        assertThrows(InvalidWebhookSignatureException::class.java) {
            driver.verifyWebhook(
                payload = "{\"id\":\"evt_test\"}".toByteArray(),
                signature = "bogus",
                headers = mapOf("X-Gateway-Event-Id" to "evt_test"),
            )
        }
    }

    @Test
    fun `no-op driver rejects authorize with GatewayNotConfiguredException`() {
        val driver = NoOpGatewayDriver("adyen")
        val ex = assertThrows(GatewayNotConfiguredException::class.java) {
            driver.authorize(
                AuthorizeRequest(
                    paymentIntentId = UUID.randomUUID(),
                    customerId = UUID.randomUUID(),
                    amountMinor = 100L,
                    currency = "USD",
                    gatewayToken = "tok",
                    gatewayRegion = "us-east",
                    captureMode = "auto",
                    correlationId = UUID.randomUUID(),
                    idempotencyKey = "idem",
                ),
            )
        }
        assertTrue(ex.message!!.contains("adyen"))
    }

    @Test
    fun `no-op driver rejects capture`() {
        assertThrows(GatewayNotConfiguredException::class.java) {
            NoOpGatewayDriver("adyen").capture(
                CaptureRequest(
                    paymentIntentId = UUID.randomUUID(),
                    gatewayIntentId = "x",
                    amountMinor = 100L,
                    currency = "USD",
                    correlationId = UUID.randomUUID(),
                    idempotencyKey = "x",
                ),
            )
        }
    }

    @Test
    fun `no-op driver rejects void`() {
        assertThrows(GatewayNotConfiguredException::class.java) {
            NoOpGatewayDriver("adyen").void(
                VoidRequest(
                    paymentIntentId = UUID.randomUUID(),
                    gatewayIntentId = "x",
                    correlationId = UUID.randomUUID(),
                    idempotencyKey = "x",
                ),
            )
        }
    }

    @Test
    fun `no-op driver rejects refund`() {
        assertThrows(GatewayNotConfiguredException::class.java) {
            NoOpGatewayDriver("adyen").refund(
                RefundRequest(
                    paymentIntentId = UUID.randomUUID(),
                    gatewayIntentId = "x",
                    gatewayCaptureId = "x",
                    refundAmountMinor = 100L,
                    currency = "USD",
                    correlationId = UUID.randomUUID(),
                    idempotencyKey = "x",
                ),
            )
        }
    }

    @Test
    fun `real drivers report healthy`() {
        assertEquals(GatewayHealth.HEALTHY, StripeDriver().health())
        assertEquals(GatewayHealth.HEALTHY, PaypalDriver().health())
        assertEquals(GatewayHealth.HEALTHY, BinanceDriver().health())
        assertEquals(GatewayHealth.HEALTHY, PaymobDriver().health())
        assertEquals(GatewayHealth.HEALTHY, NowPaymentsDriver().health())
        assertEquals(GatewayHealth.HEALTHY, ManualDriver().health())
    }

    @Test
    fun `no-op drivers report unreachable`() {
        assertEquals(GatewayHealth.UNREACHABLE, NoOpGatewayDriver("adyen").health())
        assertEquals(GatewayHealth.UNREACHABLE, NoOpGatewayDriver("binance").health())
    }

    @Test
    fun `authorize result timestamps are set`() {
        val driver = StripeDriver()
        val req = AuthorizeRequest(
            paymentIntentId = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            amountMinor = 100L,
            currency = "USD",
            gatewayToken = "tok",
            gatewayRegion = "us",
            captureMode = "manual",
            correlationId = UUID.randomUUID(),
            idempotencyKey = "x",
        )
        val before = System.currentTimeMillis()
        val result = driver.authorize(req)
        val after = System.currentTimeMillis()
        assertNotNull(result.authorizedAt)
        val ts = result.authorizedAt.toEpochMilli()
        assert(ts in before..after)
    }
}