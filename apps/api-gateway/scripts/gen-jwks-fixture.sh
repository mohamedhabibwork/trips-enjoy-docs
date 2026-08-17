#!/usr/bin/env bash
# Generate a self-signed RSA keypair and emit a JWKS fixture for
# offline tests + local development. NOT for production.
#
# Usage: ./scripts/gen-jwks-fixture.sh [out-dir]
#
# Outputs:
#   ${out-dir}/jwks.json     — public JWKS
#   ${out-dir}/signing.pem   — RSA private key in PEM form
#
# The signing key is purely for local use. In stg/prod, the JWKS
# comes from Keycloak (docs/architecture/KEYCLOAK_ARCHITECTURE.md).

set -euo pipefail

out_dir="${1:-./tmp/jwks}"
mkdir -p "${out_dir}"

# Generate a 2048-bit RSA private key.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "${out_dir}/signing.pem" 2>/dev/null

# Extract the modulus (n) and exponent (e) for the JWKS.
modulus=$(openssl rsa -in "${out_dir}/signing.pem" -noout -modulus \
    | sed -E 's/^Modulus=//')
exponent=$(openssl rsa -in "${out_dir}/signing.pem" -noout -text \
    | awk '/^exponent:/{print $2}' | tr -d '()')
# Convert raw hex modulus/exponent to base64url.
n_b64=$(printf "%s" "${modulus^^}" | xxd -r -p | base64 | tr -d '\n=' | tr '+/' '-_')
e_b64=$(printf "%02x" "0x${exponent}" | xxd -r -p | base64 | tr -d '\n=' | tr '+/' '-_')
kid="api-gateway-dev"

cat > "${out_dir}/jwks.json" <<EOF
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "${kid}",
      "n": "${n_b64}",
      "e": "${e_b64}"
    }
  ]
}
EOF

echo "wrote:"
echo "  ${out_dir}/jwks.json"
echo "  ${out_dir}/signing.pem"
echo
echo "Kid: ${kid}"
echo "Use API_GATEWAY_KEYCLOAK_JWKS_URI=file://${PWD}/${out_dir}/jwks.json for offline tests."
