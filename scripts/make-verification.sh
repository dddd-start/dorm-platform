#!/usr/bin/env bash
# 从 Maven Surefire 原始输出生成验收报告（docs/verification/report.md）。
# 用法：bash scripts/make-verification.sh
# 前置：已跑过 `mvn test`（否则没有 surefire 报告）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
python3 scripts/make-verification.py
