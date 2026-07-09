# 知识库开放 API 设计文档

> 状态：草案 v2.1 · 评审修订已合入
> 作者：MateClaw Team
> 关联 ISSUE：待创建（R12：动工前在上游开 issue）

## 1. 背景与目标

### 1.1 现状

MateClaw 的知识库目前有 3 条触达路径，但**没有直接对外的入口**：

| 路径 | 消费方 | 认证 | 局限 |
|---|---|---|---|
| ① Agent 工具（`kb_xxx_search/read/list`） | LLM 内部调用 | Agent 绑定隔离 | 不对外，必须经 Agent 对话 |
| ② REST `search-preview` / `pages` | 前端 UI | JWT + workspace 鉴权 | JWT 认证，不对外 |
| ③ WebChat 渠道 | 外部网站 | API Key | 只暴露 Agent 对话，外部无法直接检索知识库 |

外部系统想用知识库，只能套一层 Agent 对话（路径③），无法程序化检索。

### 1.2 目标

把知识库做成一个**开放功能**，提供 OpenAPI 风格的对外服务，支持两类消费者：

1. **其他应用程序**（客服系统、搜索前端、内部业务系统）—— 通过 REST API 程序化检索知识库。
2. **外部 AI Agent**（Claude Desktop、Cursor、其他 Agent 平台）—— 后续通过 MCP Server 零代码接入。

### 1.3 核心设计判断

在讨论过程中，我们达成了一个关键判断：**Wiki 的页面在逻辑上就是实体**。项目数据模型里，`pageType` 的第一种取值就叫 `entity`，页面带有 `canonicalName`、`metadataJson`（结构化字段）、`knowledgeLayer`（fact/experience 分层标签）。因此：

- **slug 是实体的唯一标识**，`get_entity` 就是"查页面并按卡片格式返回"。
- **不需要分"页面中心化"和"实体中心化"两套 API**，统一为一套接口。
- **entityId 概念在对外 API 中消失**——除了 `traverse`（实体关系遍历），这是唯一需要 `WikiEntityRelationEntity` 表的接口。

### 1.4 设计原则

- **复用优先**：底层检索复用 `HybridRetriever`，认证复用 PAT 的 hash 存储范式，鉴权复用 `verifyKBWorkspace` 模式。
- **安全收紧**：API Key 采用 SHA-256 hash 存储，支持 scope 细粒度控制。
- **务实简化**：traverse 先支撑 1~2 跳高频场景，性能优化（索引、BFS 引擎）留后续。

---

## 2. 整体架构

```
                    ┌─────────────────────────────────────────┐
                    │            外部消费方                      │
                    ├────────────────┬────────────────────────┤
                    │  其他应用程序     │  外部 AI Agent (后续MCP)  │
                    │  (HTTP client)  │                        │
                    └───────┬────────┴──────────┬─────────────┘
                            │ REST API           │
                            ▼                    ▼
┌───────────────────────────────────────────────────────────────┐
│                    /api/v1/open/kb/**                          │
│              (SecurityConfig permitAll 白名单)                  │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │          KbOpenApiAuthFilter (新建, OncePerRequestFilter) │  │
│  │  Authorization: Bearer mck_xxxxx                          │  │
│  │    → SHA-256 hash 查表 → 校验 enabled/expired/scope      │  │
│  │    → 注入 KbApiKeyContext (keyId, kbIds, scopes, rateLimit)│ │
│  │    → 每 key 粗粒度限流（复用 TriggerRateLimiter 范式）    │  │
│  └──────────────────────────┬────────────────────────────────┘  │
│                             ▼                                 │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │              KbOpenApiController (新建)                    │  │
│  │  + @RequireKbScope 集中授权（A1，仿 @RequireWorkspaceRole）│  │
│  │                                                          │  │
│  │  查主体:  GET  /pages/{slug}          (卡片+下钻mode)      │  │
│  │  搜索:    POST /search                 (granularity控制)   │  │
│  │  走关系:  POST /pages/{slug}/traverse  (实体关系遍历)       │  │
│  │  溯源:    GET  /pages/{slug}/trace     (页面→chunk→raw)    │  │
│  │  地图:    GET  /taxonomy               (类型枚举)          │  │
│  │  时效:    GET  /whats-new              (变更/stale查询)    │  │
│  │  元信息:  GET  /stats                                      │  │
│  └──────────────────────────┬────────────────────────────────┘  │
│                             ▼                                 │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │   现有服务层（不改动或最小改造）                              │  │
│  │  HybridRetriever · WikiPageService · WikiEntityGraphService│  │
│  │  PageCitationMapper · WikiResearchService                  │  │
│  └─────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘

管理端（JWT 认证）:
  POST /api/v1/open/keys          签发 API Key（明文仅返回一次）
  GET  /api/v1/open/keys          列出 API Key（不返回明文）
  DELETE /api/v1/open/keys/{id}   撤销 API Key
  PUT  /api/v1/open/keys/{id}     更新 Key（name/scopes/kb-ids/expiresAt）
```

---

## 3. 数据模型

### 3.1 API Key 表 `mate_kb_api_key`

```sql
CREATE TABLE mate_kb_api_key (
    id              BIGINT       PRIMARY KEY,         -- 雪花 ID
    name            VARCHAR(128) NOT NULL,            -- 人类可读标签
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,     -- SHA-256 hex（明文不落库，自带索引）
    prefix          VARCHAR(6)   NOT NULL,            -- 明文前 4 位，用于 UI 展示 "mck_ab****"
    workspace_id    BIGINT       NOT NULL,            -- 所属工作区（隔离边界）
    created_by      BIGINT       NOT NULL,            -- 签发人 userId
    scopes          VARCHAR(256),                     -- "kb:search,kb:read,kb:list"
    enabled         TINYINT      NOT NULL DEFAULT 1,  -- 软撤销
    expires_at      DATETIME,                         -- 可选硬过期，NULL=永不过期
    last_used_at    DATETIME,                         -- 最后使用时间（去抖更新）
    rate_limit_per_min INT NOT NULL DEFAULT 60,       -- 每分钟请求上限（P0 强制执行）
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0
);
-- R10: token_hash 已 UNIQUE（自带索引），不再额外建 idx_kb_api_key_hash
```

