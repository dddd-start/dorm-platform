#!/usr/bin/env bash
# 重启指定服务（默认三个都重启，保留 MySQL/Redis）
# 用法： bash scripts/restart.sh [dorm|agent|ui ...]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash "$ROOT/scripts/stop.sh" "$@"
exec bash "$ROOT/scripts/start.sh" "$@"
