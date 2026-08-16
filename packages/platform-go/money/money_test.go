package money

import "testing"

func TestOfMinorAndDisplay(t *testing.T) {
	m := OfMinor(1999, "USD")
	if m.Major() != "19.99" {
		t.Errorf("Major = %q", m.Major())
	}
}

func TestOfFromString(t *testing.T) {
	cases := []struct {
		amount string
		minor  int64
	}{
		{"19.99", 1999},
		{"0.00", 0},
		{"100.00", 10000},
		{"-5.50", -550},
	}
	for _, c := range cases {
		m, err := Of(c.amount, "USD")
		if err != nil {
			t.Fatalf("Of(%q) error: %v", c.amount, err)
		}
		if m.Minor != c.minor {
			t.Errorf("Of(%q) Minor = %d, want %d", c.amount, m.Minor, c.minor)
		}
	}
}

func TestMixedCurrencyRejection(t *testing.T) {
	a := OfMinor(100, "USD")
	b := OfMinor(100, "EUR")
	if _, err := a.Plus(b); err == nil {
		t.Error("expected mixed-currency error")
	}
}

func TestArithmetic(t *testing.T) {
	a := OfMinor(1000, "USD")
	b := OfMinor(250, "USD")
	c, _ := a.Plus(b)
	if c.Minor != 1250 {
		t.Errorf("Plus = %d", c.Minor)
	}
	d, _ := a.Minus(b)
	if d.Minor != 750 {
		t.Errorf("Minus = %d", d.Minor)
	}
	if a.Times(3).Minor != 3000 {
		t.Errorf("Times = %d", a.Times(3).Minor)
	}
	if a.Div(2).Minor != 500 {
		t.Errorf("Div = %d", a.Div(2).Minor)
	}
}

func TestZeroFractionCurrencies(t *testing.T) {
	m, err := Of("1234", "JPY")
	if err != nil {
		t.Fatalf("Of error: %v", err)
	}
	if m.Minor != 1234 {
		t.Errorf("JPY Minor = %d", m.Minor)
	}
}