设计要点（对照 PAT / WebChat）：
- **`token_hash` 存 SHA-256**，明文仅创建时返回一次。**不复用 WebChat 的明文存储**。
- **R8：`prefix` 缩短为 4 位**（原 8 位）——DB 泄露时缩小爆破空间。UI 展示 `mck_ab****`，hash 不可逆，prefix 不泄露安全性。
- **`workspace_id`** 是隔离边界：一个 API Key 属于一个工作区，只能访问该工作区内的知识库。
- **`scopes`** 复用 PAT 的逗号分隔格式（`"kb:search,kb:read,kb:list"`）。
- **`rate_limit_per_min`** P0 强制执行（R2）——search 消耗 embedding token，公网可达必须限流。

### 3.2 API Key ↔ 知识库绑定表 `mate_kb_api_key_binding`

```sql
CREATE TABLE mate_kb_api_key_binding (
    id          BIGINT  PRIMARY KEY,
    api_key_id  BIGINT  NOT NULL,
    kb_id       BIGINT  NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE(api_key_id, kb_id)
);
CREATE INDEX idx_kb_api_key_binding_kb ON mate_kb_api_key_binding(kb_id);
-- R10: 按 kb_id 反查（Wiki 面板"这个 KB 绑了哪些 Key"）需要索引，否则全表扫
```

设计要点：
- **多对多关系**：一个 API Key 可绑定多个知识库（满足"Key 绑定多 KB"需求）。
- 绑定列表定义了这个 Key 的检索范围——调用方传的 `kbId` 必须在绑定列表内，否则 403。
- **R3：对外 key 空绑定 = 零访问（不是全量）**。签发时绑定列表不能为空。这与内部 Agent 的 `AgentWikiKbBinding`"无绑定=不收窄"语义**相反**——对外 key 不应静默拿到全工作区、且新建 KB 自动纳入。

### 3.3 API Key 明文格式

```
mck_<43 url-safe base64 chars>
 ^^
 ├─ mck = MateClaw Knowledge，区别于 PAT 的 mc_ 前缀
 └─ JwtAuthFilter 可据此区分 JWT(eyJ) / PAT(mc_) / KB-API-Key(mck_) 三种 token
```

### 3.4 现有数据模型（不新建表，仅引用）

开放 API 的所有数据都来自现有表，只是按"实体/主体"语义做运行时投影：

```
WikiKnowledgeBaseEntity    知识库：id, name, workspaceId
       │
WikiPageEntity             主体画像的载体（= 实体）：
  ├─ slug                   唯一标识（对外 API 的主键）
  ├─ pageType               fund_manager / risk_analysis / person ...
  ├─ canonicalName          主体名
  ├─ knowledgeLayer         fact | experience（知识分层标签）
  ├─ metadataJson           结构化字段（schema 随 KB 变化）
  ├─ content                全文
  ├─ sourceRawIds           来源原始材料
  └─ stale / version        时效状态
       │
       │ PageCitationWithRaw (pageId → chunkId → rawId)
       ▼
WikiChunkEntity + WikiRawMaterialEntity    原始片段 + 文档（溯源终点）

WikiEntityEntity + WikiEntityRelationEntity  仅 traverse 使用（实体间语义关系）
```

### 3.5 共享 Token 内核（A4）

> A4 修订：KB API Key 与 PAT 都是"hash 存储 + CRUD + 管理 UI"，高度同构。把 hash 生成/校验、CRUD、管理 UI 抽成共享"token 内核"，避免 P0-A 一半工作量在复制、日后两套漂移。

```
共享 token 内核（token-kernel，新建或从 PAT 重构提取）
  ├─ TokenHashUtil       hash 生成（SecureRandom + SHA-256）、校验、前缀管理
  ├─ TokenCrudTemplate   通用 CRUD（create/list/revoke/update + 去抖 recordUse）
  └─ TokenManagementUi   共享管理 UI 组件（列表 + 签发 Modal + 明文一次性展示）

两种 flavor：
  ├─ PAT flavor：token_hash 存 mate_personal_access_token，携带 userId（用户身份）
  └─ KB-Key flavor：token_hash 存 mate_kb_api_key，携带 workspaceId + kbIds + scopes（非用户身份）
```

关键约束：
- **KB-key 不含用户身份**（独立 filter，不写 SecurityContext），这个决策**不变**——共享的是 hash/CRUD/UI 基础设施，不是认证语义。
- P0 实施时，优先评估 PAT 现有代码的可提取程度；若 PAT 重构成本高，可先**共享 `TokenHashUtil`（hash 生成/校验）**，CRUD/UI 的完整共享作为后续重构。

---

## 4. 认证与鉴权设计

### 4.1 认证 Filter（新建 `KbOpenApiAuthFilter`）

```
请求: Authorization: Bearer mck_xxxxx
  │
  ▼
KbOpenApiAuthFilter (OncePerRequestFilter, 仅拦截 /api/v1/open/kb/**)
  │
  ├─ 1. 提取 Bearer token
  ├─ 2. 缺失 / 非 "mck_" 前缀 / hash 未命中 / disabled / 过期
  │     → 当场返回 401（绝不放行）
  │     R1: 此路径是 permitAll，本 filter 是唯一守门人，pass-through = 漏洞
  ├─ 3. 加载绑定 KB 列表 + scopes + rateLimit
  ├─ 4. 限流检查（R2）：超 rate_limit_per_min → 返回 429
  ├─ 5. 注入 KbApiKeyContext 到 request attribute：
  │      { keyId, workspaceId, kbIds(Set), scopes(Set) }
  └─ 6. recordUse（去抖，复用共享 token 内核的 60s 窗口）
```

