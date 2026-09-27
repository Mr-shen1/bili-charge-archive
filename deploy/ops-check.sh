#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then echo "usage: $0 ENV_FILE" >&2; exit 2; fi
env_file=$(realpath "$1")
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
dc=(docker compose --env-file "$env_file" -f "$script_dir/compose.prod.yaml")
"${dc[@]}" ps
docker system df
df -h "$script_dir"
"${dc[@]}" logs --tail 30 mysql8 spring-app nginx 2>&1 | awk 'BEGIN {n=0} /WARN|ERROR|Exception/ {n++} END {print "recent_warning_or_error_lines=" n}'
