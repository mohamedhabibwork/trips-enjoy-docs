// Package money defines the platform Money value type shared by Go
// services. Mirrors the Kotlin `Money` (@JvmInline value class) in
// `platform-spring-boot-money` and the Python `Money` model in
// `platform-python`. Wire JSON: `{"amount": "19.99", "currency": "USD"}`.
// All arithmetic is performed on minor units (Long / int64) to avoid
// floating-point drift. Mixed-currency arithmetic is rejected at
// runtime.
package money

import (
	"errors"
	"fmt"
	"math/big"
)

// Money is a value type carrying `minor` (Long minor units) and
// `currency` (ISO 4217 code).
type Money struct {
	Minor    int64  `json:"-"`
	Currency string `json:"-"`
}

// Amount is the display-form representation, used for wire JSON.
type Amount struct {
	Amount   string `json:"amount"`
	Currency string `json:"currency"`
}

// Zero returns the zero amount for a given currency.
func Zero(currency string) Money { return Money{Minor: 0, Currency: currency} }

// OfMinor constructs a Money from minor units (e.g. cents).
func OfMinor(minor int64, currency string) Money { return Money{Minor: minor, Currency: currency} }

// Of constructs a Money from a display-form amount string (e.g. "19.99").
// The number of fractional digits is determined by the ISO 4217 currency.
func Of(amount string, currency string) (Money, error) {
	rat, ok := new(big.Rat).SetString(amount)
	if !ok {
		return Money{}, fmt.Errorf("money: invalid amount %q", amount)
	}
	frac := defaultFractionDigits(currency)
	scale := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(frac)), nil)
	rat.Mul(rat, new(big.Rat).SetInt(scale))
	minor := new(big.Int).Div(rat.Num(), rat.Denom())
	if !rat.IsInt() {
		// rounded to nearest
		num2 := new(big.Int).Mul(rat.Num(), big.NewInt(2))
		denom2 := rat.Denom()
		half := new(big.Int).Div(denom2, big.NewInt(2))
		if num2.Cmp(new(big.Int).Mul(denom2, minor)) >= 0 {
			// round up
			mod := new(big.Int).Mod(num2, denom2)
			if mod.Cmp(zero) != 0 {
				minor.Add(minor, big.NewInt(1))
			}
		}
		_ = half
	}
	if !minor.IsInt64() {
		return Money{}, errors.New("money: minor overflow")
	}
	return Money{Minor: minor.Int64(), Currency: currency}, nil
}

// Major returns the display-form big.Rat (e.g. 19.99).
func (m Money) Major() string {
	frac := defaultFractionDigits(m.Currency)
	scale := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(frac)), nil)
	num := new(big.Int).Mul(big.NewInt(m.Minor), big.NewInt(1))
	rat := new(big.Rat).SetFrac(num, scale)
	return rat.FloatString(frac)
}

// UnmarshalJSON parses the wire format {"amount": "...", "currency": "..."}.
func (m *Money) UnmarshalJSON(data []byte) error {
	var a Amount
	if err := jsonUnmarshal(data, &a); err != nil {
		return err
	}
	parsed, err := Of(a.Amount, a.Currency)
	if err != nil {
		return err
	}
	*m = parsed
	return nil
}

// MarshalJSON renders the wire format.
func (m Money) MarshalJSON() ([]byte, error) {
	return jsonMarshal(Amount{Amount: m.Major(), Currency: m.Currency})
}

func (m Money) Plus(other Money) (Money, error) {
	if m.Currency != other.Currency {
		return Money{}, fmt.Errorf("money: mixed currencies %s + %s", m.Currency, other.Currency)
	}
	return Money{Minor: m.Minor + other.Minor, Currency: m.Currency}, nil
}

func (m Money) Minus(other Money) (Money, error) {
	if m.Currency != other.Currency {
		return Money{}, fmt.Errorf("money: mixed currencies %s - %s", m.Currency, other.Currency)
	}
	return Money{Minor: m.Minor - other.Minor, Currency: m.Currency}, nil
}

func (m Money) Times(multiplier int64) Money {
	return Money{Minor: m.Minor * multiplier, Currency: m.Currency}
}

func (m Money) Div(divisor int64) Money {
	return Money{Minor: m.Minor / divisor, Currency: m.Currency}
}

func defaultFractionDigits(currency string) int {
	switch currency {
	case "JPY", "KRW", "VND", "CLP", "PYG", "UGX", "XAF", "XOF":
		return 0
	case "BHD", "JOD", "KWD", "OMR", "TND":
		return 3
	default:
		return 2
	}
}

var zero = big.NewInt(0)

// jsonUnmarshal and jsonMarshal are split out so the dependency on
// encoding/json doesn't bleed into a file that is mostly arithmetic.
func jsonUnmarshal(data []byte, v any) error { return jsonImpl.Unmarshal(data, v) }
func jsonMarshal(v any) ([]byte, error)       { return jsonImpl.Marshal(v) }