关键决策：
- **独立 Filter，不复用 JwtAuthFilter**。JwtAuthFilter 统一处理 JWT+PAT 并写入 SecurityContext（赋用户角色）；KB API Key 不代表用户身份，不应获得用户级权限，需要独立的认证上下文。
- **R1：此路径是 permitAll，filter 是唯一守门人**——缺失/无效 key 必须**当场返回 401**，绝不放行。pass-through 只适用于全局 filter（如 JwtAuthFilter 有后续 Security 链兜底），不适用于这条只靠自身把关的路径。
- **R2：限流在 filter 层做**——search 每次调用消耗 embedding token，公网可达 + 无限流 = 成本风险。每 key 粗粒度限流（复用 `TriggerRateLimiter` 范式），超限返回 429。
- **A4：hash 生成/校验复用共享 token 内核**（见 §3.5），不复制 PAT 的整段实现。

### 4.2 鉴权：集中式注解 + 两层校验

> A1 修订：授权校验**不逐端点手写**。本仓库刚因"逐端点手写归属校验"连发 #438/#439 修 Wiki IDOR——对外 API 重蹈代价更大。安全关键校验应收一处审计。

**集中授权**：新建 `@RequireKbScope("kb:search")` 注解 + interceptor（仿现有 `@RequireWorkspaceRole` + `WorkspaceAccessInterceptor` 模式），集中完成 scope 检查与 KB 归属校验。

```
请求 POST /api/v1/open/kb/{kbId}/search
  │
  ├─ 层① 认证：KbOpenApiAuthFilter 已校验（key 有效 + 限流通过）
  │
  ├─ 层② scope 校验（@RequireKbScope 注解 → interceptor）：
  │     KbApiKeyContext.scopes 包含注解要求的 scope（如 "kb:search"）
  │     → 不满足返回 403
  │
  └─ 层③ KB 归属校验（interceptor 内，从 path variable 提取 kbId）：
        kbId ∈ KbApiKeyContext.kbIds
        → 不满足返回 403
```

层③ 复用 `verifyKBWorkspace` 的核心逻辑（`kb.getWorkspaceId().equals(workspaceId)`），判断来源为 API Key 的 `workspaceId` 字段。

**R3：空绑定语义为"零访问"而非"全量"**——对外 key 不复用内部 Agent 的"无绑定=不收窄"语义。签发时绑定列表不能为空（或空绑定 = 零访问），避免一个 key 静默拿到全工作区、且新建 KB 自动纳入。

### 4.3 Scope 定义

| Scope | 允许的操作 | 说明 |
|---|---|---|
| `kb:search` | POST `/search`、POST `/search/chunks`、POST `/research/**`（Deep Research 全套）| 检索（含异步深度研究，复用同一 scope）|
| `kb:read` | GET `/pages/{slug}`、GET `/pages/{slug}/trace`、POST `/pages/{slug}/traverse` | 读主体画像 + 溯源 + 关系遍历 |
| `kb:list` | GET `/pages`、GET `/taxonomy` | 列页面 + 地图 |
| `kb:meta` | GET `/stats`、GET `/whats-new` | 元信息 + 时效查询 |
| `kb:*` | 全部 | 通配，简便但权限大 |

签发时可组合，如 `"kb:search,kb:read"`。默认签发 `kb:*`，按需收窄。

> 注：`/research`（异步 Deep Research）已移出本版，作为独立议题单独设计（见 §9 P1.5）。R5 已处理：原 scope 表中的 `GET /raw` 幽灵端点已删除，不在本版 9 端点清单内。

---

## 5. API 规范

### 5.1 对外接口总览（`/api/v1/open/kb/**`，API Key 认证）

一套接口，共 9 个端点。标 🔧 的需要新增底层 service 方法。R5 已处理：`/raw` 不在本版清单内。

| 方法 | 路径 | Scope | 底层方法 | 状态 |
|---|---|---|---|---|
| GET | `/kb/{kbId}/pages/{slug}` | kb:read | `WikiPageService.getBySlug()` + 🔧 mode 改造 | 改造 |
| POST | `/kb/{kbId}/search` | kb:search | `HybridRetriever.search()` | 现有 |
| POST | `/kb/{kbId}/search/chunks` | kb:search | `HybridRetriever.searchChunks()` | 现有 |
| POST | `/kb/{kbId}/pages/{slug}/traverse` | kb:read | 🔧 `WikiEntityGraphService.traverse()` | 新增 |
| GET | `/kb/{kbId}/pages/{slug}/trace` | kb:read | `PageCitationMapper.listWithRawByPageId()` | 现有 |
| GET | `/kb/{kbId}/taxonomy` | kb:list | 🔧 组装（pageType/entityType/relationType 统计） | 新增 |
| GET | `/kb/{kbId}/whats-new` | kb:meta | `WikiPageService.findRecentUpdated()` + stale | 现有 |
| GET | `/kb/{kbId}/stats` | kb:meta | 🔧 `KbStatsDto` 标准化 | 新增 |
| GET | `/kb/{kbId}/pages` | kb:list | `WikiPageService.listByKbId()` | 现有 |

> `/research`（异步 Deep Research + SSE）已移出本版，作为独立议题设计（见 §9 P1.5）。R7（SSE × API key）随之解决——本版无 SSE 端点。

### 5.2 接口示例

#### 查主体画像（get_entity = get_page）

