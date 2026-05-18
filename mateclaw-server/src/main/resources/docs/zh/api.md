# API 参考

所有 REST 端点前缀 `/api/v1/`。所有响应遵循同一个信封格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

除了 `/api/v1/auth/login`，所有端点都需要 `Authorization` header 里带 JWT：

```
Authorization: Bearer <token>
```

深入的行为细节去读对应的功能页——[聊天与消息](./chat)、[Agent 引擎](./agents)、[工具系统](./tools)、[安全与审批](./security)、[LLM Wiki](./wiki)、[多模态创作](./multimodal)、[记忆系统](./memory)、[多渠道接入](./channels)、[模型配置](./models)、[工作空间](./workspaces)、[Doctor](./doctor)。

---

## 认证

```
POST /api/v1/auth/login        # 登录，获取 JWT
GET  /api/v1/users/me          # 获取当前用户
PUT  /api/v1/users/me          # 更新个人资料
PUT  /api/v1/users/me/password # 修改密码
```

**登录示例：**

```bash
curl -X POST http://localhost:18088/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

响应：

```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

---

## 聊天

```
POST /api/v1/chat/{agentId}/message              # 发送消息
GET  /api/v1/chat/{agentId}/stream?conversationId=  # SSE 流式
POST /api/v1/chat/{conversationId}/stop          # 停止进行中的流
GET  /api/v1/chat/{conversationId}/pending-approvals  # 列出等待的审批
```

**发送消息：**

```bash
curl -X POST http://localhost:18088/api/v1/chat/1/message \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"你好，你能做什么？", "conversationId":"conv-abc123"}'
```

**SSE 流式示例：**

```bash
curl -N http://localhost:18088/api/v1/chat/1/stream?conversationId=conv-abc123 \
  -H "Authorization: Bearer YOUR_TOKEN"
```

事件类型和 schema 在 [聊天与消息](./chat) 里。

### 会话

```
GET    /api/v1/conversations                       # 列表（?page&size&agentId）
GET    /api/v1/conversations/{id}/messages         # 取消息
DELETE /api/v1/conversations/{id}                  # 删除
DELETE /api/v1/conversations/{id}/messages         # 清空消息
GET    /api/v1/conversations/{id}/status           # 会话状态
```

---

## Agent

```
GET    /api/v1/agents                # 列表（分页）
GET    /api/v1/agents/{id}            # 获取
POST   /api/v1/agents                 # 创建
PUT    /api/v1/agents/{id}            # 更新（部分）
DELETE /api/v1/agents/{id}            # 软删除

GET    /api/v1/agents/{id}/chat/stream?message=...&conversationId=...  # 流式对话

GET    /api/v1/agents/{id}/workspace/files                    # 列文件
GET    /api/v1/agents/{id}/workspace/files/{filename}         # 取内容
PUT    /api/v1/agents/{id}/workspace/files/{filename}         # 写入
DELETE /api/v1/agents/{id}/workspace/files/{filename}         # 删除
GET    /api/v1/agents/{id}/workspace/prompt-files             # 哪些文件被注入
PUT    /api/v1/agents/{id}/workspace/prompt-files             # 设置注入的文件列表

GET    /api/v1/agents/templates        # 列出模板
POST   /api/v1/agents/templates/{id}   # 从模板创建
```

---

## 工具

```
GET    /api/v1/tools                               # 列表
PUT    /api/v1/tools/{id}                          # 更新
PUT    /api/v1/tools/{id}/toggle?enabled={bool}    # 开关
POST   /api/v1/tools/{name}/test                   # 直接测试
```

---

## 技能

```
GET    /api/v1/skills                                 # 列表（?type=builtin|custom|mcp&tag=...）
GET    /api/v1/skills/{id}                            # 获取
POST   /api/v1/skills                                 # 创建
PUT    /api/v1/skills/{id}                            # 更新
DELETE /api/v1/skills/{id}                            # 删除
PUT    /api/v1/skills/{id}/toggle?enabled={bool}      # 开关
GET    /api/v1/skills/runtime/active                  # 当前活跃的技能
GET    /api/v1/skills/runtime/status                  # 运行时状态
POST   /api/v1/skills/runtime/refresh                 # 重载运行时
```

