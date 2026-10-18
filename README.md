# ai-agent-scaffold

`ai-agent-scaffold` 是一个基于 Java 17、Spring Boot、Google ADK Java 和 LangChain4j 的配置驱动 Agent 工程脚手架。项目通过模块化 YAML catalog 描述模型、工具、Skill、Agent、Workflow 和 Application，再将配置编译为可运行的 Agent Runtime。

项目适合用于学习、PoC、团队初始化模板和受控环境服务。它提供了较完整的工程骨架，但不能仅凭离线测试或健康检查结果推断为公网、多租户生产系统。

## 核心能力

- 配置驱动的 `LlmAgent`、顺序 Workflow、并行 Workflow 和循环 Workflow。
- Local Function Tool、MCP Tool、Skill Toolset 和 Plugin。
- 同步对话、POST SSE 流式对话和多模态对话。
- Session、Event、Conversation、Message 和工具确认流程。
- `memory`、`local`、`minio` 三种 Artifact 存储方式。
- 上下文压缩、Event 清理、Runtime generation、Lease 和优雅切换。
- 外部 catalog、文件监听、防抖、Digest 去重、自动重试和发布历史。
- Actuator、Micrometer、Prometheus、Tracing、Flyway 和容器化基础设施。
- 独立的 ADK Web 开发调试模块。

## 快速开始

### 环境要求

- JDK 17
- Maven 3.8.1 或更高版本
- MySQL（默认 Session、Conversation 和附件元数据会使用数据库）
- 可访问的模型服务，并通过环境变量提供模型密钥

默认测试保持离线，不要求真实模型、MCP、MinIO 或 Docker。真实外部服务测试需要显式配置并在受控环境中执行。

### 配置数据库和模型

开发配置位于 `ai-agent-scaffold-app/src/main/resources/application-dev.yml`。启动前请根据本地环境覆盖数据库连接信息，并提供模型配置中引用的密钥，例如：

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3306/ai_agent_scaffold"
$env:SPRING_DATASOURCE_USERNAME = "<database-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<database-password>"
$env:MODEL_API_KEY = "<model-api-key>"
$env:JWT_SECRET = "<at-least-32-character-secret>"
```

模型连接、模型名称和 Agent 关联关系位于 `ai-agent-scaffold-app/src/main/resources/agent/models/` 与 `agent/agents/`。

### 编译和启动

在仓库根目录执行：

```bash
mvn clean test
mvn -pl ai-agent-scaffold-app -am spring-boot:run
```

也可以先打包再启动：

```bash
mvn -pl ai-agent-scaffold-app -am package
java -jar ai-agent-scaffold-app/target/ai-agent-scaffold-app.jar
```

业务接口默认监听 `http://localhost:8080`，Actuator 默认监听 `http://127.0.0.1:8081`。

### 注册、登录并发起对话

在线模式下先注册或登录，取得访问令牌：

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"<password>"}'

curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"<password>"}'
```

创建会话并发送消息：

```bash
curl -X POST http://localhost:8080/api/v1/create-session \
  -H "Authorization: Bearer <access-token>" \
  -H "Content-Type: application/json" \
  -d '{"agentId":"100000"}'

curl -X POST http://localhost:8080/api/v1/chat \
  -H "Authorization: Bearer <access-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "agentId":"100000",
    "sessionId":"<session-id>",
    "message":"你好"
  }'
```

可用 Agent 也可以通过 `GET /api/v1/query-agent-list` 查询。流式接口使用 `POST /api/v1/chat-stream`，多模态接口使用 `POST /api/v1/chat-multimodal` 或 `POST /api/v1/chat-multimodal-stream`。

## 最小配置示例

一个 Application 将 Agent 或 Workflow 发布为可调用的 Runtime：

```yaml
ai:
  agent:
    application:
      id: hello-application
      app-name: HelloApplication
      agent-id: 100001
      agent-name: Hello Agent
      agent-desc: 一个简单的对话 Agent
      agent-ref: hello-agent
      runner:
        session-storage: true
        artifact-storage:
          type: local
        context-compress:
          type: slidingWindow
          compaction-interval: 12
          overlap-size: 2
