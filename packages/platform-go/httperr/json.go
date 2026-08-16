package httperr

import (
	"encoding/json"
	"net/http"
)

func newJSONEncoder(w http.ResponseWriter) *json.Encoder {
	return json.NewEncoder(w)
}