```
GET /api/v1/open/kb/{kbId}/pages/{slug}
Authorization: Bearer mck_xxxxx
Query: ?mode=summary&fields=登记编码,登记状态

Response 200 (mode=summary, 默认紧凑):
{
  "code": 200,
  "data": {
    "slug": "xx-investment",
    "canonicalName": "XX投资管理有限公司",
    "pageType": "fund_manager",
    "knowledgeLayer": "fact",
    "title": "XX投资管理有限公司 - 基本信息登记",
    "summary": "中基协登记的管理人，注册资本5000万...",
    "fields": {
      "登记编码": "P123456",
      "登记状态": "已注销"
    },
    "source": { "rawIds": [55, 56], "rawTitles": ["中基协公示页.pdf", "工商信息.docx"] },
    "version": 3,
    "updatedAt": "2026-06-25T10:00:00"
  }
}

Response 200 (mode=full):
{ ..., "content": "# XX投资管理有限公司\n\n## 基本信息\n..." }

Response 200 (mode=section:风险评估):
{ ..., "content": "## 风险评估\n注销原因为异常经营..." }
```

mode 语义：
- **summary**（默认）：标题 + 摘要 + fields，省 token。`fields` 参数可只取指定字段。
- **full**：页面全文。
- **section:{heading}**：按 heading 切片取部分内容。

#### 搜索（带粒度控制）

```
POST /api/v1/open/kb/{kbId}/search
Authorization: Bearer mck_xxxxx

Request:
{
  "query": "已注销的私募管理人",
  "pageType": "fund_manager",      // 可选，按页面/实体类型过滤
  "granularity": "entity",         // entity(默认) | section | chunk
  "topK": 10
}

Response 200 (granularity=entity):
{
  "code": 200,
  "data": {
    "kbId": 123,
    "count": 2,
    "results": [
      {
        "slug": "xx-investment",
        "title": "XX投资管理有限公司",
        "pageType": "fund_manager",
        "knowledgeLayer": "fact",
        "summary": "...",
        "score": 0.91
      }
    ]
  }
}
```

granularity 三档：
- **entity**（默认）：页面级聚合，返回 slug + 摘要 + 分层标签（= Agent 友好的主体命中）。
- **chunk**：片段级，返回 ChunkHit（深挖时用，调 `/search/chunks`）。
- **section**（R9）：暂不支持，P0 不实现。`HybridRetriever` 无中间档产出（search 返回页面级 `PageSearchResult`、searchChunks 返回 `ChunkHit`），section 的 snippet/score 来源未定义。P0 只支持 entity/chunk 两档。

#### traverse（实体关系遍历）

```
POST /api/v1/open/kb/{kbId}/pages/{slug}/traverse
Authorization: Bearer mck_xxxxx

Request:
{
  "relation": "实控人",       // 可选，null=所有关系（LIKE 模糊匹配）
  "depth": 1,                // 1 或 2，默认 1
  "direction": "both",       // outgoing | incoming | both，默认 both
  "limit": 20                // 每跳返回边数上限，默认 20，硬上限 50
}

Response 200:
{
  "code": 200,
  "data": {
    "root": {
      "slug": "xx-investment",
      "entityId": 9001,
      "name": "XX投资管理有限公司",
      "type": "fund_manager"
    },
    "edges": [
      {
        "predicate": "实控人",
        "fromId": 9002,
        "toId": 9001,
        "fromName": "张三",
        "toName": "XX投资",
        "evidence": "张三持有XX投资51%股权",
        "confidence": 0.95,
        "sourceHandle": "p:789"
      }
    ],
    "nodes": [
      { "entityId": 9002, "name": "张三", "type": "person", "slug": "zhang-san" }
    ]
  }
}
```

#### trace（溯源）

```
GET /api/v1/open/kb/{kbId}/pages/{slug}/trace
Authorization: Bearer mck_xxxxx

Response 200:
{
  "code": 200,
  "data": {
    "slug": "xx-investment",
    "pageType": "fund_manager",
    "knowledgeLayer": "fact",
    "sources": [
      {
        "rawId": 55,
        "rawTitle": "中基协公示页.pdf",
        "citations": [
          { "chunkId": 9001, "snippet": "登记状态：已注销...", "confidence": 0.92, "pageNumber": 1 }
        ]
      }
    ],
    "extractedAt": "2026-06-20T10:30:00",
    "pageVersion": 3
  }
}
```

#### get_taxonomy（地图）

```
GET /api/v1/open/kb/{kbId}/taxonomy
Authorization: Bearer mck_xxxxx

Response 200:
{
  "code": 200,
  "data": {
    "pageTypes": [
      { "pageType": "fund_manager", "count": 45, "label": "私募基金管理人" },
      { "pageType": "risk_analysis", "count": 30, "label": "风险评估" }
    ],
    "entityTypes": [
      { "type": "person", "count": 120 },
      { "type": "organization", "count": 56 }
    ],
    "relationTypes": [
      { "predicate": "实控人", "count": 30 },
      { "predicate": "works_for", "count": 56 }
    ]
  }
}
```

#### whats_new（时效查询）

```
GET /api/v1/open/kb/{kbId}/whats-new?since=2026-06-20&kind=updated
Authorization: Bearer mck_xxxxx

Response 200:
{
  "code": 200,
  "data": {
    "kbId": 123,
    "since": "2026-06-20T00:00:00",
    "changedPages": [
      { "slug": "xx-investment", "title": "...", "knowledgeLayer": "fact",
        "updatedAt": "2026-06-25T10:00:00" }
    ],
    "stalePages": [
      { "slug": "xx-risk", "title": "...", "staleReason": "上游fact页面变更" }
    ]
  }
}
```

### 5.3 错误响应

统一 `R<T>` 包装：

| HTTP | code | 场景 |
|---|---|---|
| 401 | 401 | 缺少/无效/过期 API Key |
| 403 | 403 | scope 不足 / KB 不在绑定范围 / KB 不属于 Key 的工作区 |
| 404 | 404 | kbId / slug 不存在 |
| 429 | 429 | 超过 rate limit（P1） |

### 5.4 管理接口（`/api/v1/open/keys/**`，JWT 认证）

