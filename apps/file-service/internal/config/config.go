// Package config loads file-service configuration from the FILE_SERVICE_*
// environment variables described in .env.example and PLATFORM_BASELINE.md.
// Required values fail-fast at Load(); optional values fall back to safe
// dev defaults so the binary boots offline.
package config

import (
	"errors"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is the resolved, immutable view of file-service configuration.
// All fields are populated by Load() at process start; the struct is
// passed by value to every package that needs settings.
type Config struct {
	// Platform
	PlatformEnv string // dev | stg | prod

	// Server
	PublicPort string // :8080 mux per INTEGRATION.md (default 8084 to match the gateway route table)
	AdminPort  string // :8081 admin mux per TECH.md §10.4 (default 8081)

	// Database
	DBURL      string // postgres://...?options=-c search_path=file,public
	DBUsername string
	DBPassword string

	// Redis (signed-URL cache; optional in dev)
	RedisHost     string
	RedisPort     string
	RedisPassword string

	// Kafka (event publisher; optional in dev — falls back to stdout publisher)
	KafkaBootstrapServers string

	// Kafka topic map (per INTEGRATION.md §3)
	TopicFileUploaded string
	TopicFileScanned  string
	TopicFileDeleted  string
	TopicFileMigrated string

	// Keycloak / auth
	KeycloakJWKSURI   string
	KeycloakIssuerURI string

	// local_fs driver root (per TECH.md §13.1)
	LocalFSRoot string

	// HMAC secret for admin overrides
	HMACSecret string

	// OpenTelemetry
	OTLPEndpoint string
	ServiceName  string

	// Driver catalog defaults (loaded by the storage registry)
	MaxUploadSizeBytes   int64
	SyncScanMaxSizeBytes int64
	DefaultSignedURLTTL  time.Duration

	// ---- Real Storage Driver config (per SRS FR-031, TECH.md §2) ----

	// S3 (AWS / S3-compatible: MinIO / R2 / RGW)
	S3Enabled   bool
	S3Region    string
	S3Endpoint  string // empty = AWS native
	S3Bucket    string
	S3AccessKey string
	S3SecretKey string
	S3PathStyle bool
	S3KMSKeyID  string

	// Azure Blob Storage
	AzureBlobEnabled    bool
	AzureBlobAccount    string
	AzureBlobAccountKey string
	AzureBlobContainer  string
	AzureBlobEndpoint   string // empty = default <account>.blob.core.windows.net

	// Oracle Object Storage (native REST or S3-compatible)
	OCIEnabled       bool
	OCINamespace     string
	OCIRegion        string
	OCIBucket        string
	OCICompartmentID string
	OCIS3Compatible  bool
	OCIS3Endpoint    string
	OCIAccessKey     string
	OCISecretKey     string

	// Google Cloud Storage
	GCSEnabled             bool
	GCSBucket              string
	GCSServiceAccountEmail string
	GCSPrivateKeyPEM       string // the PEM-encoded RSA private key for SignedURL
}

// Load reads environment variables and returns a Config. Required variables
// (DBURL) must be present; missing them returns an error so the binary fails
// fast per PLATFORM_BASELINE.md §8.
func Load() (Config, error) {
	cfg := Config{
		PlatformEnv:           valueOrDefault(os.Getenv("PLATFORM_ENV"), "dev"),
		PublicPort:            valueOrDefault(os.Getenv("FILE_SERVICE_PUBLIC_PORT"), "8084"),
		AdminPort:             valueOrDefault(os.Getenv("FILE_SERVICE_ADMIN_PORT"), "8081"),
		DBURL:                 os.Getenv("FILE_SERVICE_DB_URL"),
		DBUsername:            valueOrDefault(os.Getenv("FILE_SERVICE_DB_USERNAME"), "postgres"),
		DBPassword:            os.Getenv("FILE_SERVICE_DB_PASSWORD"),
		RedisHost:             valueOrDefault(os.Getenv("FILE_SERVICE_REDIS_HOST"), "0.0.0.0"),
		RedisPort:             valueOrDefault(os.Getenv("FILE_SERVICE_REDIS_PORT"), "6379"),
		RedisPassword:         os.Getenv("FILE_SERVICE_REDIS_PASSWORD"),
		KafkaBootstrapServers: os.Getenv("FILE_SERVICE_KAFKA_BOOTSTRAP_SERVERS"),
		TopicFileUploaded:     valueOrDefault(os.Getenv("FILE_SERVICE_KAFKA_TOPIC_FILE_UPLOADED"), "file.file.uploaded"),
		TopicFileScanned:      valueOrDefault(os.Getenv("FILE_SERVICE_KAFKA_TOPIC_FILE_SCANNED"), "file.file.scanned"),
		TopicFileDeleted:      valueOrDefault(os.Getenv("FILE_SERVICE_KAFKA_TOPIC_FILE_DELETED"), "file.file.deleted"),
		TopicFileMigrated:     valueOrDefault(os.Getenv("FILE_SERVICE_KAFKA_TOPIC_FILE_MIGRATED"), "file.file.migrated"),
		KeycloakJWKSURI:       os.Getenv("FILE_SERVICE_KEYCLOAK_JWKS_URI"),
		KeycloakIssuerURI:     os.Getenv("FILE_SERVICE_KEYCLOAK_ISSUER_URI"),
		LocalFSRoot:           valueOrDefault(os.Getenv("FILE_SERVICE_LOCAL_FS_ROOT"), "/tmp/trips-enjoy-file-dev"),
		HMACSecret:            valueOrDefault(os.Getenv("FILE_SERVICE_HMAC_SECRET"), "local-dev-hmac-secret-change-me"),
		OTLPEndpoint:          os.Getenv("FILE_SERVICE_OTEL_EXPORTER_OTLP_ENDPOINT"),
		ServiceName:           valueOrDefault(os.Getenv("FILE_SERVICE_OTEL_SERVICE_NAME"), "file-service"),
		MaxUploadSizeBytes:    int64OrDefault(os.Getenv("FILE_SERVICE_MAX_UPLOAD_SIZE_BYTES"), 100*1024*1024),
		SyncScanMaxSizeBytes:  int64OrDefault(os.Getenv("FILE_SERVICE_SYNC_SCAN_MAX_SIZE_BYTES"), 5*1024*1024),
		DefaultSignedURLTTL:   durationOrDefault(os.Getenv("FILE_SERVICE_DEFAULT_SIGNED_URL_TTL_SECONDS"), 15*time.Minute),

		// S3 driver (AWS / S3-compatible)
		S3Enabled:   boolOrDefault(os.Getenv("FILE_SERVICE_S3_ENABLED"), false),
		S3Region:    valueOrDefault(os.Getenv("FILE_SERVICE_S3_REGION"), "us-east-1"),
		S3Endpoint:  os.Getenv("FILE_SERVICE_S3_ENDPOINT"),
		S3Bucket:    os.Getenv("FILE_SERVICE_S3_BUCKET"),
		S3AccessKey: os.Getenv("FILE_SERVICE_S3_ACCESS_KEY"),
		S3SecretKey: os.Getenv("FILE_SERVICE_S3_SECRET_KEY"),
		S3PathStyle: boolOrDefault(os.Getenv("FILE_SERVICE_S3_PATH_STYLE"), false),
		S3KMSKeyID:  os.Getenv("FILE_SERVICE_S3_KMS_KEY_ID"),

		// Azure Blob driver
		AzureBlobEnabled:    boolOrDefault(os.Getenv("FILE_SERVICE_AZURE_BLOB_ENABLED"), false),
		AzureBlobAccount:    os.Getenv("FILE_SERVICE_AZURE_BLOB_ACCOUNT"),
		AzureBlobAccountKey: os.Getenv("FILE_SERVICE_AZURE_BLOB_ACCOUNT_KEY"),
		AzureBlobContainer:  os.Getenv("FILE_SERVICE_AZURE_BLOB_CONTAINER"),
		AzureBlobEndpoint:   os.Getenv("FILE_SERVICE_AZURE_BLOB_ENDPOINT"),

		// Oracle Object Storage driver (native or S3-compat)
		OCIEnabled:       boolOrDefault(os.Getenv("FILE_SERVICE_OCI_ENABLED"), false),
		OCINamespace:     os.Getenv("FILE_SERVICE_OCI_NAMESPACE"),
		OCIRegion:        os.Getenv("FILE_SERVICE_OCI_REGION"),
		OCIBucket:        os.Getenv("FILE_SERVICE_OCI_BUCKET"),
		OCICompartmentID: os.Getenv("FILE_SERVICE_OCI_COMPARTMENT_ID"),
		OCIS3Compatible:  boolOrDefault(os.Getenv("FILE_SERVICE_OCI_S3_COMPATIBLE"), true),
		OCIS3Endpoint:    os.Getenv("FILE_SERVICE_OCI_S3_ENDPOINT"),
		OCIAccessKey:     os.Getenv("FILE_SERVICE_OCI_ACCESS_KEY"),
		OCISecretKey:     os.Getenv("FILE_SERVICE_OCI_SECRET_KEY"),

		// GCS driver
		GCSEnabled:             boolOrDefault(os.Getenv("FILE_SERVICE_GCS_ENABLED"), false),
		GCSBucket:              os.Getenv("FILE_SERVICE_GCS_BUCKET"),
		GCSServiceAccountEmail: os.Getenv("FILE_SERVICE_GCS_SERVICE_ACCOUNT_EMAIL"),
		GCSPrivateKeyPEM:       os.Getenv("FILE_SERVICE_GCS_PRIVATE_KEY_PEM"),
	}

	if cfg.DBURL == "" {
		return Config{}, errors.New("FILE_SERVICE_DB_URL is required (see .env.example)")
	}
	return cfg, nil
}

// AllowedMIMETypes returns the conservative default allowlist per
// docs/services/file-service/SRS.md §9 (deny by default; the
// configuration-service updates this list at runtime via
// configuration.updated.v1).
func (c Config) AllowedMIMETypes() []string {
	return []string{
		"image/jpeg", "image/png", "image/webp", "image/gif",
		"application/pdf",
		"video/mp4", "video/quicktime",
		"audio/mpeg", "audio/wav",
	}
}

func valueOrDefault(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func int64OrDefault(value string, fallback int64) int64 {
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return fallback
	}
	return parsed
}

func durationOrDefault(value string, fallback time.Duration) time.Duration {
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return time.Duration(parsed) * time.Second
}

func boolOrDefault(value string, fallback bool) bool {
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}
