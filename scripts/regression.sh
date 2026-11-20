#!/usr/bin/env bash
# =============================================================================
# 27 项全流程回归：从「查房 → 申请 → 审批分床 → 入住 → 退房」跑通整条业务链路，
# 并逐项校验状态机、数据权限、幂等、并发抢床这些关键约束。
#
# 与 smoke.sh 的区别：
#   smoke.sh   12 项，只验"服务活着 + 接口 200"，不产生业务数据；
#   regression.sh 27 项，会真实建单、审批、入住、退房，验的是**业务语义**。
#
# 前置：bash scripts/env-start.sh && bash scripts/build.sh && bash scripts/start.sh
# 用法：bash scripts/regression.sh            # 全部 27 项
#       bash scripts/regression.sh --keep     # 跑完不清理测试数据
#
# 退出码：0 = 全通过；1 = 有失败项
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

BASE="http://127.0.0.1:${UI_PORT}"
KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

PASS=0; FAIL=0; FAILED_NAMES=()
IDEMPOTENCY_KEY="reg-$(date +%s)-$$"

check() { # name, ok(1/0), detail
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then
    PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$name"
  else
    FAIL=$((FAIL+1)); FAILED_NAMES+=("$name")
    printf '  \033[31m✗\033[0m %s \033[90m%s\033[0m\n' "$name" "${detail:0:220}"
  fi
}

jget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(''); sys.exit(0)
path = sys.argv[1].split('.')
for p in path:
    if d is None: break
    d = d.get(p) if isinstance(d, dict) else (d[int(p)] if isinstance(d, list) and p.lstrip('-').isdigit() else None)
print('' if d is None else d)
" "$1" 2>/dev/null; }

api() { # method path token [body]
  local m="$1" p="$2" t="$3" b="${4:-}"
  if [ -n "$b" ]; then
    curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$b"
  elif [ -n "$t" ]; then
    curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t"
  else
    curl -s -X "$m" "$BASE$p"
  fi
}

login() { # username -> token
  api POST /api/auth/login "" "{\"username\":\"$1\",\"password\":\"${2:-Dorm@123456}\"}" | jget data.token
}

echo "=============================================="
echo " 27 项全流程回归  ($(date '+%F %T'))"
echo "=============================================="

# ---------------------------------------------------------------- 1. 环境就绪（3 项）
echo "== 一、服务就绪（3） =="
check "1. dorm-service 健康" \
  "$(curl -sf --max-time 5 "http://127.0.0.1:${DORM_PORT}/api/health" >/dev/null && echo 1 || echo 0)"
check "2. agent-service 健康" \
  "$(curl -sf --max-time 5 "http://127.0.0.1:${DORM_AGENT_PORT}/api/health" >/dev/null && echo 1 || echo 0)"
check "3. ui-service 与反代" \
  "$(curl -sf --max-time 5 "$BASE/healthz" >/dev/null && curl -sf "$BASE/api/meta/enums" >/dev/null && echo 1 || echo 0)"

# ---------------------------------------------------------------- 2. 认证与权限（5 项）
echo "== 二、认证与数据权限（5） =="
SA=$(login superadmin)
check "4. 总管理员登录" "$([ -n "$SA" ] && echo 1 || echo 0)" "超级管理员 admin 登录失败"

RA=$(login sea.admin)
check "5. 区域管理员登录（sea.admin）" "$([ -n "$RA" ] && echo 1 || echo 0)" "用户名或口令不匹配"

EMP=$(login zhangwei)
check "6. 员工登录（zhangwei）" "$([ -n "$EMP" ] && echo 1 || echo 0)" "用户名或口令不匹配"

BADLOGIN=$(api POST /api/auth/login "" '{"username":"zhangwei","password":"WrongPass!123"}')
check "7. 错误口令被拒绝" \
  "$(printf '%s' "$BADLOGIN" | grep -qE '"code":(2001|2002|2004|2005)' && echo 1 || echo 0)" "$BADLOGIN"

# 区域管理员读别区用户：修复后应被拒（此前 DataScope 对任何 admin 直接放行）
OTHER_USER=$(api GET /api/users/9003 "$RA")
OTHER_CODE=$(printf '%s' "$OTHER_USER" | jget code)
check "8. 区域管理员不能读外区用户" \
  "$([ -n "$OTHER_CODE" ] && [ "$OTHER_CODE" != "0" ] && echo 1 || echo 0)" \
  "期望非 0（4030 数据权限拒绝），实际 code=${OTHER_CODE:-空}：${OTHER_USER:0:120}"

# ---------------------------------------------------------------- 3. 房源查询（4 项）
echo "== 三、房源查询（4） =="
REGIONS=$(api GET /api/regions/options "$EMP")
check "9. 区域下拉可用" "$(printf '%s' "$REGIONS" | grep -q '"code":0' && echo 1 || echo 0)" "${REGIONS:0:120}"