```
POST   /api/v1/open/keys              签发 Key（返回明文，仅此一次）
GET    /api/v1/open/keys              列出当前工作区的 Key（不返回明文）
GET    /api/v1/open/keys/{id}         Key 详情（不含明文）
DELETE /api/v1/open/keys/{id}         撤销 Key
PUT    /api/v1/open/keys/{id}         更新 Key（name / scopes / kb-ids / expiresAt）
```

---

## 6. traverse 设计专题

traverse 是整套 API 里唯一需要 `WikiEntityRelationEntity` 表（实体间语义关系）的接口，也是最有独立价值的差异化能力。其余接口都是页面的不同投影。

### 6.1 数据现状

`WikiEntityRelationEntity` 是三元组表：

```
subjectEntityId → predicate → objectEntityId
  + evidence（证据原文 ≤500字）
  + evidenceChunkId（来源 chunk）
  + confidence（0~1）
  + source（llm-extracted | inferred | manual）
```

关键事实：
- **predicate 是自由文本**，由 LLM 抽取并归一化为 snake_case（`WikiEntityExtractionService.java:469`）。没有预定义的关系类型枚举。
- **现有的 `ego()` 方法**（`WikiEntityGraphService.java:94`）是 traverse 的雏形——但固定 1 跳、不按 predicate 过滤、双向全量取。
- **entityId 与 slug 的映射**：slug → pageId → `WikiEntityMentionEntity(pageId=X)` → entityId。

### 6.2 务实版设计

先支撑最高频场景（"这个发行人的关联方""实控人名下其他主体"），都是 1~2 跳、按关系类型过滤。

**参数设计**：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `relation` | String | null | 关系类型过滤，null=所有关系。LIKE 模糊匹配（不标准化） |
| `depth` | int | 1 | 遍历深度，仅支持 1 或 2，硬上限 2 防爆炸 |
| `direction` | String | both | outgoing / incoming / both |
| `limit` | int | 20 | 每跳返回边数上限，硬上限 50 |

**slug → entityId 映射**：页面取其关联的 salience 最高实体作为 primary entity（一对一假设）。R11：响应回显实际选中的 `root.entityId` / `root.name`，让调用方知道映射选了哪个实体（同页可能有多个实体，取 salience 最高会静默丢其它）。

**邻居节点呈现**：子图节点返回 entityId + name + type + slug（slug 通过 mention 反查，如果该实体有页面的话；没有则为 null）。

**边溯源**：`evidenceChunkId` → firstCitingPage → `sourceHandle` 句柄。

### 6.3 底层实现

```
1. pageService.getBySlug(kbId, slug) → pageId
2. mentionMapper 查 (pageId=pageId) → 取 salience 最高 entityId → primaryEntityId
3. WikiEntityGraphService.traverse(kbId, primaryEntityId, relation, depth, direction, limit)
   ├─ depth=1: relationMapper 查 (subjectId=X OR objectId=X) + predicate LIKE + direction + LIMIT
   └─ depth=2: 先查 1 跳邻居 IDs（≤limit）→ 批量查这些邻居的边 (IN 查询) + LIMIT
4. 收集邻居 entityId → entityMapper.selectBatchIds → 组装 nodes
5. 邻居 slug 反查: mentionMapper(entityId=X, pageId != null) → pageService → slug
6. 边溯源: evidenceChunkId → firstCitingPage → sourceHandle "p:{pageId}"
```

需要新增 1 个 service 方法：`WikiEntityGraphService.traverse()`。不新建表、不加索引、不改抽取逻辑。

### 6.4 局限性（诚实标注）

| 局限 | 影响 | 后续优化 |
|---|---|---|
| depth ≤ 2 | 超过 2 跳的深链路查询不支持 | 加索引 + BFS 引擎 |
| predicate LIKE 模糊匹配 | `实控人` 可能匹配不到 `actual_controller` | 建关系类型映射表 + 约束抽取 prompt；配合 get_taxonomy 先查精确值 |
| 无路径去重 | 2 跳可能出现环形重复边 | BFS 时去重 |
| 性能未优化 | 大 KB（万级实体）2 跳可能慢 | 加 `(kbId, subjectId)` / `(kbId, objectId)` 复合索引 |

**配合 get_taxonomy 使用**：Agent 先调 `/taxonomy` 看库里实际有哪些 predicate，拿精确值再传给 traverse，比靠猜更可靠。

---

## 7. 前端管理 UI 方案

### 7.1 放置位置：Settings 子菜单为主 + Wiki 配置面板只读为辅

**主管理页**：`/settings/open-api`（Settings ▸ Advanced 分段，紧邻 ACP 端点）

```
设置 ▸ Advanced
  ├─ ...
  ├─ ACP 端点 /settings/acp        ← 最接近的 CRUD 范例
  ├─ 开放 API /settings/open-api   ← 新增
  └─ Token 统计 /settings/token-usage
```

**辅助展示**：在 `WikiConfig.vue`（单个知识库配置面板）增加「开放 API」折叠区，只读展示"这个 KB 被哪些 Key 绑定了"，提供跳转到设置页的链接。

### 7.2 理由

| 考量 | 分析 |
|---|---|
| 项目惯例 | RFC-090 已把多个功能降级进 Settings，所有"配置/治理/接入"类功能收拢在 `/settings` |
| 最接近范例 | `AcpEndpoints.vue` 就是"对外暴露 + 凭证管理"，放在 `/settings/acp`，结构完全同构 |
| Key 粒度 | 1 Key → N KB 是跨 KB 的，必须在平台级页面统一管理，单 KB 视角无法管理跨 KB Key |
| 语义辅助 | 在知识库配置面板做只读展示，让用户知道"这个 KB 开了哪些对外接口"，但实际操作跳转 Settings |

### 7.3 主管理页界面结构（仿 `AcpEndpoints.vue`）

