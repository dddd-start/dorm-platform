#!/usr/bin/env bash
# 查看三服务与依赖的状态。
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

MYSQL_DIR="$ROOT/env/mysql"
REDIS_DIR="$ROOT/env/redis"
MYSQL_LIB="$MYSQL_DIR/root/usr/lib/x86_64-linux-gnu"

echo "==================== dorm-platform 状态 ===================="
printf -- '--- 版本 ---\n'
"$ROOT/env/maven/apache-maven-3.9.16/bin/mvn" -v 2>/dev/null | head -1
java -version 2>&1 | head -1
node -v 2>/dev/null || true

printf -- '--- MySQL (3307) ---\n'
if LD_LIBRARY_PATH="$MYSQL_LIB" "$MYSQL_DIR/root/usr/bin/mysqladmin" \
     --defaults-file="$MYSQL_DIR/my.cnf" -uroot ping 2>/dev/null | grep -q alive; then
  LD_LIBRARY_PATH="$MYSQL_LIB" "$MYSQL_DIR/root/usr/bin/mysql" \
    --defaults-file="$MYSQL_DIR/my.cnf" -uroot -N -e \
    "SELECT CONCAT('  databases: ', GROUP_CONCAT(schema_name)) FROM information_schema.schemata WHERE schema_name IN ('dorm_db','agent_db');" 2>/dev/null
  echo "  状态: 运行中"
else
  echo "  状态: 未运行"
fi

printf -- '--- Redis (6380) ---\n'
PONG=$(LD_LIBRARY_PATH="$REDIS_DIR/lib" "$REDIS_DIR/bin/redis-cli" -h 127.0.0.1 -p 6380 \
  -a "${DORM_REDIS_PASSWORD:-}" --no-auth-warning ping 2>/dev/null || true)
[ "$PONG" = "PONG" ] && echo "  状态: 运行中" || echo "  状态: 未运行"

printf -- '--- 三服务 ---\n'
check_port() {
  local name="$1" port="$2" pidfile="$3"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; then
    echo "  $name: 运行中 (pid $(cat "$pidfile"), :$port)"
  elif ss -lnt 2>/dev/null | grep -q ":$port "; then
    echo "  $name: 端口 $port 被占用（非本脚本启动）"
  else
    echo "  $name: 未运行"
  fi
}
check_port dorm-service "$DORM_PORT" "$DORM_RUN_DIR/dorm-service.pid"
check_port agent-service "$DORM_AGENT_PORT" "$DORM_RUN_DIR/agent-service.pid"
check_port ui-service "$UI_PORT" "$DORM_RUN_DIR/ui-service.pid"
echo "============================================================"
