#!/usr/bin/env bash
#
# ai-auto-testing-backend / infra / stopall.sh
#
# startall.sh tarafindan baslatilan tum Appium/Grid hub/Grid node
# sureclerini durdurur.
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$SCRIPT_DIR/.run/pids"

if [[ ! -f "$PID_FILE" ]]; then
  echo "[stopall] $PID_FILE bulunamadi -- calisan bir surec yok (startall.sh hic calistirilmamis olabilir)."
  exit 0
fi

count=0
while IFS= read -r pid; do
  [[ -z "$pid" ]] && continue
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null && count=$((count + 1))
  fi
done < "$PID_FILE"

: > "$PID_FILE"
echo "[stopall] $count surec durduruldu."