- **列表表格**：Key 名称 / `prefix****` 标识 / 绑定 KB 数 / scope / 最后使用 / 状态开关
- **签发 Modal**：名称 + scope 勾选 + 选择绑定 KB（多选）+ 可选过期时间 → 提交后**一次性弹窗显示明文**
- **编辑 Modal**：修改名称 / scope / 绑定 KB / 过期时间（不能改明文）
- **行内操作**：编辑 / 撤销（软删除）/ 复制 Key 前缀

### 7.4 需要新增的前端文件

| 文件 | 说明 |
|---|---|
| `src/views/Settings/OpenApi/index.vue` | 主管理页（仿 `AcpEndpoints.vue`） |
| `src/router/index.ts` | `/settings` children 新增路由（约 `:258` 紧邻 `acp`） |
| `src/views/Settings/Layout.vue` | `sections` 的 Advanced 分段末尾加菜单项（`:155` 后） |
| `src/api/index.ts` | 新增 `kbOpenKeyApi`（仿 `acpApi`，`:403` 起） |
| `src/views/Wiki/components/WikiConfig.vue` | 新增「开放 API」只读折叠区 |
| i18n `zh-CN.ts` / `en-US.ts` | `settings.sections` + `nav` 段文案 |

---

## 8. 与现有体系的关系

### 8.1 复用的组件

| 组件 | 来源 | 复用方式 |
|---|---|---|
| `HybridRetriever.search()` | `wiki/service/HybridRetriever.java:92` | 直接调用，不改动（granularity 在 Controller 层后处理） |
| `HybridRetriever.searchChunks()` | `wiki/service/HybridRetriever.java:188` | 直接调用，不改动 |
| `WikiPageService.*` | `wiki/service/WikiPageService.java` | 直接调用（getBySlug / listByKbId / findRecentUpdated） |
| `PageCitationMapper.listWithRawByPageId()` | `wiki/repository/WikiPageCitationMapper.java:28` | 直接调用（trace） |
| `WikiResearchService.research()` | `wiki/service/WikiResearchService.java:70` | 已移出 P0（A3），P1.5 开放时复用 |
| SHA-256 hash 逻辑 | `PersonalAccessTokenService.sha256Hex` | 提取为公共工具或复制 |
| `R<T>` 响应包装 | `common/result/R` | 直接使用 |
| `verifyKBWorkspace` 逻辑 | `WikiController:1181` | 逻辑复用，参数来源不同 |

### 8.2 需要新增/改造的组件

| 组件 | 类型 | 说明 |
|---|---|---|
| `WikiEntityGraphService.traverse()` | 🔧 改造 | 新增按 predicate/depth/direction 遍历方法（基于现有 ego 查询模式扩展） |
| `KbStatsDto` + 组装 | 🔧 新建 | 结构化统计 JSON（rawCount/chunkCount/pageCount/embeddedChunks 等） |
| `get_taxonomy` 组装 | 🔧 新建 | pageType/entityType/relationType 分组统计 + profile 读取 |
| `whats_new` 组装 | 🔧 新建 | 组合 findRecentUpdated + stale 页面查询 |
| `pages/{slug}` mode 参数 | 🔧 改造 | 新增 summary/full/section:{heading} 模式语义化 |
| `@RequireKbScope` 注解 + interceptor | 🔧 新建 | A1：集中授权（scope + KB 归属），仿 `@RequireWorkspaceRole` + `WorkspaceAccessInterceptor` |
| `TokenHashUtil` 共享内核 | 🔧 新建/提取 | A4：hash 生成/校验共享，KB-key 与 PAT 两种 flavor 复用 |

**架构约束（A5/A6）**：
- **A5 对外契约保持"页面中心"**：并非所有 wiki 页面都是实体（overview/synthesis/log/risk_analysis…）；对非实体页面，"entity 语义"会退化。对外就叫"页面"，entity 仅作为 traverse 的内部细节。**响应一律用显式 DTO，绝不直接序列化实体**（同属 IDOR/字段泄露老坑，定为硬约束）。
- **A6 service 层不耦合 HTTP 类型**：P0 新增的 service 方法（traverse / taxonomy / stats）返回纯 DTO，**不让 `HttpServletRequest` / `R<T>` 漏进 service 层**，P2 MCP 才能零成本套壳。

**不需要新建任何数据库表**（API Key 表除外）。全部建立在现有 wiki 数据模型之上。

### 8.3 不复用 WebChat API Key 的原因

| 维度 | WebChat | KB 开放 API | 理由 |
|---|---|---|---|
| 存储 | 明文存 `config_json` | SHA-256 hash 独立表 | 安全 |
| 比较 | 明文 `equals` O(n) | hash 索引查找 O(1) | 安全+性能 |
| 粒度 | 1 key = 1 channel = 1 agent | 1 key = N 个 KB | 需求不同 |
| scope | 无 | `kb:search/read/list/meta` | 最小权限原则 |
| 校验 | 每 endpoint 手写 | 统一 Filter | 可维护性 |

### 8.4 SecurityConfig 改动

```java
// 放行开放 API 路径（API Key 认证由 KbOpenApiAuthFilter 负责）
http.requestMatchers("/api/v1/open/kb/**").permitAll()
// 管理端 /api/v1/open/keys/** 仍走 JWT 认证，不加白名单

// R6: CORS — 消费方含浏览器"搜索前端"，/api/v1/open/kb/** 需配置 CORS。
// 复用现有 CorsConfigurationSource（若已有）或新增；允许 Authorization 头。
http.cors(...);
```

---

## 9. 分阶段实施计划

### P0：开放 API 完整版（9 端点）

**目标**：一套完整的知识库开放 API（9 个端点），配齐 API Key 生命周期管理 + 限流 + 集中授权。

#### P0-A：基础设施（认证 + 限流 + 数据模型 + 管理端 + 集中授权）

