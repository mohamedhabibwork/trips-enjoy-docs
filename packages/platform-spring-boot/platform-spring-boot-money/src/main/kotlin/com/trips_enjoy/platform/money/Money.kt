package com.trips_enjoy.platform.money

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * Money value class. Stores amounts in **minor units** (Long) so all
 * arithmetic is integer-based and avoids floating-point drift.
 *
 * Arithmetic rejects mixed currencies at runtime — `USD + EUR` throws.
 * Currency is ISO 4217. Companion factory methods accept either Strings
 * (display form, e.g. `"19.99"`) or minor-unit Longs.
 *
 * Wire JSON: `{"amount":"19.99","currency":"USD"}` — minor units are
 * not exposed externally; the serializer converts at the boundary.
 *
 * Implementation note: `value class` must wrap a single value. We
 * therefore wrap a `Long` that encodes both the minor unit (lower 48
 * bits) and a self-incrementing currency index. The default companion
 * factory methods produce the canonical Money pair (minor, currency).
 */
@JvmInline
value class Money private constructor(val packed: Long) {

    val minor: Long get() = packed and MINOR_MASK
    val currency: Currency get() = CURRENCIES[(packed ushr CURRENCY_SHIFT).toInt()]

    operator fun plus(other: Money): Money {
        require(other.currency == currency) {
            "Mixed currencies: $currency + ${other.currency}"
        }
        return Money(packed + (other.packed and MINOR_MASK))
    }

    operator fun minus(other: Money): Money {
        require(other.currency == currency) {
            "Mixed currencies: $currency - ${other.currency}"
        }
        return Money(packed - (other.packed and MINOR_MASK))
    }

    operator fun times(multiplier: Long): Money = Money(packed * multiplier)
    operator fun times(multiplier: Int): Money = Money(packed * multiplier)
    operator fun div(divisor: Long): Money = Money(packed / divisor)

    fun toBigDecimal(): BigDecimal =
        BigDecimal(minor).movePointLeft(currency.defaultFractionDigits)
            .setScale(currency.defaultFractionDigits, RoundingMode.UNNECESSARY)

    override fun toString(): String = "${toBigDecimal()} ${currency.currencyCode}"

    @get:JsonValue
    val json: Map<String, String>
        get() = mapOf(
            "amount" to toBigDecimal().toPlainString(),
            "currency" to currency.currencyCode,
        )

    companion object {
        private const val MINOR_BITS = 48
        private const val CURRENCY_SHIFT = MINOR_BITS
        private const val MINOR_MASK: Long = (1L shl MINOR_BITS) - 1L

        private val CURRENCIES: MutableList<Currency> = mutableListOf()
        private val CURRENCY_INDEX: MutableMap<String, Int> = mutableMapOf()

        @Synchronized
        private fun index(currency: Currency): Int {
            val code = currency.currencyCode
            return CURRENCY_INDEX.getOrPut(code) {
                val idx = CURRENCIES.size
                CURRENCIES.add(currency)
                idx
            }
        }

        val ZERO_USD: Money = ofMinor(0, "USD")

        fun ofMinor(minor: Long, currency: String): Money {
            val cur = Currency.getInstance(currency)
            val idx = index(cur).toLong()
            return Money((idx shl CURRENCY_SHIFT) or (minor and MINOR_MASK))
        }

        fun of(amount: BigDecimal, currency: String): Money {
            val cur = Currency.getInstance(currency)
            val scaled = amount.setScale(cur.defaultFractionDigits, RoundingMode.UNNECESSARY)
            val minor = scaled.movePointRight(cur.defaultFractionDigits).toLong()
            return ofMinor(minor, currency)
        }

        fun of(amount: String, currency: String): Money = of(BigDecimal(amount), currency)

        @JvmStatic
        @JsonCreator
        fun fromJson(
            @JsonProperty("amount") amount: String,
            @JsonProperty("currency") currency: String,
        ): Money = of(amount, currency)
    }
}

fun BigDecimal.toMoney(currency: String): Money = Money.of(this, currency)
fun Long.toMoney(currency: String): Money = Money.ofMinor(this, currency)
