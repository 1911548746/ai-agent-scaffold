# 容器部署说明

本目录统一使用以下名称，构建、Compose、脚本与镜像仓库不得另行改名：

| 项目 | 值 |
|---|---|
| Maven 可执行产物 | `ai-agent-scaffold-app.jar` |
| 本地镜像 | `ai-agent-scaffold-app:1.0` |
| Compose 服务 | `ai-agent-scaffold-app` |
| 默认业务端口 | `8080` |
| 默认管理端口 | `8081` |
| 共享 Docker 网络 | `ai-agent-scaffold-network` |

## 构建镜像

在仓库根目录执行：

```sh
./ai-agent-scaffold-app/build.sh
```

Dockerfile 使用多阶段构建，会在 Builder 阶段通过 Maven 生成
`ai-agent-scaffold-app.jar`，运行阶段仅包含 Eclipse Temurin Java 17 JRE。镜像
以内置的 UID/GID `10001` 非 root 用户运行，使用 `/readyz` 检查应用、数据库和
Agent Runtime 是否就绪。

## 启动依赖环境

应用通过共享网络连接 MySQL。先从本目录启动开发依赖环境：

```sh
docker compose -f docs/dev-ops/docker-compose-environment.yml up -d
```

需要隔离环境时，在两份 Compose 命令中传入相同的
`COMPOSE_NETWORK_NAME`。阿里云镜像环境使用
`docker-compose-environment-aliyun.yml`。

## 使用 Compose 启动应用

先由 CI、密钥管理系统或当前终端设置下列变量，勿将真实值写入 YAML、脚本或 Git：

```text
MODEL_API_KEY
JWT_SECRET
AI_APPLICATION_MODE（默认 online）
AI_CORS_ALLOWED_ORIGINS（线上模式必填）
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
```

Application 的 `runner.artifact-storage.type` 默认是 `local`。`memory` 仅适用于不持久化
Session 的开发、测试和演示场景；当 `runner.sessionStorage=true` 时，Loader 会拒绝 `memory`，避免
应用重启后 Session 仍存在但 Artifact 已丢失。选择 `minio` 时还需提供
`MINIO_ENDPOINT`、`MINIO_ACCESS_KEY` 和 `MINIO_SECRET_KEY`，Bucket 可通过
`MINIO_ATTACHMENT_BUCKET` 覆盖；选择 `local` 时可通过 `LOCAL_ARTIFACT_ROOT` 覆盖默认的
`./data/artifacts`。没有 Application 选择 MinIO 时，应用不会创建 MinIO 客户端或访问 Bucket。

## 外部 Agent Catalog

生产环境可设置 `AI_AGENT_CATALOG_PATH` 指向一份完整 catalog，例如
`file:/etc/ai-agent-scaffold/agent/catalog/default-catalog.yml`。设置后该文件会完整替换 JAR 内置的
`classpath:agent/catalog/default-catalog.yml`，不会与内置模块目录追加或合并。外部目录应保持如下结构：

```text
/etc/ai-agent-scaffold/agent/
├── catalog/default-catalog.yml
├── models/
├── tools/
├── skills/
├── agents/
├── workflows/
└── applications/
```

catalog 中模块资源可使用相对路径，例如 `../models/**/*.yml`；解析时以 catalog 所在位置为基准，
且不得越过上述外部配置根目录。外部 catalog 缺失或校验失败会使启动失败，不会静默回退到内置配置。
应用启动后不会监听文件变更，也不支持配置追加或合并。可以通过仅绑定本机的管理端口手动触发完整 Reload：

```sh
curl -X POST http://127.0.0.1:8081/actuator/agentRuntime
curl http://127.0.0.1:8081/actuator/info
```

Reload 会先重新读取并编译完整外部 catalog，成功后原子切换 Runtime；失败时保留旧 Runtime。
`/actuator/info` 只返回当前 generation、配置摘要、Runtime 数量、Reload 计数、耗时和脱敏失败摘要，
不会返回模型密钥、MCP 参数或完整配置。配置摘要由 catalog 及其实际加载的模块资源内容计算得到，
可用于确认当前运行版本。
为保持既有 catalog 校验，外部 catalog 仍需保留 `ai.agent.config.catalog-path` 字段；该字段仅用于
兼容诊断，实际加载入口与相对资源基准始终以 `AI_AGENT_CATALOG_PATH` 为准。

MinIO Artifact 使用 `artifacts/{app-name}/` 前缀。Application 可单独配置 `ttl` 和
`lifecycle-enabled`：整天 TTL 会对账为对应前缀的 Bucket Lifecycle，非整天 TTL 只由应用按
`MINIO_ATTACHMENT_CLEANUP_INTERVAL` 精确清理；未配置 TTL 的数据保留到 Session 手动删除。

然后执行：

```sh
docker compose -f docs/dev-ops/docker-compose-app.yml up -d --build
```

Compose 默认使用 `prod` Profile 和 `online` 模式，业务端口发布为 `8080`，管理端口仅发布到宿主机
`127.0.0.1:8081`。可通过 `HOST_SERVER_PORT`、`HOST_MANAGEMENT_PORT`、
`APP_IMAGE`、`SPRING_PROFILES_ACTIVE`、`AI_APPLICATION_MODE`、
`AI_CORS_ALLOWED_ORIGINS` 和 `STOP_GRACE_PERIOD` 覆盖默认值。线上模式必须提供至少
32 字节的 `JWT_SECRET`；本地模式仅适合可信本机环境，不应将业务端口发布到外部网络。

## 直接使用脚本

`docs/dev-ops/app/start.sh` 使用与 Compose 相同的镜像名、端口、共享网络和必填环境变量：

```sh
./docs/dev-ops/app/start.sh
```

停止时执行：

```sh
./docs/dev-ops/app/stop.sh
```

脚本及 Compose 均为 Spring 的 `30s` 优雅停机阶段保留至少 `35s`。不要使用
`docker rm -f` 中断在途的 Agent 调用。

## 健康检查

```sh
curl http://127.0.0.1:8080/livez
curl http://127.0.0.1:8080/readyz
curl http://127.0.0.1:8081/actuator/health
```

Docker 健康检查使用 `/readyz`。该检查不主动调用模型或 MCP，避免探针产生模型费用或
外部副作用；因此 Runtime 已发布并不代表外部供应商在任意时刻均可达。
