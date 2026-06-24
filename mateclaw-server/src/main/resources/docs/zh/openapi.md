# OpenAPI / Swagger 指南

MateClaw 后端集成了 [SpringDoc OpenAPI](https://springdoc.org/)（`springdoc-openapi-starter-webmvc-ui`），自动把所有 `@RestController` 的端点生成为 OpenAPI 3 文档，并提供在线调试 UI。本页说明如何访问和使用。

> 本文是**机读文档**入口。人读的端点详解、通用约定与完整路由索引见 [API 参考](./api.md)。两者关系：Swagger = 由源码注解自动生成的机读契约；`api.md` = 旗舰端点人读详解 + 通用约定。

## 访问地址

部署后端后，相对服务地址（本地默认端口 `18088`）：

| 地址 | 用途 |
|---|---|
| `/swagger-ui.html` | Swagger UI 在线浏览 + 调试（Authorize、Try it out） |
| `/v3/api-docs` | OpenAPI 3 JSON（可导入 Postman / Apifox / Insomnia） |
| `/v3/api-docs.yaml` | OpenAPI 3 YAML（可下载、纳入版本库或导入工具） |

本地示例：

```bash
# 浏览器打开
open http://localhost:18088/swagger-ui.html

# 下载 YAML
curl http://localhost:18088/v3/api-docs.yaml -o mateclaw-openapi.yaml
```

## 鉴权（Authorize）

页面右上角 **Authorize** 按钮。在 `bearerAuth` 输入框填入 token（不带 `Bearer ` 前缀，UI 会自动加）：

- **JWT**：登录 `POST /api/v1/auth/login` 拿到的 `token` 字段（`eyJ...` 开头）。
- **Personal Access Token**：在 `POST /api/v1/auth/tokens` 创建的 `mc_...` token。

两种 token 都走标准 `Authorization: Bearer <token>` 头，后端 `JwtAuthFilter` 按前缀自动分发（JWT → JWT 校验，`mc_` → PAT 校验）。授权后，受保护的 `@RequireWorkspaceRole` / `@RequireGlobalAdmin` 端点即可在 UI 内直接 Try it out。

> SSE 流式端点（`/chat/stream` 等）在 Swagger UI 里调试体验有限 —— UI 对 `text/event-stream` 的渲染是缓冲式的。正式集成 SSE 请按 [API 参考](./api.md#流式对话-post-apiv1chatstream) 用 `curl -N` 或 `fetch()` 流式 reader。

## 端点覆盖范围

SpringDoc 自动扫描所有 `@RestController`，约 85% 的 Controller 已标注 `@Tag`（分组）与 `@Operation(summary)`（方法摘要），所以 Swagger UI 的分组与端点说明基本齐全。

**当前未做的注解增强**（不在本次范围，留作后续）：

- 没有 `@Parameter` 描述、`@ApiResponse` 错误码、请求体 `@Schema` —— 这些字段文档以 `api.md` 人读详解为准。
- 公开端点（登录、SSE 等）没有逐个加 `@SecurityRequirements({})` opt-out，所以 Swagger 上会显示锁图标，但实际调用不受影响（`SecurityConfig` 已放行）。

## 配置项

全局 OpenAPI 元信息（标题、描述、版本、服务器地址）由 `OpenApiConfig` Bean 驱动，可通过 `application.yml` 的 `mateclaw.openapi.*` 覆盖：

```yaml
mateclaw:
  openapi:
    title: ${MATECLAW_OPENAPI_TITLE:MateClaw REST API}
    version: ${MATECLAW_OPENAPI_VERSION:1.0}
    server-url: ${MATECLAW_OPENAPI_SERVER_URL:}   # 留空则从请求 host 推导
    description: ${MATECLAW_OPENAPI_DESCRIPTION:}  # 留空则用内置默认描述
    expose-ui: ${MATECLAW_OPENAPI_EXPOSE_UI:true}  # 是否公开 Swagger/OpenAPI 路径，见下方安全章节
```

`server-url` 留空时由 SpringDoc 从请求 host 推导，避免 "Try it out" 打到错误地址；生产若需固定（如反代后），设 `MATECLAW_OPENAPI_SERVER_URL=https://mate.example.com`。

## 🔒 访问控制：Swagger 生产默认收口

Swagger UI / OpenAPI 文档路径（`/swagger-ui*`、`/v3/api-docs*`、`/webjars/**`）的访问由 `mateclaw.openapi.expose-ui` 开关控制，并由 `SecurityConfig.filterChain` 显式强制（不再依赖 `.anyRequest().permitAll()` 兜底）：

| `expose-ui` | 行为 | 默认生效的场景 |
|---|---|---|
| `true` | 公开可访问，无需登录即可浏览全部端点结构（含请求/响应 schema） | 本地 / 默认 profile（H2、桌面版） |
| `false` | 需要全局管理员（`ROLE_ADMIN`）；匿名访问返回 401，非管理员返回 403 | 生产数据库 profile（`mysql` / `kingbase` / `postgres`） |

- 本地开发：默认 `true`，`http://localhost:18088/swagger-ui.html` 直接可访问。
- 公网生产：默认 `false`，已收口。如确需在内网/预发环境临时打开，设 `MATECLAW_OPENAPI_EXPOSE_UI=true`。
- 注意：锁定后浏览器直接访问 `/swagger-ui.html` 不会自动携带 SPA 的 JWT（token 存于 localStorage 而非 Cookie），因此即使管理员也无法在浏览器里直接打开；如需调试，临时置 `expose-ui=true` 或改用带 `Authorization` 头的客户端拉取 `/v3/api-docs`。
- 访问规则只在 `SecurityConfig`，不在 `OpenApiConfig`。

## 关联

- [API 参考（人读）](./api.md) —— 旗舰端点详解 + 通用约定 + 完整路由索引
- [WebChat 接入指南](./webchat.md) —— 外部网站 HTTP / SSE 集成（含 SSE 事件协议）
