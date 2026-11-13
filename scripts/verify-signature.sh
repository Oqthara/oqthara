#!/usr/bin/env bash
# verify-signature.sh - verify the sigstore signature on a released oq.jar.
#
# Usage:
#   ./scripts/verify-signature.sh path/to/oq.jar path/to/oq.jar.sigstore.json
#
# The checksum published beside a release detects corruption: anyone who can replace
# the jar can replace the checksum. This script checks a signature instead, which
# detects tampering because the verification key is not in the release.
#
# Verification is deliberately independent of the network when a key is supplied
# (OQ_COSIGN_KEY), so an air-gapped or offline check is possible. The identity-based
# path needs the transparency log, and says so rather than failing obscurely.
#
# Exit codes: 0 verified, 1 verification failed, 2 usage or missing tool.
set -euo pipefail

PUB_KEY_DEFAULT="${OQ_COSIGN_KEY:-}"

usage() {
  cat <<'USAGE'
usage: verify-signature.sh <artifact> <bundle>

Verifies <artifact> against a sigstore bundle.

Environment:
  OQ_COSIGN_KEY       path to a cosign public key. When set, verification is
                      offline and does not consult the transparency log.
  OQ_COSIGN_IDENTITY  expected certificate identity (email or URI) for the
                      keyless path. Defaults to the release workflow identity.
  OQ_COSIGN_ISSUER    expected OIDC issuer for the keyless path.
  OQ_COSIGN_BIN       cosign binary to use (default: cosign on PATH).
USAGE
}

if [ "$#" -ne 2 ]; then
  usage >&2
  exit 2
fi

artifact="$1"
bundle="$2"
cosign_bin="${OQ_COSIGN_BIN:-cosign}"

if [ ! -f "$artifact" ]; then
  printf 'error: artifact not found: %s\n' "$artifact" >&2
  exit 2
fi
if [ ! -f "$bundle" ]; then
  printf 'error: bundle not found: %s\n' "$bundle" >&2
  exit 2
fi

if ! command -v "$cosign_bin" >/dev/null 2>&1; then
  cat >&2 <<EOF
error: '$cosign_bin' is not installed, so the signature cannot be checked.

Install cosign (https://docs.sigstore.dev/cosign/installation/), or download a
release binary and set OQ_COSIGN_BIN to its path. If you cannot install it, the
compromise is explicit: verify the SHA-256 instead, which detects corruption and
NOT tampering. Do not describe that as a signature check.
EOF
  exit 2
fi

if [ -n "$PUB_KEY_DEFAULT" ]; then
  if [ ! -f "$PUB_KEY_DEFAULT" ]; then
    printf 'error: OQ_COSIGN_KEY is set but not a file: %s\n' "$PUB_KEY_DEFAULT" >&2
    exit 2
  fi
  if "$cosign_bin" verify-blob --key "$PUB_KEY_DEFAULT" --bundle "$bundle" "$artifact" >/dev/null 2>&1; then
    printf 'verified: %s matches the signature in %s (offline, key %s)\n' \
      "$artifact" "$bundle" "$PUB_KEY_DEFAULT"
    exit 0
  fi
  printf 'error: signature verification FAILED for %s\n' "$artifact" >&2
  printf 'The artifact does not match the signed bundle. Do not run it.\n' >&2
  exit 1
fi

identity="${OQ_COSIGN_IDENTITY:-https://github.com/Oqthara/oqthara/.github/workflows/release.yml@refs/tags/*}"
issuer="${OQ_COSIGN_ISSUER:-https://token.actions.githubusercontent.com}"

if "$cosign_bin" verify-blob \
    --certificate-identity "$identity" \
    --certificate-oidc-issuer "$issuer" \
    --bundle "$bundle" "$artifact" >/dev/null 2>&1; then
  printf 'verified: %s matches the signature in %s (keyless, identity %s)\n' \
    "$artifact" "$bundle" "$identity"
  exit 0
fi

printf 'error: signature verification FAILED for %s\n' "$artifact" >&2
printf 'Either the artifact does not match the bundle, or it was not signed by %s.\n' "$identity" >&2
printf 'Do not run it. If you expected a different signer, set OQ_COSIGN_IDENTITY.\n' >&2
exit 1
