# Agent 配置目录

本目录保存模块化 Agent catalog 及其分类资源。配置由 `catalog/default-catalog.yml` 统一声明，Loader
再按 `models`、`tools`、`skills`、`agents`、`workflows` 和 `applications` 分别读取。

各目录中的 `*.yml.example` 和 `*.md.example` 仅用于复制参考，不会被 Loader 自动发现。创建正式配置时，
请复制对应模板并去掉 `.example` 后缀，再把正式文件放入 catalog 声明的目录中。

| 目录 | 用途 | 模板 |
| --- | --- | --- |
| `catalog` | 声明本次加载的完整资源集合 | `catalog-template.yml.example` |
| `models` | 模型连接和调用参数 | `model-template.yml.example` |
| `tools` | Local Tool 或 MCP Tool | `tool-template.yml.example` |
| `skills` | Skill Toolset 的目录入口 | `skill-template.yml.example` |
| `skill-resources` | Skill 的 `SKILL.md` 和引用文件 | `SKILL.md.example` |
| `agents` | LLM Agent 定义 | `agent-template.yml.example` |
| `workflows` | 顺序、并行、循环或基础 LLM 工作流 | `workflow-template.yml.example` |
| `applications` | 对外发布的 Agent Runtime | `application-template.yml.example` |

配置步骤：

1. 先创建模型、工具、Skill、Agent 或 Workflow 配置，并保证各文件的 `id` 唯一。
2. 在 Application 中通过 `agent-ref` 或 `workflow-ref` 选择根能力，并填写 `app-name`。
3. 在 catalog 中声明正式资源路径；模板文件的 `.example` 后缀不会匹配 `**/*.yml`。
4. 使用外部 catalog 时，需要提供一份完整配置快照，并将入口配置为 `file:` 地址；不要只复制单个模块文件期待自动生效。

模型密钥、MCP 凭据等敏感值请通过环境变量或部署配置注入，不要写入仓库。Skill 的 `directory` 必须指向
包含 `SKILL.md` 的目录，引用文件使用该 Skill 目录下的相对路径。