---

## MCP 服务

```
GET    /api/v1/mcp/servers                             # 列表
GET    /api/v1/mcp/servers/{id}                         # 获取
POST   /api/v1/mcp/servers                              # 创建
PUT    /api/v1/mcp/servers/{id}                         # 更新（PATCH 语义）
DELETE /api/v1/mcp/servers/{id}                         # 删除
PUT    /api/v1/mcp/servers/{id}/toggle?enabled={bool}   # 开关
POST   /api/v1/mcp/servers/{id}/test                    # 测试连接
POST   /api/v1/mcp/servers/refresh                      # 刷新所有
```

请求体 schema 见 [MCP 协议](./mcp)。

---

## LLM Wiki

```
GET    /api/v1/wiki/kbs                                  # 列知识库
POST   /api/v1/wiki/kbs                                  # 创建 KB
GET    /api/v1/wiki/kbs/{id}                             # 获取 KB 详情
PUT    /api/v1/wiki/kbs/{id}                             # 更新 KB
DELETE /api/v1/wiki/kbs/{id}                             # 删除 KB

POST   /api/v1/wiki/kbs/{kbId}/raw                       # 上传原始材料
GET    /api/v1/wiki/kbs/{kbId}/raw                       # 列原始材料
DELETE /api/v1/wiki/raw/{id}                             # 删除原始材料
POST   /api/v1/wiki/raw/{id}/reprocess                   # 重新消化

GET    /api/v1/wiki/kbs/{kbId}/pages                     # 列页面
GET    /api/v1/wiki/pages/{id}                           # 获取页面
PUT    /api/v1/wiki/pages/{id}                           # 编辑页面
DELETE /api/v1/wiki/pages/{id}                           # 删除页面
POST   /api/v1/wiki/pages/{id}/lock                      # 锁定页面
POST   /api/v1/wiki/pages/{id}/unlock                    # 解锁

GET    /api/v1/wiki/kbs/{kbId}/search?q=...              # 全文搜索
GET    /api/v1/wiki/pages/{id}/backlinks                 # 反向链接
```

Agent 可调的 wiki 工具（`wiki_search`、`wiki_read`、`wiki_backlinks`）自动解析 `kbId`。

---

## 多模态

```
POST /api/v1/image/generate              # 生成图像
POST /api/v1/image/edit                  # 编辑图像
POST /api/v1/video/generate              # 生成视频
POST /api/v1/video/from-image            # 图生视频
POST /api/v1/music/generate              # 生成音乐
POST /api/v1/tts/synthesize              # 文本转语音
POST /api/v1/stt/transcribe              # 语音转文本

GET  /api/v1/image/jobs/{id}             # 查异步图像任务状态
GET  /api/v1/video/jobs/{id}             # 查异步视频任务状态
```

见 [多模态创作](./multimodal)。

---

## 记忆

```
POST /api/v1/memory/{agentId}/emergence                        # 手动触发整合
POST /api/v1/memory/{agentId}/summarize/{conversationId}       # 手动触发提取
GET  /api/v1/memory/{agentId}/dreaming/status                  # 上次/下次运行 + 最新 DREAMS.md 条目
```

---

## 安全与审批

### Tool Guard 规则

```
GET    /api/v1/security/guard/config                               # 全局配置
PUT    /api/v1/security/guard/config                               # 更新全局配置
GET    /api/v1/security/guard/rules                                # 列自定义规则
GET    /api/v1/security/guard/rules/builtin                        # 列内置规则
POST   /api/v1/security/guard/rules                                # 创建规则
PUT    /api/v1/security/guard/rules/{id}                           # 更新规则
DELETE /api/v1/security/guard/rules/{id}                           # 删除规则
PUT    /api/v1/security/guard/rules/{id}/toggle?enabled={bool}     # 开关规则
```

### File Guard

```
GET /api/v1/security/guard/config/file-guard   # 获取配置
PUT /api/v1/security/guard/config/file-guard   # 更新配置
```

### 审批

```
GET  /api/v1/approvals?status=pending          # 列 pending 审批
POST /api/v1/approvals/{id}/resolve            # 批准或拒绝
```

请求体：

