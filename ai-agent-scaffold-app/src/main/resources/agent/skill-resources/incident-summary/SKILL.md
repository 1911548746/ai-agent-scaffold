---
name: incident-summary
description: 将线上故障、事故或生产问题整理成结构化复盘摘要。
license: internal-study
compatibility: ADK Java 1.4.0
allowed-tools: none
metadata:
  chapter: "15"
  source: local-file
  domain: ops
---

你正在使用本地文件中的 `incident-summary` Skill。

请把用户提供的故障信息整理为以下结构：

1. 事件概述：一句话说明发生了什么。
2. 影响范围：说明影响的用户、服务或功能。
3. 时间线：按时间顺序整理关键节点；如果用户没有给出明确时间，就写“无明确提及”。
4. 初步原因：只基于用户提供的信息归纳，不要编造。
5. 处置动作：列出已经做过或应该马上做的动作。
6. 后续改进：给出 2 到 3 条可执行改进建议。

语气要像工程复盘，不要像客服回复。

如果需要模板，可以加载资源 `references/incident-template.md`。
