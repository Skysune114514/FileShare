#!/usr/bin/env bash
# FileShare 一键部署（WSL / Linux / Git Bash 下运行）
# 用法：bash scripts/deploy.sh   （或 chmod +x 后 ./scripts/deploy.sh）
set -euo pipefail
cd "$(dirname "$0")/.."

# 首次运行自动生成 .env（模板）
if [ ! -f .env ]; then
  cp .env.example .env
  echo ">> 已从 .env.example 生成 .env。国内网络或需要改口令时，请先编辑 .env，例如："
  echo "   REGISTRY_PREFIX=docker.m.daocloud.io/library/"
fi

docker compose up -d --build

echo
echo ">> 启动完成。查看状态： docker compose ps"
echo ">> 访问： http://127.0.0.1/  （Docker Desktop + WSL2 下用 127.0.0.1 而非 localhost）"
