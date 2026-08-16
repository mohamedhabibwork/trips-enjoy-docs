"""Money value type shared by Python services.

Wire JSON: ``{"amount": "19.99", "currency": "USD"}``. Arithmetic is
performed on minor units (Long / int64) to avoid floating-point drift.
Mixed-currency arithmetic is rejected at runtime.
"""
from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal, getcontext
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, model_validator

# Default precision: 28 digits is more than enough for any ISO 4217 amount.
getcontext().prec = 28

# ISO 4217 currencies with 0 or 3 fractional digits.
_ZERO_FRACTION_CURRENCIES = frozenset({"JPY", "KRW", "VND", "CLP", "PYG", "UGX", "XAF", "XOF"})
_THREE_FRACTION_CURRENCIES = frozenset({"BHD", "JOD", "KWD", "OMR", "TND"})


def _fraction_digits(currency: str) -> int:
    if currency in _ZERO_FRACTION_CURRENCIES:
        return 0
    if currency in _THREE_FRACTION_CURRENCIES:
        return 3
    return 2


class Money(BaseModel):
    """Value type holding minor units + ISO 4217 currency."""

    model_config = ConfigDict(frozen=True)

    minor: int
    currency: Annotated[str, Field(min_length=3, max_length=3)]

    @classmethod
    def zero(cls, currency: str) -> "Money":
        return cls(minor=0, currency=currency)

    @classmethod
    def of_minor(cls, minor: int, currency: str) -> "Money":
        return cls(minor=minor, currency=currency)

    @classmethod
    def of(cls, amount: str | Decimal, currency: str) -> "Money":
        if isinstance(amount, str):
            amount = Decimal(amount)
        frac = _fraction_digits(currency)
        scale = Decimal(10) ** frac
        quant = (amount * scale).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
        return cls(minor=int(quant), currency=currency)

    @property
    def major(self) -> Decimal:
        frac = _fraction_digits(self.currency)
        scale = Decimal(10) ** frac
        return Decimal(self.minor) / scale

    def plus(self, other: "Money") -> "Money":
        self._assert_same_currency(other)
        return Money(minor=self.minor + other.minor, currency=self.currency)

    def minus(self, other: "Money") -> "Money":
        self._assert_same_currency(other)
        return Money(minor=self.minor - other.minor, currency=self.currency)

    def times(self, multiplier: int) -> "Money":
        return Money(minor=self.minor * multiplier, currency=self.currency)

    def div(self, divisor: int) -> "Money":
        return Money(minor=self.minor // divisor, currency=self.currency)

    def _assert_same_currency(self, other: "Money") -> None:
        if self.currency != other.currency:
            raise ValueError(f"money: mixed currencies {self.currency} and {other.currency}")

    @model_validator(mode="before")
    @classmethod
    def _from_wire(cls, data: Any) -> Any:
        """Accept wire format ``{"amount": "...", "currency": "..."}`` or the
        canonical ``{"minor": ..., "currency": ...}`` shape."""
        if isinstance(data, dict) and "amount" in data and "minor" not in data:
            amount = data["amount"]
            currency = data["currency"]
            return cls.of(amount, currency).model_dump()
        return data


class MoneyHTTPException(Exception):
    """Convenience exception carrying a fully built Money for HTTP responses."""

    def __init__(self, money: Money) -> None:
        self.money = money
        super().__init__(f"{money.major} {money.currency}")
