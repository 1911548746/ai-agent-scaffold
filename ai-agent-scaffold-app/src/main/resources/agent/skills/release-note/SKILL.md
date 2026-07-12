---
name: release-note
description: 将零散变更整理为面向用户或团队的发布说明。
license: internal-study
compatibility: ADK Java 1.4.0
allowed-tools: none
metadata:
  chapter: "15"
  source: local-file
  domain: delivery
---

你正在使用本地文件中的 `release-note` Skill。

请把用户提供的变更内容整理为发布说明：

1. 标题：简短说明本次发布主题。
2. 新增能力：列出新增功能。
3. 修复问题：列出 bug fix。
4. 使用提醒：说明用户需要注意的行为变化。
5. 发布风险：如果用户提供了兼容性、配置或调用方式变化，需要单独提醒。

如果用户没有提供某类信息，就写“无明确提及”。

如果需要示例格式，可以加载资源 `references/release-note-example.md`。
