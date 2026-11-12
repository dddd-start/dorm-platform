#!/usr/bin/env bash
# 停止项目自带的 MySQL / Redis（数据保留）。
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/env.sh"

MYSQL_DIR="$ROOT/env/mysql"
REDIS_DIR="$ROOT/env/redis"
MYSQL_LIB="$MYSQL_DIR/root/usr/lib/x86_64-linux-gnu"

printf '[stop] 关闭 Redis ...\n'
LD_LIBRARY_PATH="$REDIS_DIR/lib" "$REDIS_DIR/bin/redis-cli" -h 127.0.0.1 -p 6380 \
  -a "${DORM_REDIS_PASSWORD:-}" --no-auth-warning shutdown nosave 2>/dev/null || true
rm -f "$REDIS_DIR/run/redis.pid"

printf '[stop] 关闭 MySQL ...\n'
LD_LIBRARY_PATH="$MYSQL_LIB" "$MYSQL_DIR/root/usr/bin/mysqladmin" \
  --defaults-file="$MYSQL_DIR/my.cnf" -uroot shutdown 2>/dev/null || true
rm -f "$MYSQL_DIR/run/mysqld.pid"
printf '[stop] 完成\n'
