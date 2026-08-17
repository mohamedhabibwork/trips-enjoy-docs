package admin

import "encoding/json"

// jsonMarshal is forwarded to encoding/json so service.go doesn't need
// to import encoding/json directly. Kept in a separate file so the
// package surface stays organized.
func jsonMarshal(v any) ([]byte, error) { return json.Marshal(v) }
