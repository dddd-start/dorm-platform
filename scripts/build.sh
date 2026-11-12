#!/usr/bin/env bash
# =============================================================================
# 构建三个服务（后端两个 jar + UI 静态产物）
# 用法： bash scripts/build.sh [--skip-ui]
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

SKIP_UI=false
[ "${1:-}" = "--skip-ui" ] && SKIP_UI=true

log() { printf '\033[32m[build]\033[0m %s\n' "$*"; }

log "构建 dorm-contract / dorm-service / agent-service ..."
( cd "$ROOT" && "$M2_HOME/bin/mvn" -B -q -DskipTests package )
log "后端构建完成："
ls -lh "$ROOT/dorm-service/target/dorm-service.jar" "$ROOT/agent-service/target/agent-service.jar"

if [ "$SKIP_UI" = false ]; then
  log "构建 UI ..."
  cd "$ROOT/ui-service"
  if [ ! -d node_modules ]; then
    log "安装 UI 依赖（首次）"
    npm ci
  fi
  npm run build
  log "UI 构建完成：$ROOT/ui-service/dist"
else
  log "跳过 UI 构建"
fi