```json
{ "decision": "approved" }
```

或

```json
{ "decision": "rejected", "notes": "原因" }
```

### 审计日志

```
GET /api/v1/security/audit/logs   # 查询（?toolName, ?decision, ?from, ?to）
GET /api/v1/security/audit/stats  # 统计
GET /api/v1/audit/events          # 完整审计事件查询
```

---

## 模型

```
GET    /api/v1/models                                              # 列出模型
GET    /api/v1/models/enabled                                      # 仅列已启用
GET    /api/v1/models/default                                      # 默认模型
GET    /api/v1/models/active                                       # 活跃模型
PUT    /api/v1/models/active                                       # 设置活跃
POST   /api/v1/models                                              # 创建模型配置
PUT    /api/v1/models/{id}                                         # 更新
DELETE /api/v1/models/{id}                                         # 删除
POST   /api/v1/models/{id}/default                                 # 设为默认

PUT    /api/v1/models/{providerId}/config                          # 更新供应商配置
POST   /api/v1/models/custom-providers                             # 创建自定义供应商
DELETE /api/v1/models/custom-providers/{providerId}                # 删除自定义供应商

POST   /api/v1/models/{providerId}/models                          # 往供应商加模型
DELETE /api/v1/models/{providerId}/models/{modelId}                # 移除模型

POST   /api/v1/models/{providerId}/discover                        # 发现模型
POST   /api/v1/models/{providerId}/discover/apply                  # 应用已发现
POST   /api/v1/models/{providerId}/test-connection                 # 测试供应商连接
POST   /api/v1/models/{providerId}/models/{modelId}/test           # 测试单个模型
```

### 遗留端点

```
GET    /api/v1/model-providers           # 遗留——优先用 /api/v1/models
POST   /api/v1/model-providers
PUT    /api/v1/model-providers/{id}
DELETE /api/v1/model-providers/{id}

GET    /api/v1/model-configs             # 遗留——优先用 /api/v1/models
POST   /api/v1/model-configs
PUT    /api/v1/model-configs/{id}
DELETE /api/v1/model-configs/{id}
```

---

## 渠道

```
GET    /api/v1/channels                                          # 列表
POST   /api/v1/channels                                          # 创建
PUT    /api/v1/channels/{id}                                     # 更新
DELETE /api/v1/channels/{id}                                     # 删除
PUT    /api/v1/channels/{id}/toggle?enabled={bool}               # 开关
GET    /api/v1/channels/status                                   # 每个渠道的连接状态
GET    /api/v1/channels/health                                   # 聚合健康视图

GET    /api/v1/channels/webhook/weixin/qrcode                    # 微信 iLink 二维码
GET    /api/v1/channels/webhook/weixin/qrcode/status             # 扫码状态
```

### 渠道 webhook 回调

| 渠道 | 回调 URL |
|------|----------|
| 钉钉 | `POST /api/v1/channels/webhook/dingtalk` |
| 飞书 | `POST /api/v1/channels/webhook/feishu` |
| 企业微信 | `POST /api/v1/channels/webhook/wecom` |
| Telegram | `POST /api/v1/channels/webhook/telegram` |
| Discord | *（Gateway——无 webhook）* |
| QQ | `POST /api/v1/channels/webhook/qq` |
| Slack | `POST /api/v1/channels/webhook/slack` |
| 微信 | `POST /api/v1/channels/webhook/weixin` |

---

## 定时任务

```
GET    /api/v1/cron-jobs                                # 列表
POST   /api/v1/cron-jobs                                # 创建
PUT    /api/v1/cron-jobs/{id}                           # 更新
DELETE /api/v1/cron-jobs/{id}                           # 删除
PUT    /api/v1/cron-jobs/{id}/toggle?enabled={bool}     # 开关
POST   /api/v1/cron-jobs/{id}/run                       # 立即执行
```

---

## 工作流（1.3.0+）

完整字段、step mode、Pebble 语法见 [工作流](./workflow)。