```

`app-name` 是 Runtime 路由和 Artifact 前缀使用的稳定名称，必须唯一。`agent-ref` 与 `workflow-ref` 二选一。完整模板见 `ai-agent-scaffold-app/src/main/resources/agent/applications/application-template.yml.example`。

## 配置模型

默认 catalog 位于 `ai-agent-scaffold-app/src/main/resources/agent/catalog/default-catalog.yml`，正式资源按职责分目录：

```text
agent/
├── catalog/          catalog 入口
├── models/            模型连接和调用参数
├── tools/             Local Tool 和 MCP Tool
├── skills/            Skill Toolset 入口
├── skill-resources/   SKILL.md 和引用资料
├── agents/            LLM Agent 定义
├── workflows/         顺序、并行、循环和基础 LLM Workflow
└── applications/      对外发布的 Agent Runtime
```

Loader 会按 catalog 声明发现资源，执行绑定、引用解析、唯一性校验、工作流图校验和 Definition 编译。`.example` 文件只作为模板，不会被正式资源扫描。

### 外部 catalog

生产环境可以将完整 catalog 放在 JAR 外部，通过 `AI_AGENT_CATALOG_PATH` 指定：

```powershell
$env:AI_AGENT_CATALOG_PATH = "file:///etc/ai-agent-scaffold/agent/catalog/default-catalog.yml"
```

外部 catalog 应声明完整配置快照所需的所有模块资源；相对资源路径以 catalog 文件所在目录为基准解析。外部配置会替换 classpath 默认配置；缺少资源或格式错误时启动或 Reload 直接失败，不会静默回退。

### Artifact 存储

每个 Application 可以独立选择 Artifact 后端：

| 类型 | 用途 | 持久性 |
|---|---|---|
| `memory` | 单元测试、开发和演示 | 重启后丢失，长期运行有内存压力 |
| `local` | 单机部署和本地开发 | 写入本地目录，默认 `./data/artifacts` |
| `minio` | 多实例或对象存储部署 | 写入 MinIO，支持 TTL 和 Lifecycle |

MinIO 和本地 Artifact 的对象前缀按 Application 隔离。MinIO 部署参数位于 `application.yml` 的 `ai.agent.artifact-storage.minio`，只有实际 Application 选择 `minio` 时才创建客户端并访问 Bucket。

## 运行流程

```text
catalog + 分类 YAML
        │
        ▼
AgentDefinitionLoader
        │  绑定、引用解析、校验、configDigest
        ▼
AgentDefinition
        │
        ▼
AgentRuntimeCompiler
        │  组装 ADK App、Runner、Tool、Skill、Plugin、ArtifactService
        ▼
AgentRuntimeRegistry
        │  原子发布不可变 Runtime 快照
        ▼
Chat / Stream / Multimodal / HITL
```

运行中的请求通过 Runtime Lease 持有当前 generation。配置 Reload 会先编译完整候选 Runtime，编译成功后原子切换 Registry；旧 Runtime 进入排空状态，等在途请求释放 Lease 后再关闭其专属资源。候选编译失败时保留旧 Runtime。

文件监听默认关闭。对外部 `file:` catalog 开启监听后，连续文件变化会经过防抖；Loader 重新计算完整 `configDigest`，摘要未变化时跳过自动编译，变化后才触发候选发布。手动 Reload 始终强制重新编译，可用于恢复模型、MCP 或其他运行时资源。

## HTTP API

业务接口前缀为 `/api/v1`：

| 接口 | 用途 |
|---|---|
| `POST /auth/register` | 注册用户 |
| `POST /auth/login` | 登录并获取令牌 |
| `GET /query-agent-list` | 查询已发布 Agent |
| `POST /create-session` | 创建 Session |
| `POST /chat` | 同步文本对话 |
| `POST /chat-stream` | 文本 SSE 流式对话 |
| `POST /chat-multimodal` | 同步多模态对话 |
| `POST /chat-multimodal-stream` | 多模态 SSE 流式对话 |
| `GET /conversations/list` | 查询会话归档 |
| `GET /conversations/detail/{conversationId}` | 查询会话详情 |
| `DELETE /conversations/delete` | 删除会话归档 |
| `GET /tool-confirmations/{confirmationId}` | 查询工具确认 |
| `POST /tool-confirmations/{confirmationId}:resolve` | 同步处理工具确认 |
| `POST /tool-confirmations/{confirmationId}:resolve-stream` | 流式处理工具确认 |

业务响应统一使用项目的 `Response` 契约。用户身份从认证上下文获取，不接受请求体中的 `userId` 作为可信身份。

## Runtime Reload 和运维接口

Actuator 管理端点默认只监听本机管理端口：

```bash
curl http://127.0.0.1:8081/actuator/health
curl http://127.0.0.1:8081/actuator/info
curl http://127.0.0.1:8081/actuator/agentRuntime
curl -X POST http://127.0.0.1:8081/actuator/agentRuntime
```

`GET /actuator/agentRuntime` 返回当前 generation、`configDigest`、Runtime 数量、Reload 计数、最近失败摘要和有限数量的发布历史。`POST` 不接受 catalog 路径，使用当前已配置的完整 catalog 强制 Reload。

文件监听配置示例：

```yaml
ai:
  agent:
    runtime:
      reload:
        watch:
          enabled: true
          debounce: 10s
        retry:
          max-attempts: 3
          initial-delay: 1s
        history-size: 10
