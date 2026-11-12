#!/usr/bin/env bash
# =============================================================================
# 三服务冒烟测试：健康检查 -> 登录 -> 业务接口 -> Agent 元信息/SSE
# 用法： bash scripts/smoke.sh
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

BASE="http://127.0.0.1:${UI_PORT}"
DORM="http://127.0.0.1:${DORM_PORT}"
AGENT="http://127.0.0.1:${DORM_AGENT_PORT}"
PASS=0; FAIL=0

check() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$name"
  else FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s %s\n' "$name" "$detail"; fi
}

echo "== 健康检查 =="
check "dorm-service /api/health" "$(curl -sf "$DORM/api/health" >/dev/null && echo 1 || echo 0)"
check "agent-service /api/health" "$(curl -sf "$AGENT/api/health" >/dev/null && echo 1 || echo 0)"
check "ui-service /healthz" "$(curl -sf "$BASE/healthz" >/dev/null && echo 1 || echo 0)"
check "ui index.html" "$(curl -sf "$BASE/" | grep -q '<div id="app"' && echo 1 || echo 0)"

echo "== 登录 =="
LOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"superadmin","password":"Dorm@123456"}')
TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("data",{}).get("token",""))' 2>/dev/null)
check "superadmin 登录" "$([ -n "$TOKEN" ] && echo 1 || echo 0)" "$LOGIN"

if [ -n "$TOKEN" ]; then
  AUTH=(-H "Authorization: Bearer $TOKEN")
  echo "== 业务 API（经 UI 反代） =="
  check "region options" "$(curl -sf "${AUTH[@]}" "$BASE/api/regions/options" | grep -q '"code":0' && echo 1 || echo 0)"
  check "room availability" "$(curl -sf "${AUTH[@]}" "$BASE/api/rooms/availability" | grep -q '"code":0' && echo 1 || echo 0)"
  check "stats overview" "$(curl -sf "${AUTH[@]}" "$BASE/api/stats/overview" | grep -q '"code":0' && echo 1 || echo 0)"
  echo "== Agent =="
  check "agent meta" "$(curl -sf "${AUTH[@]}" "$BASE/api/agent/meta" | grep -q '"code":0' && echo 1 || echo 0)"
  check "agent admin models" "$(curl -sf "${AUTH[@]}" "$AGENT/api/agent/admin/models" | grep -q '"code":0' && echo 1 || echo 0)"
  CONV=$(curl -s -X POST "${AUTH[@]}" "$BASE/api/agent/conversations" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("data",{}).get("id",""))' 2>/dev/null)
  check "create conversation" "$([ -n "$CONV" ] && echo 1 || echo 0)"
  if [ -n "$CONV" ]; then
    # SSE 只要能在超时内返回 START/DONE 之一即视为链路通
    SSE=$(curl -s -N --max-time 90 -X POST "$BASE/api/agent/chat/stream" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d "{\"conversationId\":$CONV,\"message\":\"你好\"}" | head -c 400)
    check "agent SSE" "$(printf '%s' "$SSE" | grep -Eq 'START|DELTA|DONE|ERROR' && echo 1 || echo 0)" "$SSE"
  fi
fi

echo "----------------------------------------"
echo "通过：$PASS  失败：$FAIL"
[ "$FAIL" -eq 0 ]