| 任务 | 说明 |
|---|---|
| 数据模型 | `mate_kb_api_key` + `mate_kb_api_key_binding` 两张表 + 迁移脚本（h2/mysql/kingbase） |
| `TokenHashUtil` 共享内核（A4） | hash 生成/校验，KB-key 与 PAT 共享；先共享 hash 层，CRUD/UI 完整共享可后续 |
| `KbApiKeyService` | 签发（`mck_` 前缀）、校验、撤销、绑定管理；**R3 空绑定拒绝签发** |
| `KbOpenApiAuthFilter` | 认证 filter；**R1 缺失/无效 key 当场 401 不放行**；**R2 每 key 限流返回 429**；注入 `KbApiKeyContext` |
| `@RequireKbScope` 注解 + interceptor（A1） | 集中授权（scope 检查 + KB 归属），仿 `@RequireWorkspaceRole` |
| `KbApiKeyAdminController` | 管理端 CRUD（`/api/v1/open/keys/**`，JWT 认证） |
| SecurityConfig | `/api/v1/open/kb/**` permitAll + **R6 CORS** |
| 测试 | filter 认证（401）+ 限流（429）+ scope 校验 + KB 归属（参照 #438/#439 IDOR 测试范式） |

#### P0-B：开放 API 接口（9 个端点，一次上线）

A2 决策：一次全上，不分批。端点清单见 §5.1。

| 端点 | 底层方法 | 工作量 |
|---|---|---|
| `GET /pages/{slug}` | `WikiPageService` + 🔧 mode 改造 | 中 |
| `POST /search` | `HybridRetriever` + granularity 后处理 | 中 |
| `POST /search/chunks` | `HybridRetriever.searchChunks` | 低 |
| `GET /pages` | `WikiPageService.listByKbId` | 低 |
| `GET /pages/{slug}/trace` | `PageCitationMapper` | 低 |
| `GET /taxonomy` | 🔧 分组统计组装 | 中 |
| `GET /stats` | 🔧 KbStatsDto 组装 | 中 |
| `GET /whats-new` | `findRecentUpdated` + stale | 低 |
| `POST /pages/{slug}/traverse` | 🔧 `WikiEntityGraphService.traverse()` | 中 |

架构约束（A5/A6）：所有新增 service 方法返回纯 DTO，不耦合 HTTP 类型，为 P2 MCP 留钩子。

### P1.5：Deep Research 开放（独立议题）

> A3 决策：`/research` 移出 P0。它是唯一异步/SSE/多步 LLM/有成本/需 job 生命周期的端点，与其余同步只读端点本质不同。

单独设计内容：
- 限流 + token 计费（`TokenUsageService`）
- job 生命周期管理（查询/取消，当前文档未画）
- SSE 鉴权（R7：浏览器 EventSource 不能设 Authorization 头 → 仅服务端 client 或 query 参数 token）

### P1：运营能力

| 任务 | 说明 |
|---|---|
| 用量统计 | 复用 `TokenUsageService` 记录 embedding token 消耗 |
| 审计日志 | 记录每次检索的 query / kbId / keyId |
| 前端管理 UI | `/settings/open-api` 主管理页（仿 `AcpEndpoints.vue`）+ Wiki 配置面板只读展示 |
| Token 内核完整共享（A4 后续） | CRUD/UI 完整共享，消除 KB-key 与 PAT 两套实现 |
| **WebChat API Key 迁移** | **存量明文 key → hash 存储，无感迁移**（见下） |

#### WebChat API Key 迁移（P1，独立 PR）

> 分两步策略：KB 开放 API P0 先抽出 `TokenHashUtil` 并验证；P0 完成后，单独 PR 把 WebChat 迁过来。

```
当前（明文）                         迁移后（hash）
config_json:                         config_json:
  api_key: "mc_webchat_3f9a..."        api_key_hash: "a1b2c3..."（SHA-256）
                                       api_key_prefix: "mc_we3f9a"（展示用）
```

改动点：
- **生成**：`ChannelService.generateWebChatApiKey()` → 复用 `TokenHashUtil`，生成明文 + 计算 hash，只存 hash
- **校验**：`WebChatController.resolveChannel()` 从遍历明文 equals（O(n)）→ hash 索引查找（O(1)）
- **签发**：`enrichWebChatConfig()` 把 hash 塞入 config_json，明文只返回一次
- **数据迁移脚本**：遍历所有 webchat 渠道，读 `config_json.api_key` 明文 → 算 hash → 写 `api_key_hash` + 删明文。存量 key 值不变（`mc_webchat_xxx` 继续有效），无感迁移。

迁移完成后三套 token 共用一套内核：
```
TokenHashUtil（共享 hash 生成/校验）
  ├─ PAT：mc_ 前缀，userId 归属
  ├─ WebChat：mc_webchat_ 前缀，channelId 归属  ← P1 迁入
  └─ KB-Key：mck_ 前缀，workspaceId + kbIds 归属
```

### P2：MCP Server（Agent 原生接入，低优先级）

> 暂不排期，优先确保 REST 接口完善稳定后再考虑。

底层检索与 P0 共用（A6 约束保证 service 层可零成本套壳），MCP Server 只是新的传输层。

---

## 10. 风险与对策

| 风险 | 影响 | 对策 | 状态 |
|---|---|---|---|
| 语义检索全库扫描（无 ANN 索引） | 大 KB 下检索慢 | P1 加结果缓存；后续评估 pgvector/Milvus | 待优化 |
| API Key 泄露 | 知识库内容被未授权访问 | hash 存储 + scope 收窄 + 可撤销 + 可设过期 | ✅ 已设计 |
| search 成本风险（embedding） | 泄露 key 刷爆成本 | **P0 每 key 限流**（R2，复用 TriggerRateLimiter）+ topK 硬上限 | ✅ 已设计 |
| /research 成本风险（多步 LLM） | 同上 | **移出 P0**（A3），P1.5 带限流+计费再开 | ✅ 已规避 |
| traverse predicate 自由文本 | 模糊匹配不可靠 | 配合 get_taxonomy 先查精确值；后续建映射表 | 待优化 |
| traverse depth=2 性能 | 大 KB 可能慢 | 限制 depth ≤ 2 + LIMIT；后续加索引 | 待优化 |
| 检索计费盲点 | embedding 成本无法追踪 | P1 接入 `TokenUsageService` | 待实现 |

