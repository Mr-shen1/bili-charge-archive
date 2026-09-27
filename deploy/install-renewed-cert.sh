#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 2 ]]; then echo "usage: $0 ENV_FILE CERT_LINEAGE_DIR" >&2; exit 2; fi
env_file=$(realpath "$1")
lineage=$(realpath "$2")
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
state_dir=$(sed -n 's/^DEPLOY_STATE_DIR=//p' "$env_file" | tail -1)
if [[ -z "$state_dir" ]]; then echo "DEPLOY_STATE_DIR missing" >&2; exit 2; fi
if [[ "$state_dir" != /* ]]; then state_dir="$script_dir/$state_dir"; fi
tls_dir="$state_dir/tls"
mkdir -p "$tls_dir"
install -m 600 "$lineage/fullchain.pem" "$tls_dir/fullchain.pem.new"
install -m 600 "$lineage/privkey.pem" "$tls_dir/privkey.pem.new"
mv -f "$tls_dir/fullchain.pem.new" "$tls_dir/fullchain.pem"
mv -f "$tls_dir/privkey.pem.new" "$tls_dir/privkey.pem"
dc=(docker compose --env-file "$env_file" -f "$script_dir/compose.prod.yaml")
if "${dc[@]}" ps --status running --services | grep -qx nginx; then
  "${dc[@]}" exec -T nginx nginx -t
  "${dc[@]}" exec -T nginx nginx -s reload
fi
printf 'certificate_installed=true\n'
