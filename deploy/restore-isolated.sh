#!/usr/bin/env bash
# shellcheck disable=SC2016 # Variables in single-quoted commands expand inside the MySQL container.
set -euo pipefail
umask 077

if [[ $# -ne 2 ]]; then echo "usage: $0 ISOLATED_ENV_FILE BACKUP_DIR" >&2; exit 2; fi
env_file=$(realpath "$1")
backup_dir=$(realpath "$2")
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
project=$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' "$env_file" | tail -1)
bind_ip=$(sed -n 's/^PUBLIC_BIND_IP=//p' "$env_file" | tail -1)
if [[ ! "$project" =~ ^m8[_-][a-zA-Z0-9_-]+$ || "$bind_ip" != 127.0.0.1 ]]; then
  echo "restore requires an m8_* project bound to 127.0.0.1" >&2; exit 2
fi
export COMPOSE_PROJECT_NAME="$project" PUBLIC_BIND_IP="$bind_ip"
(cd "$backup_dir" && sha256sum -c SHA256SUMS)
dc=(docker compose --env-file "$env_file" -f "$script_dir/compose.prod.yaml")
"${dc[@]}" up -d --wait mysql8
existing=$("${dc[@]}" exec -T mysql8 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -N -B "$MYSQL_DATABASE" -e "SHOW TABLES"' | wc -l)
if [[ "$existing" -ne 0 ]]; then echo "target database is not empty; refusing restore" >&2; exit 2; fi
gzip -dc "$backup_dir/database.sql.gz" | "${dc[@]}" exec -T mysql8 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root "$MYSQL_DATABASE"'
restored_keys=$(mktemp)
trap 'rm -f "$restored_keys"' EXIT
"${dc[@]}" exec -T mysql8 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -N -B "$MYSQL_DATABASE" -e "SELECT oss_key FROM dynamic_image WHERE upload_status=0x5245414459 AND oss_key IS NOT NULL UNION SELECT oss_key FROM comment_image WHERE upload_status=0x5245414459 AND oss_key IS NOT NULL ORDER BY oss_key"' > "$restored_keys"
if ! cmp -s "$backup_dir/oss-keys.txt" "$restored_keys"; then
  echo "restored OSS keys differ from backup manifest" >&2; exit 1
fi
"${dc[@]}" up -d --wait spring-app nginx
printf 'restored_project=%s\n' "$project"
