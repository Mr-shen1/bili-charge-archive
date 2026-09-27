#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then echo "usage: $0 ENV_FILE" >&2; exit 2; fi
env_file=$(realpath "$1")
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
public_ip=$(sed -n 's/^PUBLIC_IP=//p' "$env_file" | tail -1)
if [[ -z "$public_ip" || "$public_ip" == REPLACE_* ]]; then echo "PUBLIC_IP missing" >&2; exit 2; fi
export BILI_DEPLOY_ENV_FILE="$env_file"
certbot renew --cert-name "$public_ip" --deploy-hook "$script_dir/certbot-deploy-hook.sh"