```

管理端点涉及 Runtime 发布权限，生产部署应通过网络隔离、认证和管理面访问控制进行保护。

## 项目结构

```text
api             对外 DTO、接口和公共契约
types           最小公共异常、枚举和基础类型
domain          Agent Definition、Workflow 规则和值对象
application     Chat、Conversation、HITL 用例和出站端口
trigger         HTTP 等入站适配器
infrastructure  ADK、LangChain4j、MCP、Skill、Plugin、Session 和存储适配器
app             Spring Boot 启动、配置绑定、Bean 组装、Flyway 和资源
adk-web         独立的 ADK Web 开发调试入口
```

主要依赖方向为：

```text
api -> types
domain -> types
application -> domain + types
trigger -> api + application + domain + types
infrastructure -> application + domain
app -> application + trigger + infrastructure
adk-web -> app plain artifact + google-adk-dev
```

ADK `Runner`、`Event`、RxJava、LangChain4j Provider、MCP Client 和 MyBatis 类型属于基础设施内部，不作为上层或 HTTP 契约暴露。

## 开发与测试

常用命令：

```bash
# 编译并运行默认离线测试
mvn clean test

# 只运行应用模块及其依赖模块
mvn -pl ai-agent-scaffold-app -am test

# 显式运行需要 MySQL 容器的持久化集成测试
mvn -pl ai-agent-scaffold-app -am -Ppersistence-it verify

# 显式运行需要真实模型配置的 Agent 集成测试
mvn -pl ai-agent-scaffold-app -am -Plive-agent-it verify

# 启用独立 ADK Web 开发模块
mvn -Padk-web clean package
```

默认测试不连接真实模型、生产数据库、外部 MCP 或公网服务。运行集成测试前，应确认测试配置、Docker 环境和凭据范围均明确受控。

## 扩展方式

- 新增 Agent：在 `agent/agents/` 增加 YAML，并在 Application 中填写 `agent-ref`。
- 新增 Workflow：在 `agent/workflows/` 增加 YAML，并在 Application 中填写 `workflow-ref`。
- 新增模型：在 `agent/models/` 增加模型配置，并由 Agent 使用 `model-ref` 引用。
- 新增 Tool 或 Skill：分别在 `agent/tools/`、`agent/skills/` 和 `agent/skill-resources/` 增加资源，再在 Agent 中声明引用。
- 新增存储或运行时适配器：在 application 定义端口，在 infrastructure 实现适配器，在 app 完成 Bean 组装。

遵循模块边界：领域模型不依赖 Spring、ADK、MyBatis 或具体模型供应商；HTTP 层通过 application 用例调用运行时；基础设施实现端口并隐藏 SDK 和数据库类型。

## 当前边界

以下内容仍需要在真实部署环境中单独验证和治理：

- 认证主体、租户和资源归属的完整闭环；
- Tool/MCP 的权限、审批、审计和外部幂等；
- 模型 Provider 扩展、真实 Runtime 资源生命周期和断连恢复；
- 真实 MySQL、模型、MinIO、MCP、Servlet 断连和容器环境验收；
- 长会话分页、多模态引用、Artifact 保留和容量治理；
- Agent Eval、CI、发布审批和配置版本治理。

不要将本地启动成功、默认单元测试通过、Runtime readiness 或 Docker healthcheck 视为真实模型、MCP、数据库或公网生产验收。

## 相关文档

- [Agent 配置与使用说明](ai-agent-scaffold-app/src/main/resources/agent/README.md)
- [项目分析](docs/过程文档/项目分析.md)
- [架构与模块规则](docs/agent-rules/architecture.md)
- [Agent 配置与运行时规则](docs/agent-rules/agent-configuration-runtime.md)
- [数据与流式规则](docs/agent-rules/data-streaming.md)
- [安全规则](docs/agent-rules/security.md)
- [测试与运维规则](docs/agent-rules/testing-operations.md)
- [改造计划](docs/过程文档/改造计划.md)

## License

本项目的许可证信息以仓库实际许可证文件和 Maven 项目配置为准。