---

## 11. 已确认的决策

| 问题 | 决策 | 备注 |
|---|---|---|
| API Key 是否跨工作区？ | **不跨** | 一个 Key 绑定一个工作区 |
| 检索结果是否按 pageType 脱敏？ | **不脱敏** | 开放 API 返回全部命中 |
| 前端管理 UI 放哪？ | **Settings `/settings/open-api`** | Key 跨 KB，需平台级管理 |
| P2 MCP Server 优先级？ | **低** | 先确保 REST 完善 |
| 实体 vs 页面 | **实体就是页面** | 统一为一套接口，slug 即标识 |
| 溯源粒度 | **页面级** | page → chunk → raw |
| DATA/FACT/EXPERIENCE 标签 | **复用页面 knowledgeLayer** | 标签在页面上 |
| 实体数据来源 | **文档摄入自动抽取** | 复用现有 wiki 处理流水线 |
| get_entity 标识 | **用 slug** | entityId 概念在 API 中消失（traverse 内部用） |
| traverse 实现 | **务实版** | depth ≤ 2 + LIKE + LIMIT |
| A3：/research | **移出 P0** | 异步/SSE/多步 LLM，P1.5 独立设计 |
| A2：P0 范围 | **一次全上 9 端点** | 不分批，契约一次冻结 |
| R2：search 限流 | **P0 就做** | 每 key 限流（复用 TriggerRateLimiter） |
| A4：token 内核 | **和 PAT 共享** | 先共享 hash 层，CRUD/UI 后续 |
| A1：授权方式 | **集中注解** | `@RequireKbScope` + interceptor，不逐端点手写 |
| R3：空绑定语义 | **零访问** | 对外 key 不复用"无绑定=全量" |
| WebChat 迁移 | **P1 分两步** | KB P0 先验证 TokenHashUtil，P1 单独 PR 迁 WebChat（含无感数据迁移） |

---

## 12. Review 修订清单（草案 v2 评审产出）

> 来源：对 v2 的一次代码对照评审。v2.1 已合入全部修订。✅ = 已解决并写入文档。

### 🔴 Blocker（P0 动工前定稿）— 全部已解决

- [x] **R1 认证 filter 不能 pass-through（§4.1）** ✅
      → §4.1 已改为：缺失/无效 key **当场返回 401**，绝不放行。

- [x] **R2 昂贵端点不能裸奔进 P0（§4.1 / §9 / §10）** ✅
      → search 限流拉进 P0（§4.1 限流 + §9 P0-A + §10 风险表）；research 移出（A3）。

- [x] **R3 空绑定默认开放，对外 key 反模式（§3.2 / §4.2）** ✅
      → §3.2 / §4.2 已改为：对外 key 空绑定 = 零访问，签发时绑定列表不能为空。

### 🟡 Should-fix — 全部已解决

- [x] **R4 响应信封 `code` 对齐（§5.2 / §5.3）** ✅ → 所有示例已改 `"code": 200`。
- [x] **R5 幽灵端点 `/raw`（§4.3 / §5.1）** ✅ → 已从 scope 表和端点清单删除。
- [x] **R6 CORS（§8.4）** ✅ → SecurityConfig 已补 CORS 配置。
- [x] **R7 `/research` 的 SSE × API key（§9 P1.5）** ✅ → research 移出 P0，随 A3 解决。
- [x] **R8 `prefix` 缩短（§3.1）** ✅ → 已从 8 位改为 4 位。

### 🟢 Nits — 全部已解决

- [x] **R9 granularity=`section` 缺底层（§5.2）** ✅ → P0 只支持 entity/chunk，section 标注"暂不支持"。
- [x] **R10 索引（§3.1 / §3.2）** ✅ → 删除冗余 `idx_kb_api_key_hash`；binding 表补 `kb_id` 索引。
- [x] **R11 traverse 一对一假设（§6.2）** ✅ → 响应回显实际选中的 `root.entityId`/`root.name`。
- [ ] **R12 流程（issue）** ⏳ → 动工前在上游 mateaix/mateclaw 开 issue（实施时第一步）。

### 架构层修订 — 全部已决策

- [x] **A1 集中授权（§4.2 / §8.2）** ✅ → `@RequireKbScope` 注解 + interceptor。
- [x] **A2 P0 范围（§9）** ✅ → 用户决策：一次全上 9 端点（不分批）。
- [x] **A3 `/research` 移出（§9 P1.5）** ✅ → 独立议题设计。
- [x] **A4 共享 token 内核（§3.5 / §8.2）** ✅ → 先共享 hash 层，CRUD/UI 后续。
- [x] **A5 对外契约保持"页面中心"（§8.2）** ✅ → 响应一律显式 DTO，entity 仅 traverse 内部细节。
- [x] **A6 P2 MCP 钩子（§8.2）** ✅ → service 层不耦合 HTTP 类型。

### 评审认可的优点

- "实体即页面、slug 即标识"的统一抽象，砍掉一整套实体中心 API（§1.3）。
- SHA-256 hash 存储相对 WebChat 明文是实打实的安全改进（§8.3）。
- traverse 的局限性诚实标注 + "配合 get_taxonomy 先查精确 predicate"的务实路径（§6.4）。
- 底层服务复用充分、分阶段 + 分批 PR 的实施计划清晰（§8.1 / §9）。
