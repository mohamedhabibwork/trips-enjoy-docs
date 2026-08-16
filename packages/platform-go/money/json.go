package money

import "encoding/json"

// jsonImpl is a small indirection so test builds can swap the encoder
// if needed. Production uses encoding/json.
var jsonImpl = struct {
	Marshal   func(v any) ([]byte, error)
	Unmarshal func(data []byte, v any) error
}{
	Marshal:   json.Marshal,
	Unmarshal: json.Unmarshal,
}
