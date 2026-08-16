package admin

import "time"

// _now returns the current unix-second; kept in a separate file so the
// hmac.go surface stays free of time-package imports (which keeps the
// HMAC verification pure and easy to test).
func _now() int64 { return time.Now().Unix() }
