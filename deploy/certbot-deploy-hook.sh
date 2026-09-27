#!/usr/bin/env bash
set -euo pipefail
: "${BILI_DEPLOY_ENV_FILE:?renew-cert.sh must set BILI_DEPLOY_ENV_FILE}"
: "${RENEWED_LINEAGE:?Certbot must set RENEWED_LINEAGE}"
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
exec "$script_dir/install-renewed-cert.sh" "$BILI_DEPLOY_ENV_FILE" "$RENEWED_LINEAGE"
