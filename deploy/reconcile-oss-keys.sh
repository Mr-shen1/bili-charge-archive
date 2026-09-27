#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then echo "usage: $0 BACKUP_OSS_KEYS ACTUAL_OBJECT_INVENTORY" >&2; exit 2; fi
missing=$(comm -23 <(LC_ALL=C sort -u "$1") <(LC_ALL=C sort -u "$2") | wc -l)
printf 'missing_oss_objects=%s\n' "$missing"
[[ "$missing" -eq 0 ]]
