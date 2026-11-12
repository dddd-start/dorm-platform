#!/usr/bin/env bash
# =============================================================================
# 一键演示：拉起依赖与三个服务，跑冒烟，打印登录账号与演示路线。
#
# 用法：
#   bash scripts/demo.sh              # 起服务 + 冒烟 + 打印演示路线
#   bash scripts/demo.sh --check      # 只检查环境与构建产物，不启动
#   bash scripts/demo.sh --stop       # 停止三个服务（保留 MySQL/Redis）
#
# 前置：已执行 bash scripts/env-install.sh && bash scripts/env-start.sh，
#       且已构建（bash scripts/build.sh）。本脚本只负责"起+验+指路"。
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

MODE="demo"
case "${1:-}" in
  --check) MODE="check" ;;
  --stop)  MODE="stop" ;;
  -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
  "") ;;
  *) echo "[demo] 未知参数：$1（可用 --check / --stop）" >&2; exit 2 ;;
esac

c_green='\033[32m'; c_yellow='\033[33m'; c_cyan='\033[36m'; c_dim='\033[2m'; c_reset='\033[0m'
log()  { printf "${c_green}[demo]${c_reset} %s\n" "$*"; }
warn() { printf "${c_yellow}[demo]${c_reset} %s\n" "$*"; }

if [ "$MODE" = "stop" ]; then
  bash "$ROOT/scripts/stop.sh"
  exit $?
fi

# ---------------------------------------------------------------- 前置检查
fail=0
check_file() { [ -e "$1" ] || { warn "缺少 $2（$1）"; fail=1; }; }
check_file "$ROOT/config/.env" "config/.env，先跑 bash scripts/env-install.sh"
check_file "$ROOT/dorm-service/target/dorm-service.jar" "dorm-service.jar，先跑 bash scripts/build.sh"
check_file "$ROOT/agent-service/target/agent-service.jar" "agent-service.jar，先跑 bash scripts/build.sh"
check_file "$ROOT/ui-service/dist/index.html" "UI 静态产物，先跑 bash scripts/build.sh"

# 模型配置提示：没有 key 时用 mock，演示可讲流程但回答是模板
set -a; . "$ROOT/config/.env" 2>/dev/null || true; set +a
if [ -z "${DORM_AI_API_KEY:-}" ] && [ "${DORM_AGENT_MOCK:-false}" != "true" ]; then
  warn "DORM_AI_API_KEY 为空且 DORM_AGENT_MOCK!=true：AI 助手会报错。"
  warn "  要么在 config/.env 填真实 key，要么设 DORM_AGENT_MOCK=true 用离线模拟模型。"
fi

if [ "$fail" -eq 1 ]; then
  echo
  echo "环境未就绪。完整步骤："
  echo "  bash scripts/env-install.sh      # 可选，无 Docker 时准备本机运行时"
  echo "  bash scripts/env-start.sh        # 启动 MySQL(3307) / Redis(6380)"
  echo "  bash scripts/build.sh            # 构建两个 jar + UI"
  exit 1
fi

[ "$MODE" = "check" ] && { log "前置检查通过"; exit 0; }

# ---------------------------------------------------------------- 起服务
bash "$ROOT/scripts/env-start.sh" || { warn "依赖启动失败"; exit 1; }
bash "$ROOT/scripts/start.sh"     || { warn "服务启动失败，见 logs/"; exit 1; }

# ---------------------------------------------------------------- 冒烟
echo
if bash "$ROOT/scripts/smoke.sh"; then
  smoke_ok=1
else
  smoke_ok=0
  warn "冒烟未全通过，先看 logs/dorm-service.log / agent-service.log"
fi

# ---------------------------------------------------------------- 演示信息
cat <<EOF

$(printf "${c_cyan}==================== 演示入口 ====================${c_reset}")

  员工端   http://127.0.0.1:${UI_PORT}/rooms          （zhangwei / Dorm@123456）
  管理端   http://127.0.0.1:${UI_PORT}/admin/dashboard（superadmin / Dorm@123456）
  AI 助手  http://127.0.0.1:${UI_PORT}/agent
  接口文档 http://127.0.0.1:${DORM_PORT}/swagger-ui.html

$(printf "${c_cyan}==================== 演示路线（约 5 分钟）====================${c_reset}")

  1. 房源查询     选区域 -> 看房态/床位        「业务数据权限：区域管理员只看本区」
  2. 提交申请     填入住日期 -> 提交           「状态机 + 幂等：重复提交不产生脏数据」
  3. 管理端审批   superadmin 审批 -> 分配床位  「并发抢床：一条 SQL 条件更新，防超卖」
  4. 入住 / 退房  订单页操作 -> 生成账单       「订单与房态联动、审计落库」
  5. 统计看板     入住率/区域分布              「按区域的聚合统计与缓存」
  6. AI 助手      问「新加坡出差怎么申请宿舍」 「RAG 引用 + 工具调业务 HTTP + SSE 流式」
  7. Agent 运营看板 模型健康/用量/知识库        「可观测：token 用量、RAG 命中、工具审计」
  8. 审计日志     最近操作                     「谁在什么时候改了什么，可回溯」

$(printf "${c_dim}停止： bash scripts/demo.sh --stop    （加 --all 连 MySQL/Redis 一起停）${c_reset}")

EOF

[ "$smoke_ok" -eq 1 ] && exit 0 || exit 1
