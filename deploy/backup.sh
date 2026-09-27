#!/usr/bin/env bash
# shellcheck disable=SC2016 # Variables in single-quoted commands expand inside the MySQL container.
set -euo pipefail
umask 077

if [[ $# -ne 2 ]]; then echo "usage: $0 ENV_FILE BACKUP_DIR" >&2; exit 2; fi
env_file=$(realpath "$1")
backup_dir=$(realpath -m "$2")
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
mkdir -p -- "$backup_dir"
if [[ -e "$backup_dir/database.sql.gz" ]]; then echo "backup directory already used" >&2; exit 2; fi
dc=(docker compose --env-file "$env_file" -f "$script_dir/compose.prod.yaml")
keys_before=$(mktemp)
trap 'rm -f "$keys_before"' EXIT
key_query='MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -N -B "$MYSQL_DATABASE" -e "SELECT oss_key FROM dynamic_image WHERE upload_status=0x5245414459 AND oss_key IS NOT NULL UNION SELECT oss_key FROM comment_image WHERE upload_status=0x5245414459 AND oss_key IS NOT NULL ORDER BY oss_key"'

"${dc[@]}" exec -T mysql8 sh -c "$key_query" > "$keys_before"
"${dc[@]}" exec -T mysql8 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -u root --single-transaction --quick --no-tablespaces --routines --triggers --events --set-gtid-purged=OFF "$MYSQL_DATABASE"' | gzip -9 > "$backup_dir/database.sql.gz"
gzip -t "$backup_dir/database.sql.gz"
"${dc[@]}" exec -T mysql8 sh -c "$key_query" > "$backup_dir/oss-keys.txt"
if ! cmp -s "$keys_before" "$backup_dir/oss-keys.txt"; then
  echo "OSS keys changed during SQL snapshot; discard this backup and retry during a quiet window" >&2; exit 1
fi
sha256sum "$backup_dir/database.sql.gz" "$backup_dir/oss-keys.txt" | sed "s|$backup_dir/||g" > "$backup_dir/SHA256SUMS"
printf 'backup=%s\n' "$backup_dir"
printf 'oss_keys=%s\n' "$(wc -l < "$backup_dir/oss-keys.txt")"