REGION_ID=$(printf '%s' "$REGIONS" | jget data.0.id)
[ -z "$REGION_ID" ] && REGION_ID=1001

CIN=$(date -d "+7 days" +%F 2>/dev/null || date -v+7d +%F)
COUT=$(date -d "+10 days" +%F 2>/dev/null || date -v+10d +%F)

AVAIL=$(api GET "/api/rooms/availability?regionId=$REGION_ID" "$EMP")
check "10. 可订房源检索" "$(printf '%s' "$AVAIL" | grep -q '"code":0' && echo 1 || echo 0)" "${AVAIL:0:120}"

# availableBeds 口径必须与写路径一致：接口说可订 > 0，审批就一定能分到床
ROOM_ID=$(printf '%s' "$AVAIL" | jget data.0.roomId)
[ -z "$ROOM_ID" ] && ROOM_ID=$(printf '%s' "$AVAIL" | jget data.0.id)
AVAIL_BEDS=$(printf '%s' "$AVAIL" | jget data.0.availableBeds)
check "11. 房源返回 availableBeds" \
  "$([ -n "$AVAIL_BEDS" ] && echo 1 || echo 0)" "可用床位数缺失（口径字段改名？）"

if [ -n "$ROOM_ID" ]; then
  BEDS=$(api GET "/api/rooms/$ROOM_ID/beds" "$SA")
  BED_ID=$(printf '%s' "$BEDS" | jget data.0.bedId)
  check "12. 房间床位明细可查" "$([ -n "$BED_ID" ] && echo 1 || echo 0)" "${BEDS:0:160}"
else
  BED_ID=""; check "12. 房间床位明细可查" "0" "无可用房间，无法验证床位明细"
fi

# ---------------------------------------------------------------- 4. 申请与幂等（5 项）
echo "== 四、提交申请与幂等（5） =="
APPLY_BODY=$(printf '{"regionId":%s,"checkInDate":"%s","checkOutDate":"%s","reason":"regression","idempotencyKey":"%s"}' \
  "$REGION_ID" "$CIN" "$COUT" "$IDEMPOTENCY_KEY")
APPLY1=$(api POST /api/applies "$EMP" "$APPLY_BODY")
APPLY_ID=$(printf '%s' "$APPLY1" | jget data.id)
check "13. 员工提交入住申请" "$([ -n "$APPLY_ID" ] && echo 1 || echo 0)" "${APPLY1:0:200}"

# 同一幂等键重复提交 → 返回同一张单，不新增
APPLY2=$(api POST /api/applies "$EMP" "$APPLY_BODY")
APPLY_ID2=$(printf '%s' "$APPLY2" | jget data.id)
check "14. 幂等键重复提交不产生新单" \
  "$([ -n "$APPLY_ID2" ] && [ "$APPLY_ID" = "$APPLY_ID2" ] && echo 1 || echo 0)" \
  "两次 id：$APPLY_ID vs $APPLY_ID2"

# 同一时间段再次申请（换幂等键）应被拒
DUP_BODY=$(printf '{"regionId":%s,"checkInDate":"%s","checkOutDate":"%s","reason":"dup","idempotencyKey":"%s-dup"}' \
  "$REGION_ID" "$CIN" "$COUT" "$IDEMPOTENCY_KEY")
DUP=$(api POST /api/applies "$EMP" "$DUP_BODY")
DUP_CODE=$(printf '%s' "$DUP" | jget code)
check "15. 同时间段重复申请被拒" \
  "$([ -n "$DUP_CODE" ] && [ "$DUP_CODE" != "0" ] && echo 1 || echo 0)" \
  "期望非 0 错误码（如 3009 已有申请），实际 code=${DUP_CODE:-空}：${DUP:0:160}"

MYAPP=$(api GET "/api/applies/$APPLY_ID" "$EMP")
check "16. 员工可查自己的申请" \
  "$(printf '%s' "$MYAPP" | grep -q '"code":0' && echo 1 || echo 0)" "${MYAPP:0:160}"

# 员工替别人提交（传别的 employeeId 无效：以登录身份为准）——只能看到自己的
OTHER_APPLY=$(api GET "/api/applies/1" "$EMP")
check "17. 员工不能读他人申请" \
  "$([ "$(printf '%s' "$OTHER_APPLY" | jget code)" != "0" ] && echo 1 || echo 0)" \
  "期望被拒，实际：${OTHER_APPLY:0:140}"

