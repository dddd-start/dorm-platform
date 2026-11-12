#!/usr/bin/env bash
# =============================================================================
# dorm-platform 公共环境变量
# 用法： source scripts/env.sh
# 所有运行时与数据都在项目目录内，不写系统目录、不动 ~/.m2。
# =============================================================================
set -uo pipefail

DORM_PLATFORM_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export DORM_PLATFORM_HOME

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export M2_HOME="$DORM_PLATFORM_HOME/env/maven/apache-maven-3.9.16"
export PATH="$M2_HOME/bin:$JAVA_HOME/bin:${DORM_NODE_BIN:-/usr/local/bin}:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1536m -Dfile.encoding=UTF-8}"
export DORM_M2_REPO="$DORM_PLATFORM_HOME/data/m2"

# 运行时目录
export DORM_ENV_DIR="$DORM_PLATFORM_HOME/env"
export DORM_DATA_DIR="$DORM_PLATFORM_HOME/data"
export DORM_LOG_DIR="$DORM_PLATFORM_HOME/logs"
export DORM_RUN_DIR="$DORM_PLATFORM_HOME/run"
mkdir -p "$DORM_LOG_DIR" "$DORM_RUN_DIR"

# 凭据
if [ -f "$DORM_PLATFORM_HOME/config/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$DORM_PLATFORM_HOME/config/.env"
  set +a
else
  echo "[env] 未找到 config/.env，请先执行 bash scripts/env-install.sh" >&2
fi

# Maven 仓库固定到项目内
export MAVEN_ARGS="-Dmaven.repo.local=$DORM_M2_REPO ${MAVEN_ARGS:-}"

# 统一端口默认值
export DORM_PORT="${DORM_PORT:-8081}"
export DORM_AGENT_PORT="${DORM_AGENT_PORT:-8082}"
export UI_PORT="${UI_PORT:-8080}"
export DORM_MYSQL_PORT="${DORM_MYSQL_PORT:-3307}"
export DORM_REDIS_PORT="${DORM_REDIS_PORT:-6380}"
