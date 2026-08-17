package httpapi

import "time"

// timeTimeImpl is the actual implementation of the timeT alias. Kept in
// its own file so handlers_geocode.go doesn't need to import time.
type timeTimeImpl = time.Time

// timeTNow returns the current UTC time. Used by the
// handlers_geocode.go timeNow closure.
func timeTNow() time.Time { return time.Now().UTC() }
