package httpapi

import (
	"encoding/json"
	"net/http"
)

// openAPIHandler serves the minimal OpenAPI 3.1 catalog for the public
// surface (per docs/architecture/API_STANDARDS.md §18 — every service
// publishes its spec at /openapi.json). The full per-endpoint schemas
// land in a follow-up; this stub keeps the contract checkable.
func openAPIHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"openapi": "3.1.0",
			"info": map[string]string{
				"title":       "file-service",
				"version":     "1.0.0",
				"description": "file and media storage abstraction; driver-agnostic REST surface.",
			},
			"servers": []map[string]string{
				{"url": "/", "description": "same-origin"},
			},
			"paths": map[string]any{
				"/v1/files":                  fileEndpoint(),
				"/v1/files/{id}":             fileByIDEndpoints(),
				"/v1/files/{id}/upload":      map[string]any{"post": fileUploadOp()},
				"/v1/files/{id}/complete":    map[string]any{"post": fileCompleteOp()},
				"/v1/files/{id}/signed-url":  map[string]any{"post": fileSignedURLOp()},
				"/v1/files/{id}/download":    map[string]any{"get": fileDownloadOp()},
				"/v1/files/{id}/scan":        map[string]any{"get": fileScanOp()},
				"/v1/files/{id}/driver":      map[string]any{"get": fileDriverOp()},
				"/v1/admin/drivers":          map[string]any{"get": adminListDriversOp()},
				"/v1/admin/drivers/{id}/pin": map[string]any{"post": adminPinDriverOp()},
				"/v1/admin/migrations":       map[string]any{"post": adminEnqueueMigrationOp()},
				"/v1/admin/migrations/{id}":  map[string]any{"get": adminGetMigrationOp()},
				"/v1/admin/retention/run":    map[string]any{"post": adminRetentionRunOp()},
			},
			"components": map[string]any{
				"securitySchemes": map[string]any{
					"bearerAuth": map[string]any{
						"type":         "http",
						"scheme":       "bearer",
						"bearerFormat": "JWT",
					},
				},
			},
		})
	}
}

// docsHandler serves the Swagger UI entry point. It mirrors the api-gateway
// shell; the per-service catalog is loaded from /openapi.json.
func docsHandler(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte(`<!doctype html><html><head><link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"></head><body><div id="swagger-ui"></div><script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script><script>SwaggerUIBundle({url:"/openapi.json",dom_id:"#swagger-ui"});</script></body></html>`))
}

func fileEndpoint() map[string]any {
	return map[string]any{
		"post": map[string]any{
			"summary":     "Initiate an upload",
			"operationId": "initiateUpload",
			"requestBody": map[string]any{"required": true, "content": map[string]any{"application/json": map[string]any{"schema": map[string]string{"$ref": "#/components/schemas/InitiateUploadRequest"}}}},
			"responses":   fileResponses(),
			"security":    []map[string]any{{"bearerAuth": []string{}}},
		},
	}
}

func fileByIDEndpoints() map[string]any {
	return map[string]any{
		"get": map[string]any{
			"summary":     "Read file metadata",
			"operationId": "getFileMetadata",
			"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
			"responses":   fileResponses(),
			"security":    []map[string]any{{"bearerAuth": []string{}}},
		},
		"delete": map[string]any{
			"summary":     "Soft delete a file",
			"operationId": "softDeleteFile",
			"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
			"responses":   map[string]any{"204": map[string]any{"description": "no content"}},
			"security":    []map[string]any{{"bearerAuth": []string{}}},
		},
	}
}

func fileUploadOp() map[string]any {
	return map[string]any{
		"summary":     "Proxy-upload bytes to the resolved driver",
		"operationId": "proxyUpload",
		"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
		"requestBody": map[string]any{"required": true, "content": map[string]any{"multipart/form-data": map[string]any{"schema": map[string]string{"type": "string", "format": "binary"}}}},
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func fileCompleteOp() map[string]any {
	return map[string]any{
		"summary":     "Notify direct-to-driver upload completion",
		"operationId": "completeUpload",
		"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func fileSignedURLOp() map[string]any {
	return map[string]any{
		"summary":     "Issue a driver-signed URL",
		"operationId": "signedURL",
		"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func fileDownloadOp() map[string]any {
	return map[string]any{
		"summary":     "Download a file (proxy or redirect)",
		"operationId": "download",
		"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func fileScanOp() map[string]any {
	return map[string]any{
		"summary":     "Read the virus-scan result",
		"operationId": "getScan",
		"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func fileDriverOp() map[string]any {
	return map[string]any{
		"summary":     "Inspect the file's driver assignment",
		"operationId": "getDriverAssignment",
		"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func adminListDriversOp() map[string]any {
	return map[string]any{
		"summary":     "List configured drivers",
		"operationId": "listDrivers",
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func adminPinDriverOp() map[string]any {
	return map[string]any{
		"summary":     "Pin a file or owner to a driver",
		"operationId": "pinDriver",
		"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string"}}},
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func adminEnqueueMigrationOp() map[string]any {
	return map[string]any{
		"summary":     "Enqueue a driver migration",
		"operationId": "enqueueMigration",
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func adminGetMigrationOp() map[string]any {
	return map[string]any{
		"summary":     "Read migration status",
		"operationId": "getMigration",
		"parameters":  []map[string]any{{"name": "id", "in": "path", "required": true, "schema": map[string]string{"type": "string", "format": "uuid"}}},
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func adminRetentionRunOp() map[string]any {
	return map[string]any{
		"summary":     "Manually run a retention sweep",
		"operationId": "runRetention",
		"responses":   fileResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func fileResponses() map[string]any {
	return map[string]any{
		"200": map[string]any{"description": "OK"},
		"201": map[string]any{"description": "Created"},
		"204": map[string]any{"description": "No content"},
		"400": map[string]any{"description": "VALIDATION_FAILED"},
		"403": map[string]any{"description": "FORBIDDEN"},
		"404": map[string]any{"description": "NOT_FOUND"},
		"409": map[string]any{"description": "STATE_INVALID"},
		"422": map[string]any{"description": "BUSINESS_RULE_VIOLATION"},
		"503": map[string]any{"description": "DEPENDENCY_UNAVAILABLE"},
	}
}
