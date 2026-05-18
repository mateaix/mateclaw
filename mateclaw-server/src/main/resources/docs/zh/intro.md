---
title: MateClaw 项目介绍 — 自部署多智能体 AI 操作系统
description: MateClaw 是基于 Spring AI Alibaba 的开源多智能体 AI 操作系统。ReAct + Plan-and-Execute 双引擎、LLM Wiki 知识库、四层记忆系统、MCP 工具协议、8 渠道统一接入。一个 JAR 包自部署，数据不出门。
head:
  - - meta
    - name: keywords
      content: MateClaw,多智能体,AI操作系统,自部署AI,Spring AI Alibaba,ReAct,Plan-and-Execute,MCP,LLM Wiki,记忆系统,Tool Guard,开源
---

# MateClaw — 自部署多智能体 AI 操作系统

**你的多智能体 AI，跑在你自己的机器上，按你自己的规则。**

MateClaw 是一整套可以自部署的 AI 操作系统。一个 JAR 包，一套登录，数据不出门。

**它和别的 AI 不一样的三件事——**

**主动**：它在该出现的时候自己出现。每天早上 9 点把简报推送到你的飞书，竞品有大动作直接 ping 你的钉钉。**不在浏览器 tab 里等你。** → [主动型 AI](./ambient-ai)

**会做梦**：你睡了它跑一次整合，把今天零散的对话整合成对你的理解，写进 `MEMORY.md`。第二天它从昨天结束的地方继续，**不是从零开始**。 → [记忆系统](./memory)

**可审批**：Agent 想删文件、发邮件、写数据库——触发 Tool Guard 规则就**在回合中途暂停**，审批请求推到你的 IM，你点批准 Agent 才接着跑。**会动手，但不擅自动手。** → [安全与审批](./security)

它同时活在你的桌面、浏览器，以及团队每天在用的聊天软件里——同一个大脑，同一份记忆，跟着团队走到哪里就到哪里。

模型自己选。DashScope、OpenAI、Anthropic、Gemini、DeepSeek、Kimi、MiniMax、智谱、OpenRouter。本地跑就上 Ollama，手头有 ChatGPT Plus 账号就 OAuth 登进去直接用。先配一个，后面随时加。

---

## 它在对抗什么

市面上大多数 AI 产品只做一层。

给你一个聊天框，但明天打开就又从零开始；给你一个工具执行器，但不给你按"暂停"的机会；给你一个知识库，只会检索碎片却说不清它到底知道什么；给你桌面端，却进不了团队在用的聊天软件。或者——所有东西都给了，但全都跑在别人家的云上，你的数据顺便给别人付房租。

MateClaw 换了一个打法：**所有东西放在一个屋檐下，跑在你自己能摸到的硬件上。**

---

## 它实际在做什么

**它会把活儿干完。** Plan-and-Execute 会把复杂任务拆成有序的步骤，一步一步执行，中途哪一步炸了就重新调整。ReAct 管更小的循环——思考、行动、观察、继续。你会看到计划在滚，看到工具被调，看到思考过程，看到它最终收尾。

**它会记住。** 会话上下文、对话后的结构化提取、工作空间的记忆文件、定时整合，再加一轮"dreaming"——把昨天的线索串起来。记忆不是聊天功能上贴的一张贴纸，是系统越用越懂你的底层机制。

**它会把知识嚼碎。** 扔一份 PDF。扔一整个文件夹。扔一千篇 Markdown 笔记。LLM Wiki 会把它们消化成结构化的、带双向链接和摘要的知识页面——不是一个向量库，是一本你能翻的书。Agent 自动注入页面摘要，需要细节时再去取整页。

**它手上有真工具。** 内置工具：搜索、文件读写、shell、时间、图像、音乐、视频、语音识别、语音合成。任何别的东西都能接 MCP 服务。你自己的技能包只要写个 `SKILL.md` 就能装进工作空间。所有工具都过一层 Tool Guard，必要时还能走人工审批——手能伸得远，但边界清清楚楚。

**它会在每一个真实的工作面上出现。** Web 控制台、桌面端（内置 JRE 21，用户不需要装 Java），还有八个聊天渠道：钉钉、飞书、企业微信、微信、Telegram、Discord、QQ、Slack。Slack 里回复的 Agent 和浏览器里的 Agent 是同一个——同一份记忆、同一套技能、同一种性格。

---

## 为什么"自部署"这件事很重要

把 MateClaw 跑在你自己的机器上，不是合规打个勾那么简单。它改变的是这个产品**到底是什么**。

**你的数据不再给别人付房租。** 对话、日志、文档、记忆——没有一条拿去训练别人的模型，没有一条在别人家的队列里排队，没有一条离开你的机器，除非是你自己把某个渠道接了出去。

**路线图是你的。** 记忆整合的规则你不喜欢？自己改。需要一个厂商不给你做的工具？自己加。Apache 2.0，不是 "source available"，不是 "open core"，不用等别人的季度产品评审。

**账单是你自己算的。** 一开始上 DashScope，等本地 GPU 到了就切 Ollama，某个高价值 Agent 单独挂 OpenAI，其他的走便宜的。Agent 配置和工具图不关心底下的模型接口是什么。

**部署面是实打实的。** 一个 JAR 包。一个 Spring Boot 进程。不用装 Python，不用装 Node。桌面端自己带环境，Docker Compose 一共 18 行。

---

## 底下是什么

- **后端**——Spring Boot 3.5 + Spring AI Alibaba 1.1。Agent 运行时是一张 StateGraph，reasoning、action、observation、plan generation、step execution 都是图上的节点。MyBatis Plus 持久化。流式走 SSE（WebFlux 被明确拒之门外）。
- **前端**——Vue 3 + TypeScript。Pinia 管状态，Element Plus + Tailwind 做 UI，支持深色模式。前端 build 的产物直接进后端 JAR 的 `static/`，一个进程服务两端。
- **桌面端**——Electron 包 JRE 21 + 后端 JAR。双击启动，用户完全不需要知道底下跑的是 Java。
- **渠道**——每个渠道是一个 `ChannelAdapter` SPI 实现。Web 走 SSE，IM 各自走平台的长连接或 webhook。
- **存储**——开发用 H2 文件数据库，生产用 MySQL 8。Flyway 管理 schema 迁移，每种方言各有一套脚本。

---

## 三条进入方式

大概率你想做三件事之一。

**想用起来？** → [快速开始](./quickstart)，桌面端 60 秒到第一条消息。

**想搞清楚它？** → 按这个顺序读：[Agent 引擎](./agents) → [LLM Wiki](./wiki) → [记忆系统](./memory) → [多模态创作](./multimodal)。这四页就是产品本身。

**想在它上面建东西？** → [API 参考](./api) 和 [贡献指南](./contributing)。

---

这页上写的所有东西都可以质疑。如果哪里读不通，是文档的问题，不是你的——去 GitHub 告诉我们。
