#!/usr/bin/env sh
set -eu

# 镜像名与 build.sh、Compose 和启动脚本保持一致；仓库地址可通过环境变量覆盖。
ALIYUN_REGISTRY="${ALIYUN_REGISTRY:-registry.cn-hangzhou.aliyuncs.com}"
NAMESPACE="${NAMESPACE:-system}"
IMAGE_NAME="${IMAGE_NAME:-ai-agent-scaffold-app}"
IMAGE_TAG="${IMAGE_TAG:-1.0}"
LOCAL_IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
REMOTE_IMAGE="${ALIYUN_REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${IMAGE_TAG}"

# 凭据只允许从未跟踪的本地文件或 CI 环境变量获得，脚本本身不保存任何值。
if [ -f ".local-config" ]; then
  # shellcheck disable=SC1091
  . ".local-config"
else
  echo ".local-config 文件不存在，请创建并填写 ALIYUN_USERNAME 和 ALIYUN_PASSWORD"
  exit 1
fi

# 密码仅通过 stdin 传递，避免出现在进程参数或脚本输出中。
echo "Logging into Aliyun Docker Registry..."
printf '%s' "${ALIYUN_PASSWORD}" | docker login --username="${ALIYUN_USERNAME}" --password-stdin "${ALIYUN_REGISTRY}"

echo "Tagging the Docker image..."
docker tag "${LOCAL_IMAGE}" "${REMOTE_IMAGE}"

echo "Pushing the Docker image..."
docker push "${REMOTE_IMAGE}"

echo "Docker image pushed successfully!"
echo "检出地址：docker pull ${REMOTE_IMAGE}"
echo "标签设置：docker tag ${REMOTE_IMAGE} ${LOCAL_IMAGE}"

docker logout "${ALIYUN_REGISTRY}"
