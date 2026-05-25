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

## 决策树

1. **"推荐 / 帮我挑 / 性价比 / 想买 X"** → `ckjia_shopping_recommend(query, top_n=5)`
   - 想要 ckjia 顺便给出意图理解（用于澄清后续问句）→ `include_intent=true`
2. **附带图片 / 拍照识物** → `ckjia_image_recognize(image_url)` → 拿到 `suggested_query` 后再 `ckjia_shopping_recommend(suggested_query)`
3. **transport 健康自检** → `ckjia_ping("hello")`，验证 MCP 链路通

## 输出格式（强制规则，零容忍）

每个 `ProductCard` 已经预渲染了两个开箱即用字段，**直接复制粘贴这两个字符串到回复**，不要自己拼装：

- `markdownLink` —— 已经是 `[商品名](购买URL)` 格式，照抄即可
- `priceTag` —— 已经是 `¥4099 ~~¥4499~~ (9% off)` 格式，照抄即可

### 必须遵守的输出模板

```markdown
1. {{markdownLink}}
   - 💰 {{priceTag}}
   - 🛒 {{platformLabel}} · {{shopName}}
   - 📊 评分 {{rating}} · 销量 {{salesCount}}
   - 💡 历史最低 ¥{{lowestPrice}}
   - {{purchaseAdvice}}
```

把双花括号 `{{xxx}}` 替换成 ProductCard 对应字段的值。**`markdownLink` 一定要原样输出**，不要把它拆开后用其它方式重组。

### 真实示例

工具返回：
```json
{
  "name": "格力空调 云佳pro 1.5匹...",
  "url": "https://union-click.jd.com/jdc?e=...",
  "markdownLink": "[格力空调 云佳pro 1.5匹...](https://union-click.jd.com/jdc?e=...)",
  "priceTag": "¥3057",
  "platformLabel": "京东",
  ...
}
```

正确输出：
```markdown
1. [格力空调 云佳pro 1.5匹...](https://union-click.jd.com/jdc?e=...)
   - 💰 ¥3057
   - 🛒 京东
```

### 严禁的错误（出现任何一条都算回复失败）

- ❌ 不输出 `markdownLink` —— 用户没法点击购买
- ❌ 把 markdownLink 拆开只取商品名 —— 等于丢弃链接
- ❌ 编造任何不在 ProductCard 字段里的 URL
- ❌ 输出 `[商品名](url)` 但中间填占位符或省略号

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
