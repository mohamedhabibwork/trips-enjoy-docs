package zones

import "math"

// cosRef / sqrtRef are forwarded to the math package so the lookup
// file does not need a math import directly.
func cosRef(x float64) float64  { return math.Cos(x) }
func sqrtRef(x float64) float64 { return math.Sqrt(x) }
