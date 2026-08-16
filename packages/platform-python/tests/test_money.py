from __future__ import annotations

import pytest

from platform_python.money import Money


def test_of_minor_and_display() -> None:
    m = Money.of_minor(1999, "USD")
    assert m.minor == 1999
    assert m.currency == "USD"
    assert m.major == m.__class__.of("19.99", "USD").major


def test_of_string_rounds() -> None:
    assert Money.of("19.99", "USD").minor == 1999
    assert Money.of("0.00", "USD").minor == 0
    assert Money.of("100.00", "USD").minor == 10000
    assert Money.of("-5.50", "USD").minor == -550


def test_mixed_currency_rejected() -> None:
    a = Money.of_minor(100, "USD")
    b = Money.of_minor(100, "EUR")
    with pytest.raises(ValueError):
        _ = a.plus(b)
    with pytest.raises(ValueError):
        _ = a.minus(b)


def test_arithmetic() -> None:
    a = Money.of_minor(1000, "USD")
    b = Money.of_minor(250, "USD")
    assert a.plus(b).minor == 1250
    assert a.minus(b).minor == 750
    assert a.times(3).minor == 3000
    assert a.div(2).minor == 500


def test_zero_fraction_currencies() -> None:
    m = Money.of("1234", "JPY")
    assert m.minor == 1234


def test_wire_format_round_trip() -> None:
    wire = {"amount": "19.99", "currency": "USD"}
    parsed = Money.model_validate(wire)
    assert parsed.minor == 1999
    # After validation, dump produces the canonical {minor, currency} shape
    dumped = parsed.model_dump()
    assert dumped["currency"] == "USD"
    assert dumped["minor"] == 1999