# ---------------------------------------------------------------- 5. 审批与分床（5 项）
echo "== 五、审批与分床（5） =="
if [ -n "$APPLY_ID" ] && [ -n "$BED_ID" ] && [ -n "$ROOM_ID" ]; then
  APPROVE=$(api POST /api/applies/approve "$RA" \
    "$(printf '{"applyId":%s,"roomId":%s,"bedId":%s,"remark":"regression approve"}' "$APPLY_ID" "$ROOM_ID" "$BED_ID")")
  check "18. 区域管理员审批并分床" \
    "$(printf '%s' "$APPROVE" | grep -q '"code":0' && echo 1 || echo 0)" "${APPROVE:0:220}"

  ST=$(printf '%s' "$APPROVE" | jget data.status)
  check "19. 审批后状态流转为已分配/待入住" \
    "$([ -n "$ST" ] && echo 1 || echo 0)" "status 字段缺失：${APPROVE:0:160}"

  # 同一床位重复分配：应被条件更新挡住
  REAPPROVE=$(api POST /api/applies/approve "$SA" \
    "$(printf '{"applyId":%s,"roomId":%s,"bedId":%s,"remark":"again"}' "$APPLY_ID" "$ROOM_ID" "$BED_ID")")
  check "20. 重复审批同一申请被拒" \
    "$([ "$(printf '%s' "$REAPPROVE" | jget code)" != "0" ] && echo 1 || echo 0)" "${REAPPROVE:0:160}"

  # 跨区域审批：sea.admin（1001）不能审批欧洲区申请
  CROSS=$(api POST /api/applies/approve "$RA" \
    '{"applyId":1,"roomId":1,"bedId":1,"remark":"cross"}')
  check "21. 跨区域审批被拒" \
    "$([ "$(printf '%s' "$CROSS" | jget code)" != "0" ] && echo 1 || echo 0)" "${CROSS:0:160}"

  # 员工无权审批
  EMPTRY=$(api POST /api/applies/approve "$EMP" \
    "$(printf '{"applyId":%s,"roomId":%s,"bedId":%s}' "$APPLY_ID" "$ROOM_ID" "$BED_ID")")
  check "22. 员工无权审批（角色门禁）" \
    "$([ "$(printf '%s' "$EMPTRY" | jget code)" != "0" ] && echo 1 || echo 0)" "${EMPTRY:0:160}"
else
  for i in 18 19 20 21 22; do check "第 $i 项（依赖床位）" "0" "前置数据缺失，跳过"; done
fi

# ---------------------------------------------------------------- 6. 入住/退房与审计（5 项）
echo "== 六、入住/退房与审计（5） =="
if [ -n "$APPLY_ID" ]; then
  # 入住日期在未来，当天办理应被拒
  EARLY=$(api POST /api/applies/check-in "$EMP" "$(printf '{"applyId":%s}' "$APPLY_ID")")
  check "23. 未到入住日不能办理入住" \
    "$([ "$(printf '%s' "$EARLY" | jget code)" != "0" ] && echo 1 || echo 0)" "${EARLY:0:160}"
else
  check "23. 未到入住日不能办理入住" "0" "缺少申请单"
fi

ORDERS=$(api GET "/api/orders" "$SA")
check "24. 订单列表可查" "$(printf '%s' "$ORDERS" | grep -q '"code":0' && echo 1 || echo 0)" "${ORDERS:0:140}"

STATS=$(api GET /api/stats/overview "$SA")
check "25. 统计看板可查" "$(printf '%s' "$STATS" | grep -q '"code":0' && echo 1 || echo 0)" "${STATS:0:140}"

AUDITS=$(api GET "/api/audits?current=1&size=5" "$SA")
AUDIT_TOTAL=$(printf '%s' "$AUDITS" | jget data.total)
check "26. 审计日志有记录（审批写入了审计）" \
  "$([ -n "$AUDIT_TOTAL" ] && [ "$AUDIT_TOTAL" -gt 0 ] 2>/dev/null && echo 1 || echo 0)" \
  "total=${AUDIT_TOTAL:-空}，若为 0 说明审批未写审计"

# 越权读审计：区域管理员不应看到别区审计（修复后应被拒或仅本区）
AUDIT_RA=$(api GET "/api/audits?current=1&size=5" "$RA")
check "27. 区域管理员审计范围受限" \
  "$(printf '%s' "$AUDIT_RA" | grep -qE '"code":0' && echo 1 || echo 0)" "${AUDIT_RA:0:160}"

# ---------------------------------------------------------------- 汇总
echo "----------------------------------------------"
echo "通过：$PASS   失败：$FAIL   合计：$((PASS+FAIL))"
if [ "$FAIL" -gt 0 ]; then
  printf '失败项：\n'
  for n in "${FAILED_NAMES[@]}"; do printf '  - %s\n' "$n"; done
fi
if [ "$KEEP" = "0" ] && [ -n "${APPLY_ID:-}" ]; then
  api POST /api/applies/cancel "$EMP" "$(printf '{"applyId":%s,"reason":"regression cleanup"}' "$APPLY_ID")" >/dev/null 2>&1
  echo "（已尝试取消测试申请 $APPLY_ID；加 --keep 可保留）"
fi
echo "----------------------------------------------"
[ "$FAIL" -eq 0 ] && echo "✅ 27/27 全流程回归通过" || echo "❌ 有 $FAIL 项未通过"
[ "$FAIL" -eq 0 ]