```
GET    /api/v1/workflows                                # 列表
GET    /api/v1/workflows/{id}                           # 获取（含已发布 revision + 草稿）
POST   /api/v1/workflows                                # 新建
PUT    /api/v1/workflows/{id}/draft                     # 保存草稿（graph_json）
POST   /api/v1/workflows/{id}/publish                   # 发布草稿为新 revision
DELETE /api/v1/workflows/{id}                           # 删除

POST   /api/v1/workflows/draft/generate                 # 自然语言生成 graph_json 草稿
POST   /api/v1/workflows/{id}/preview-compile           # 静态检查 + Pebble 校验，不发布

POST   /api/v1/workflows/{id}/runs                      # 起一个 run（异步）
GET    /api/v1/workflows/{id}/runs                      # run 列表
GET    /api/v1/workflows/runs/{runId}                   # run 详情 + 每步 input/output/token/duration
POST   /api/v1/workflows/runs/{runId}/resume            # await_approval 后恢复
POST   /api/v1/workflows/runs/{runId}/cancel            # 取消运行中
```

---

## 触发器（1.3.0+）

6 种 pattern type、事件治理、跨实例一致性见 [触发器](./triggers)。

```
GET    /api/v1/triggers                                 # 列表
GET    /api/v1/triggers/{id}                            # 获取
POST   /api/v1/triggers                                 # 新建
PUT    /api/v1/triggers/{id}                            # 更新
DELETE /api/v1/triggers/{id}                            # 删除
PUT    /api/v1/triggers/{id}/toggle?enabled={bool}      # 开关

POST   /api/v1/triggers/events                          # 通用事件入口（webhook / 桥接外部系统）
                                                         # 立即 ACK 200，异步派发
GET    /api/v1/triggers/{id}/events                     # 该 trigger 的事件历史
```

---

## Token 用量

```
GET /api/v1/token-usage?startDate=&endDate=&modelName=&providerId=
```

---

## 系统设置

```
GET /api/v1/settings              # 所有设置
PUT /api/v1/settings              # 更新多个
GET /api/v1/settings/language     # 当前语言
PUT /api/v1/settings/language     # 更新语言
PUT /api/v1/settings/{key}        # 更新单个 key
```

---

## 仪表盘

```
GET /api/v1/dashboard/summary             # 用量汇总卡片
GET /api/v1/dashboard/trends              # 趋势图（?range=7d|30d|90d）
GET /api/v1/dashboard/top-agents          # 最常用 Agent
GET /api/v1/dashboard/top-tools           # 最常用工具
```

---

## 工作空间

```
GET    /api/v1/workspaces                             # 列表
GET    /api/v1/workspaces/{id}                        # 获取
POST   /api/v1/workspaces                             # 创建
PUT    /api/v1/workspaces/{id}                        # 更新
DELETE /api/v1/workspaces/{id}                        # 删除（仅 owner）
GET    /api/v1/workspaces/{id}/members                # 列成员
POST   /api/v1/workspaces/{id}/members                # 添加成员
DELETE /api/v1/workspaces/{id}/members/{userId}       # 移除成员
PUT    /api/v1/workspaces/{id}/members/{userId}/role  # 变更角色
```

---

## Doctor（健康检查）

```
GET /api/v1/doctor/run          # 运行所有检查
GET /api/v1/doctor/checks       # 缓存的检查结果
```

---

## 错误响应

```json
{
  "code": 400,
  "message": "Validation failed: name is required"
}
```

### 常见状态码

| 状态码 | 含义 |
|--------|------|
| 200 | 成功 |
| 400 | 错误请求 |
| 401 | 未授权 |
| 403 | 禁止 |
| 404 | 未找到 |
| 500 | 服务端错误 |

---

## 分页

列表端点按一致的 shape 返回分页结果：

```json
{
  "code": 200,
  "data": {
    "records": [ ],
    "total": 42,
    "current": 1,
    "size": 20,
    "pages": 3
  }
}
```

| 字段 | 用途 |
|------|------|
| `records` | 当前页的条目数组 |
| `total` | 总条数 |
| `current` | 当前页（从 1 开始） |
| `size` | 每页条数 |
| `pages` | 总页数 |

---

## 下一步

- [快速开始](./quickstart)——让服务器跑起来
- [安全与审批](./security)——JWT + 审批流程
- [聊天与消息](./chat)——SSE 事件格式
- [LLM Wiki](./wiki)——Wiki 端点行为
