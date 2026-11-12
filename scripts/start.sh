#!/usr/bin/env bash
# =============================================================================
# 启动三个独立服务（依赖先自动拉起）
#   dorm-service  :8081
#   agent-service :8082
#   ui-service    :8080
#
# 用法：
#   bash scripts/start.sh            # 启动全部
#   bash scripts/start.sh dorm       # 只启动 dorm-service
#   bash scripts/start.sh agent ui   # 启动 agent + ui
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

log()  { printf '\033[32m[start]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[start]\033[0m %s\n' "$*"; }
running() { [ -f "$1" ] && kill -0 "$(cat "$1" 2>/dev/null)" 2>/dev/null; }

TARGETS=("$@")
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(dorm agent ui)

start_dorm() {
  local pid="$DORM_RUN_DIR/dorm-service.pid"
  if running "$pid"; then log "dorm-service 已在运行 (pid $(cat "$pid"))"; return 0; fi
  [ -f "$ROOT/dorm-service/target/dorm-service.jar" ] || { warn "缺少 dorm-service.jar，请先 bash scripts/build.sh"; return 1; }
  log "启动 dorm-service (:${DORM_PORT}) ..."
  setsid --fork bash -c 'echo $$ > "$0"; cd "$1"; shift; exec "$@"' \
      "$pid" "$ROOT" "$JAVA_HOME/bin/java" ${JAVA_OPTS:-} \
      -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai \
      -jar dorm-service/target/dorm-service.jar \
      --spring.profiles.active="${DORM_PROFILE:-default}" \
      >> "$DORM_LOG_DIR/dorm-service.log" 2>&1 < /dev/null
  for _ in $(seq 1 120); do
    if curl -sf --max-time 3 "http://127.0.0.1:${DORM_PORT}/api/health" >/dev/null 2>&1; then
      log "dorm-service 就绪 -> http://127.0.0.1:${DORM_PORT}"
      return 0
    fi
    if ! running "$pid"; then warn "dorm-service 启动失败，见 $DORM_LOG_DIR/dorm-service.log"; return 1; fi
    sleep 1
  done
  warn "dorm-service 120s 内未就绪"; return 1
}

start_agent() {
  local pid="$DORM_RUN_DIR/agent-service.pid"
  if running "$pid"; then log "agent-service 已在运行 (pid $(cat "$pid"))"; return 0; fi
  [ -f "$ROOT/agent-service/target/agent-service.jar" ] || { warn "缺少 agent-service.jar，请先 bash scripts/build.sh"; return 1; }
  log "启动 agent-service (:${DORM_AGENT_PORT}) ..."
  setsid --fork bash -c 'echo $$ > "$0"; cd "$1"; shift; exec "$@"' \
      "$pid" "$ROOT" "$JAVA_HOME/bin/java" ${JAVA_OPTS:-} \
      -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai \
      -jar agent-service/target/agent-service.jar \
      --spring.profiles.active="${DORM_PROFILE:-default}" \
      >> "$DORM_LOG_DIR/agent-service.log" 2>&1 < /dev/null
  for _ in $(seq 1 150); do
    if curl -sf --max-time 3 "http://127.0.0.1:${DORM_AGENT_PORT}/api/health" >/dev/null 2>&1; then
      log "agent-service 就绪 -> http://127.0.0.1:${DORM_AGENT_PORT}"
      return 0
    fi
    if ! running "$pid"; then warn "agent-service 启动失败，见 $DORM_LOG_DIR/agent-service.log"; return 1; fi
    sleep 1
  done
  warn "agent-service 150s 内未就绪"; return 1
}

start_ui() {
  local pid="$DORM_RUN_DIR/ui-service.pid"
  if running "$pid"; then log "ui-service 已在运行 (pid $(cat "$pid"))"; return 0; fi
  if [ ! -f "$ROOT/ui-service/dist/index.html" ]; then
    warn "UI 尚未构建，请先 bash scripts/build.sh"; return 1
  fi
  log "启动 ui-service (:${UI_PORT}) ..."
  setsid --fork bash -c 'echo $$ > "$0"; cd "$1"; shift; exec "$@"' \
      "$pid" "$ROOT/ui-service" env \
      UI_PORT="$UI_PORT" \
      DORM_SERVICE_URL="http://127.0.0.1:${DORM_PORT}" \
      AGENT_SERVICE_URL="http://127.0.0.1:${DORM_AGENT_PORT}" \
      node server.mjs >> "$DORM_LOG_DIR/ui-service.log" 2>&1 < /dev/null
  for _ in $(seq 1 30); do
    if curl -sf --max-time 3 "http://127.0.0.1:${UI_PORT}/healthz" >/dev/null 2>&1; then
      log "ui-service 就绪 -> http://127.0.0.1:${UI_PORT}"
      return 0
    fi
    sleep 1
  done
  warn "ui-service 30s 内未就绪"; return 1
}

# 依赖（MySQL/Redis）不在运行则拉起
if ! bash "$ROOT/scripts/env-start.sh" >/dev/null 2>&1; then
  warn "依赖环境启动失败，请单独执行 bash scripts/env-start.sh 查看原因"
fi

for target in "${TARGETS[@]}"; do
  case "$target" in
    dorm) start_dorm ;;
    agent) start_agent ;;
    ui) start_ui ;;
    *) warn "未知目标：$target（可选 dorm/agent/ui）" ;;
  esac
done
