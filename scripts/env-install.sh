#!/usr/bin/env bash
# =============================================================================
# 安装本机运行时（MySQL / Redis / Maven）到项目 env/ 目录，并生成 config/.env。
#
# 两条路径：
#   1) 推荐：Docker Compose（见 deploy/docker-compose.yml），不依赖本脚本；
#   2) 无 Docker 时用本脚本：把已解包好的 MySQL 8.x / Redis 8.x / Maven 3.9.x
#      放到项目 env/ 下，或用 DORM_SOURCE_ENV 指向一个已准备好的运行时目录
#      （目录结构：mysql/root/usr/sbin/mysqld、redis/bin/redis-server、
#       maven/apache-maven-3.9.16/bin/mvn、m2/repository）。
#
# 所有数据落在本项目的 data/ 下，端口 3307 / 6380，与系统已装的 MySQL/Redis 隔离。
#
# 用法： bash scripts/env-install.sh
#        DORM_SOURCE_ENV=/opt/dorm-runtime bash scripts/env-install.sh
# 幂等：已存在的组件与数据目录会跳过。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_DIR="$ROOT/env"
DATA_DIR="$ROOT/data"
SOURCE_ENV="${DORM_SOURCE_ENV:-}"

log()  { printf '\033[32m[env-install]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[env-install]\033[0m %s\n' "$*"; }

mkdir -p "$ENV_DIR"/{mysql,redis,maven} "$DATA_DIR"/{mysql,redis,m2} "$ROOT/logs" "$ROOT/run"

# ---------------------------------------------------------------- 二进制复用
if [ ! -x "$ENV_DIR/mysql/root/usr/sbin/mysqld" ]; then
  if [ -n "$SOURCE_ENV" ] && [ -x "$SOURCE_ENV/mysql/root/usr/sbin/mysqld" ]; then
    log "复用 $SOURCE_ENV/mysql/root -> env/mysql/root"
    cp -a "$SOURCE_ENV/mysql/root" "$ENV_DIR/mysql/root"
  else
    warn "未找到 MySQL 二进制。请设置 DORM_SOURCE_ENV 指向已解包的 MySQL 8.4（含 root/usr/sbin/mysqld），或改用 deploy/docker-compose.yml 的容器路径"
  fi
else
  log "MySQL 二进制已存在，跳过"
fi

if [ ! -x "$ENV_DIR/redis/bin/redis-server" ]; then
  if [ -n "$SOURCE_ENV" ] && [ -x "$SOURCE_ENV/redis/bin/redis-server" ]; then
    log "复用 $SOURCE_ENV/redis -> env/redis"
    cp -a "$SOURCE_ENV/redis/bin" "$ENV_DIR/redis/bin"
    [ -d "$SOURCE_ENV/redis/lib" ] && cp -a "$SOURCE_ENV/redis/lib" "$ENV_DIR/redis/lib"
  else
    warn "未找到 Redis 二进制。请设置 DORM_SOURCE_ENV 指向已解包的 Redis 8.x（含 bin/redis-server），或改用容器路径"
  fi
else
  log "Redis 二进制已存在，跳过"
fi

if [ ! -x "$ENV_DIR/maven/apache-maven-3.9.16/bin/mvn" ]; then
  if [ -n "$SOURCE_ENV" ] && [ -x "$SOURCE_ENV/maven/apache-maven-3.9.16/bin/mvn" ]; then
    log "复用 $SOURCE_ENV/maven -> env/maven"
    cp -a "$SOURCE_ENV/maven/apache-maven-3.9.16" "$ENV_DIR/maven/"
  else
    warn "未找到 Maven。请设置 DORM_SOURCE_ENV 指向 Maven 3.9.x（含 apache-maven-3.9.16/bin/mvn），或改用容器路径"
  fi
else
  log "Maven 已存在，跳过"
fi

if [ ! -d "$DATA_DIR/m2/repository" ] && [ -n "$SOURCE_ENV" ] && [ -d "$SOURCE_ENV/m2/repository" ]; then
  log "复用 Maven 本地仓库缓存（加速首次构建）"
  cp -a "$SOURCE_ENV/m2/." "$DATA_DIR/m2/"
fi

# ---------------------------------------------------------------- 凭据生成
if [ ! -f "$ROOT/config/.env" ]; then
  log "生成 config/.env（随机密码，权限 600）"
  MYSQL_DORM_PWD=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)
  MYSQL_AGENT_PWD=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)
  REDIS_PWD=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)
  JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '/+=' | head -c 48)
  INTERNAL_TOKEN=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 32)
  AES_KEY=$(head -c 32 /dev/urandom | base64)
  HMAC_SECRET=$(head -c 32 /dev/urandom | base64)
  AI_KEY="${DORM_AI_API_KEY:-}"
  cat > "$ROOT/config/.env" <<EOF
