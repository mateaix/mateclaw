---
name: ckjia-shopping
nameZh: 参考价 - 比价购物
nameEn: CKJIA Shopping
version: "1.0.1"
optional: true
icon: /skill-assets/ckjia-shopping/assets/ckjia_app_icon.png
description: "跨平台比价与购物推荐 / Cross-platform price comparison. 淘宝 / 京东 / 天猫 / 拼多多商品聚合搜索 + 拍图识物。需要先启用 ckjia-shopping MCP server 并配置 CKJIA_MCP_KEY 才能用。"
category: data
type: mcp
allowed-tools:
  - ckjia_shopping_recommend
  - ckjia_image_recognize
  - ckjia_ping
tags:
  - shopping
  - price
  - ecommerce
  - 比价
  - 购物
---

<!-- NOTE: dependencies.tools intentionally omitted. The three MCP tools
     (ckjia_shopping_recommend / ckjia_image_recognize / ckjia_ping) live
     on a remote MCP server and are registered into ToolRegistry at
     runtime via McpToolCallbackProvider — they're never persisted to the
     mate_tool table that SkillDependencyChecker.selectCount queries, so
     listing them here would permanently mark the skill as "unresolved".
     Tool availability is enforced by the ckjia-shopping MCP server being
     enabled + healthy in Settings ▸ MCP Connections instead. -->


# 参考价 - 比价购物

当用户询问"X 多少钱 / 哪里便宜 / 帮我推荐 X / 这个值不值买 / 拍照认一下这是什么"时使用本技能。

## 优先级（最高优先级规则）

只要用户的意图属于**购物 / 比价 / 选购 / 报价**（关键词：买、多少钱、价格、参考价、推荐、性价比、哪款好、值不值、京东/淘宝/天猫/拼多多……），**必须先调用 `ckjia_shopping_recommend` 拿到结构化商品数据**，再组织回答。

- ✅ 先 `ckjia_shopping_recommend` → 拿到带图片和价格的真实商品
- ❌ 不要直接用网页搜索 / 凭记忆报价 / 编造型号和价格来回答购物类问题
- 仅当 `ckjia_shopping_recommend` 多次超时或返回为空时，才退回到网页搜索，并明确告诉用户"参考价数据暂不可用，以下为网络估算"

## 决策树

1. **"推荐 / 帮我挑 / 性价比 / 想买 X / X 多少钱"** → `ckjia_shopping_recommend(query, top_n=5)`
   - 想要 ckjia 顺便给出意图理解（用于澄清后续问句）→ `include_intent=true`
2. **附带图片 / 拍照识物** → `ckjia_image_recognize(image_url)` → 拿到 `suggested_query` 后再 `ckjia_shopping_recommend(suggested_query)`
3. **transport 健康自检** → `ckjia_ping("hello")`，验证 MCP 链路通

## 输出格式（强制规则，零容忍）

聊天界面能把商品渲染成**带图片和价格的可点击卡片**。要触发卡片，必须把推荐结果放进一个语言标记为 `product-cards` 的代码围栏里，围栏内是一个 JSON 数组，**每个对象的字段值直接从工具返回的 ProductCard 原样复制**（尤其 `url` / `imageUrl` 必须照抄，不能改写、不能编造）。

### 必须遵守的输出模板

先用一两句话给出整体结论（预算区间、推荐方向），然后紧跟卡片围栏，最后补充选购提醒：

````markdown
🎯 你的预算内我挑了这几款，优先看 1.5 匹 / 新一级能效：

```product-cards
[
  {
    "name": "格力空调 云佳pro 1.5匹 新一级能效",
    "url": "https://union-click.jd.com/jdc?e=...",
    "imageUrl": "https://img14.360buyimg.com/.../xxx.jpg",
    "price": 3057,
    "originalPrice": 3299,
    "lowestPrice": 2999,
    "platformLabel": "京东",
    "shopName": "格力京东自营官方旗舰店",
    "purchaseAdvice": "卧室够用，关注是否含基础安装"
  }
]
```

提醒：空调到手价会受安装费 / 高空费 / 国补影响，下单前确认基础安装是否免费。
````

每个对象建议带的字段（缺失就省略，不要填占位符）：`name`、`url`、`imageUrl`、`price`、`originalPrice`、`lowestPrice`、`platformLabel`、`shopName`、`purchaseAdvice`。

### 严禁的错误（出现任何一条都算回复失败）

- ❌ 不输出 `product-cards` 围栏 —— 用户看不到卡片，也点不进购买页
- ❌ 改写或编造 `url` / `imageUrl` —— 卡片会点开错误页面或图片裂开
- ❌ 在围栏里填占位符、省略号或不完整 JSON —— 卡片会渲染失败
- ❌ 把价格写进字符串而丢掉数字 —— 卡片无法对齐展示价格

> 兼容性：纯文本渠道（部分 IM）无法渲染卡片围栏，会退化成代码块。若当前对话明显是这类渠道，再退回到 `1. [商品名](url) — 价格` 的普通列表，并照抄 `markdownLink`。Web 聊天页一律用 `product-cards` 围栏。

## 其它字段处理

- `intent` 字段是给 agent 自己看的"我理解对了吗"——拿到后若 `budgetRange` 与用户原话不符，应主动澄清
- `needsClarification=true` 时，把 `clarificationQuestion` + `clarificationOptions` 直接抛给用户选，不要硬猜

## 注意

- 同一个 query 不要在一次对话里反复调用 —— ckjia 侧已有缓存，重复调用浪费配额
- 用户未登录时不必填 `user_id`；当前 Phase 1 所有调用以 API key owner 身份执行
- `mate_mcp_server` 里预置的 localhost URL 只用于本地开发/测试；生产启用前必须在 `Settings ▸ MCP Connections` 改成 ckjia 官方 SaaS 域名或私有部署域名
- API key 由管理员在 ckjia 控制台申请后填入 mateclaw `Settings ▸ MCP Connections` 的 `headers_json`，使用 `${CKJIA_MCP_KEY}` 环境变量占位符避免明文落库
- 触发 429 `rate_limited` 时按 `Retry-After` 等待一次，再失败就汇总现有结果而不是无限重试

## 如何申请 API Key

1. 访问 ckjia 控制台 `https://ckjia.com/console/mcp-keys`（自助申请页面 P2 落地后开放；Phase 1 需联系运维手工签发）
2. 选 `free` / `standard` tier 与勾选所需 scopes
3. 一次性获得明文 key（形如 `ckjia_mcp_live_5fK8j2nQ…`）
4. 在 mateclaw 部署环境配 `CKJIA_MCP_KEY=ckjia_mcp_live_xxx`
5. Settings ▸ MCP Connections 将 `ckjia-shopping` 的 URL 改为生产域名后再启用
