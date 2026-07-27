
#!/usr/bin/env sh
set -eu

# Dockerfile 需要读取所有 Maven 模块，因此构建上下文必须是仓库根目录而不是 app 模块目录。
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)"
IMAGE_NAME="${IMAGE_NAME:-ai-agent-scaffold-app}"
IMAGE_TAG="${IMAGE_TAG:-1.0}"

docker build --tag "${IMAGE_NAME}:${IMAGE_TAG}" --file "${SCRIPT_DIR}/Dockerfile" "${PROJECT_ROOT}"
