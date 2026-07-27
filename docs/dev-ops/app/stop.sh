#!/usr/bin/env sh
set -eu

CONTAINER_NAME="${CONTAINER_NAME:-ai-agent-scaffold-app}"
STOP_TIMEOUT="${STOP_TIMEOUT:-35}"

# 停止等待时间必须大于 Spring 的 30s shutdown phase，确保在途请求可正常收尾。
if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  docker stop --time "${STOP_TIMEOUT}" "${CONTAINER_NAME}"
fi
