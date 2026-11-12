#!/usr/bin/env bash
# =============================================================================
# 启动项目自带的 MySQL(3307) 与 Redis(6380)，并初始化两个独立库与账号。
# 幂等：已在运行则跳过；数据保留。
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

MYSQL_DIR="$ROOT/env/mysql"
REDIS_DIR="$ROOT/env/redis"
MYSQL_LIB="$MYSQL_DIR/root/usr/lib/x86_64-linux-gnu"
MYSQL="$MYSQL_DIR/root/usr/bin/mysql"
MYSQLADMIN="$MYSQL_DIR/root/usr/bin/mysqladmin"

log()  { printf '\033[32m[start]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[start]\033[0m %s\n' "$*"; }
running() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

start_mysql() {
  local pidfile="$MYSQL_DIR/run/mysqld.pid"
  if [ -f "$pidfile" ] && running "$(cat "$pidfile" 2>/dev/null)"; then
    log "MySQL 已在运行 (pid $(cat "$pidfile"))"
    return 0
  fi
  log "启动 MySQL (3307) ..."
  mkdir -p "$MYSQL_DIR"/{run,logs} "$ROOT/data/mysql"
  setsid --fork env LD_LIBRARY_PATH="$MYSQL_LIB" "$MYSQL_DIR/root/usr/sbin/mysqld" \
    --defaults-file="$MYSQL_DIR/my.cnf" >> "$MYSQL_DIR/logs/stdout.log" 2>&1 < /dev/null || true
  for _ in $(seq 1 60); do
    if LD_LIBRARY_PATH="$MYSQL_LIB" "$MYSQLADMIN" --defaults-file="$MYSQL_DIR/my.cnf" \
         -uroot ping >/dev/null 2>&1; then
      log "MySQL 就绪 (127.0.0.1:3307)"
      return 0
    fi
    sleep 1
  done
  warn "MySQL 60s 内未就绪，请查看 $MYSQL_DIR/logs/error.log"
  return 1
}

start_redis() {
  local pidfile="$REDIS_DIR/run/redis.pid"
  if [ -f "$pidfile" ] && running "$(cat "$pidfile" 2>/dev/null)"; then
    log "Redis 已在运行 (pid $(cat "$pidfile"))"
    return 0
  fi
  log "启动 Redis (6380) ..."
  mkdir -p "$REDIS_DIR"/{run,logs} "$ROOT/data/redis"
  setsid --fork env LD_LIBRARY_PATH="$REDIS_DIR/lib" "$REDIS_DIR/bin/redis-server" \
    "$REDIS_DIR/redis.conf" >> "$REDIS_DIR/logs/stdout.log" 2>&1 < /dev/null || true
  for _ in $(seq 1 30); do
    if LD_LIBRARY_PATH="$REDIS_DIR/lib" "$REDIS_DIR/bin/redis-cli" -h 127.0.0.1 -p 6380 \
         -a "${DORM_REDIS_PASSWORD:-}" --no-auth-warning ping 2>/dev/null | grep -q PONG; then
      log "Redis 就绪 (127.0.0.1:6380)"
      return 0
    fi
    sleep 1
  done
  warn "Redis 30s 内未就绪，请查看 $REDIS_DIR/logs/redis.log"
  return 1
}

init_databases() {
  local sql
  sql=$(cat <<EOSQL
CREATE DATABASE IF NOT EXISTS \`dorm_db\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS \`agent_db\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${DORM_DB_USER:-dorm}'@'127.0.0.1' IDENTIFIED BY '${DORM_DB_PASSWORD}';
CREATE USER IF NOT EXISTS '${DORM_DB_USER:-dorm}'@'localhost' IDENTIFIED BY '${DORM_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`dorm_db\`.* TO '${DORM_DB_USER:-dorm}'@'127.0.0.1';
GRANT ALL PRIVILEGES ON \`dorm_db\`.* TO '${DORM_DB_USER:-dorm}'@'localhost';
CREATE USER IF NOT EXISTS '${DORM_AGENT_DB_USER:-agent}'@'127.0.0.1' IDENTIFIED BY '${DORM_AGENT_DB_PASSWORD}';
CREATE USER IF NOT EXISTS '${DORM_AGENT_DB_USER:-agent}'@'localhost' IDENTIFIED BY '${DORM_AGENT_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`agent_db\`.* TO '${DORM_AGENT_DB_USER:-agent}'@'127.0.0.1';
GRANT ALL PRIVILEGES ON \`agent_db\`.* TO '${DORM_AGENT_DB_USER:-agent}'@'localhost';
FLUSH PRIVILEGES;
EOSQL
)
  printf '%s\n' "$sql" | LD_LIBRARY_PATH="$MYSQL_LIB" "$MYSQL" \
      --defaults-file="$MYSQL_DIR/my.cnf" -uroot >/dev/null 2>&1 \
    && log "dorm_db / agent_db 与账号已就绪" \
    || warn "数据库初始化失败（可稍后重试）"
}

start_mysql
start_redis
init_databases
log "环境启动完成。状态：bash scripts/env-status.sh"
