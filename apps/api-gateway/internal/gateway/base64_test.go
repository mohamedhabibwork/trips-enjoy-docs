package gateway

import "testing"

func TestBase64URLDecode(t *testing.T) {
	cases := []struct {
		in      string
		want    string
		wantErr bool
	}{
		{"aGVsbG8", "hello", false},
		{"aGVsbG8=", "hello", false},
		{"", "", true},
		{"!!!not-base64!!!", "", true},
	}
	for _, c := range cases {
		got, err := base64URLDecode(c.in)
		if c.wantErr {
			if err == nil {
				t.Errorf("%q: want error, got %q", c.in, string(got))
			}
			continue
		}
		if err != nil {
			t.Errorf("%q: %v", c.in, err)
			continue
		}
		if string(got) != c.want {
			t.Errorf("%q: got %q, want %q", c.in, string(got), c.want)
		}
	}
}
