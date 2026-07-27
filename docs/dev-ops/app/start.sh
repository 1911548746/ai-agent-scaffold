#!/usr/bin/env sh
set -eu

CONTAINER_NAME="${CONTAINER_NAME:-ai-agent-scaffold-app}"
IMAGE_NAME="${IMAGE_NAME:-ai-agent-scaffold-app:1.0}"
APP_NETWORK="${COMPOSE_NETWORK_NAME:-ai-agent-scaffold-network}"
HOST_SERVER_PORT="${HOST_SERVER_PORT:-8080}"
HOST_MANAGEMENT_PORT="${HOST_MANAGEMENT_PORT:-8081}"
STOP_TIMEOUT="${STOP_TIMEOUT:-35}"

# 容器以 prod profile 运行，缺失外部依赖配置时应在启动前明确失败而不是回退到开发默认值。
: "${MODEL_API_KEY:?请设置 MODEL_API_KEY}"
: "${SPRING_DATASOURCE_URL:?请设置 SPRING_DATASOURCE_URL}"
: "${SPRING_DATASOURCE_USERNAME:?请设置 SPRING_DATASOURCE_USERNAME}"
: "${SPRING_DATASOURCE_PASSWORD:?请设置 SPRING_DATASOURCE_PASSWORD}"

echo "容器部署开始 ${CONTAINER_NAME}"

# 数据库环境 Compose 未运行时也可先创建网络；服务需要自行保证数据库可达。
docker network inspect "${APP_NETWORK}" >/dev/null 2>&1 || docker network create "${APP_NETWORK}" >/dev/null

# 已存在的容器必须先等待 SIGTERM 优雅关闭，不能用 rm -f 直接中断在途请求。
if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  docker stop --time "${STOP_TIMEOUT}" "${CONTAINER_NAME}"
  docker rm "${CONTAINER_NAME}"
fi

docker run --detach \
  --name "${CONTAINER_NAME}" \
  --init \
  --restart unless-stopped \
  --stop-timeout "${STOP_TIMEOUT}" \
  --security-opt no-new-privileges:true \
  --network "${APP_NETWORK}" \
  --publish "${HOST_SERVER_PORT}:8080" \
  --publish "127.0.0.1:${HOST_MANAGEMENT_PORT}:8081" \
  --env TZ="${TZ:-Asia/Shanghai}" \
  --env SERVER_PORT=8080 \
  --env MANAGEMENT_SERVER_ADDRESS=0.0.0.0 \
  --env MANAGEMENT_SERVER_PORT=8081 \
  --env SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}" \
  --env SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE="${SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE:-30s}" \
  --env MODEL_API_KEY \
  --env SPRING_DATASOURCE_URL \
  --env SPRING_DATASOURCE_USERNAME \
  --env SPRING_DATASOURCE_PASSWORD \
  "${IMAGE_NAME}"

echo "容器部署成功 ${CONTAINER_NAME}"

docker logs --follow "${CONTAINER_NAME}"