# 本地运行凭据 —— 请勿提交（.gitignore 已忽略）
DORM_MYSQL_PORT=3307
DORM_REDIS_PORT=6380
DORM_DB_URL=jdbc:mysql://127.0.0.1:3307/dorm_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
DORM_DB_USER=dorm
DORM_DB_PASSWORD=$MYSQL_DORM_PWD
DORM_AGENT_DB_URL=jdbc:mysql://127.0.0.1:3307/agent_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
DORM_AGENT_DB_USER=agent
DORM_AGENT_DB_PASSWORD=$MYSQL_AGENT_PWD
DORM_REDIS_HOST=127.0.0.1
DORM_REDIS_PASSWORD=$REDIS_PWD
DORM_JWT_SECRET=$JWT_SECRET
DORM_INTERNAL_TOKEN=$INTERNAL_TOKEN
DORM_PHONE_AES_KEY=$AES_KEY
DORM_PHONE_HMAC_SECRET=$HMAC_SECRET
DORM_SERVICE_URL=http://127.0.0.1:8081
UI_PORT=8080
DORM_PORT=8081
DORM_AGENT_PORT=8082
DORM_AI_BASE_URL=https://api.commandcode.ai/provider/v1
DORM_AI_API_KEY=$AI_KEY
DORM_AI_MODEL_PRIMARY=deepseek/deepseek-v4.1-flash
DORM_AI_MODEL_FAST=deepseek/deepseek-v4.1-flash-fast
DORM_AGENT_MOCK=${DORM_AGENT_MOCK:-false}
EOF
  chmod 600 "$ROOT/config/.env"
else
  log "config/.env 已存在，保留现有凭据"
fi

# ---------------------------------------------------------------- MySQL 配置
mkdir -p "$DATA_DIR/mysql" "$ENV_DIR/mysql"/{run,logs,files}
cat > "$ENV_DIR/mysql/my.cnf" <<EOF
[client]
socket = $ENV_DIR/mysql/run/mysql.sock
port   = 3307

[mysqld]
basedir                 = $ENV_DIR/mysql/root/usr
datadir                 = $DATA_DIR/mysql
socket                  = $ENV_DIR/mysql/run/mysql.sock
pid-file                = $ENV_DIR/mysql/run/mysqld.pid
log-error               = $ENV_DIR/mysql/logs/error.log
secure-file-priv        = $ENV_DIR/mysql/files
port                    = 3307
bind-address            = 127.0.0.1
mysqlx                  = OFF
character-set-server    = utf8mb4
collation-server        = utf8mb4_0900_ai_ci
default-time-zone       = '+08:00'
innodb_buffer_pool_size = 512M
max_connections         = 300
slow_query_log          = 1
slow_query_log_file     = $ENV_DIR/mysql/logs/slow.log
long_query_time         = 1
EOF

if [ ! -d "$DATA_DIR/mysql/mysql" ]; then
  log "初始化 MySQL 数据目录（首次）"
  LD_LIBRARY_PATH="$ENV_DIR/mysql/root/usr/lib/x86_64-linux-gnu" \
    "$ENV_DIR/mysql/root/usr/sbin/mysqld" --defaults-file="$ENV_DIR/mysql/my.cnf" \
    --initialize-insecure >/dev/null 2>&1
  log "MySQL 初始化完成"
else
  log "MySQL 数据目录已存在，跳过初始化"
fi

# ---------------------------------------------------------------- Redis 配置
mkdir -p "$DATA_DIR/redis" "$ENV_DIR/redis"/{run,logs}
# shellcheck disable=SC1091
set -a; . "$ROOT/config/.env"; set +a
cat > "$ENV_DIR/redis/redis.conf" <<EOF
bind 127.0.0.1
port 6380
daemonize no
pidfile $ENV_DIR/redis/run/redis.pid
logfile $ENV_DIR/redis/logs/redis.log
dir $DATA_DIR/redis
dbfilename dump.rdb
appendonly yes
appendfilename "appendonly.aof"
appenddirname "appendonlydir"
maxmemory 512mb
maxmemory-policy allkeys-lru
save 900 1
save 300 10
timeout 0
tcp-keepalive 60
requirepass $DORM_REDIS_PASSWORD
EOF

log "环境安装完成。下一步：bash scripts/env-start.sh"
