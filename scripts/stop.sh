#!/usr/bin/env bash
# 停止服务。
#   bash scripts/stop.sh                # 停三个服务
#   bash scripts/stop.sh agent ui       # 只停指定服务
#   bash scripts/stop.sh --all          # 连 MySQL/Redis 一起停
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

stop_one() {
  local name="$1" pidfile="$ROOT/run/$1.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; then
    local pid; pid=$(cat "$pidfile")
    echo "[stop] $name (pid $pid)"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
  else
    echo "[stop] $name 未运行"
  fi
  rm -f "$pidfile"
}

ALL=false
TARGETS=()
for arg in "$@"; do
  case "$arg" in
    --all) ALL=true ;;
    dorm|agent|ui) TARGETS+=("$arg") ;;
    *) echo "[stop] 未知目标：$arg（可选 dorm/agent/ui/--all）" >&2 ;;
  esac
done
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(ui agent dorm)

for target in "${TARGETS[@]}"; do
  case "$target" in
    dorm) stop_one dorm-service ;;
    agent) stop_one agent-service ;;
    ui) stop_one ui-service ;;
  esac
done

if [ "$ALL" = true ]; then
  bash "$ROOT/scripts/env-stop.sh"
fi
echo "[stop] 完成"
