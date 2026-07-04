# OpenAPI / Swagger Guide

The MateClaw backend integrates [SpringDoc OpenAPI](https://springdoc.org/) (`springdoc-openapi-starter-webmvc-ui`), which auto-generates an OpenAPI 3 document from every `@RestController` and serves an interactive debugging UI. This page covers how to access and use it.

> This is the **machine-readable** doc entry point. For the human-readable endpoint details, conventions, and the full route inventory, see the [API Reference](./api.md). Relationship: Swagger = the auto-generated, machine-readable contract from source annotations; `api.md` = flagship endpoint walkthroughs + shared conventions.

## URLs

After deploying the backend, relative to the server address (default local port `18088`):

| URL | Purpose |
|---|---|
| `/swagger-ui.html` | Swagger UI — browse + debug (Authorize, Try it out) |
| `/v3/api-docs` | OpenAPI 3 JSON (import into Postman / Apifox / Insomnia) |
| `/v3/api-docs.yaml` | OpenAPI 3 YAML (download, commit, or import) |

Local examples:

```bash
# Open in a browser
open http://localhost:18088/swagger-ui.html

# Download the YAML
curl http://localhost:18088/v3/api-docs.yaml -o mateclaw-openapi.yaml
```

## Authentication (Authorize)

Use the **Authorize** button at the top right. In the `bearerAuth` field, paste a token **without** the `Bearer ` prefix (the UI adds it automatically):

- **JWT**: the `token` field returned by `POST /api/v1/auth/login` (starts with `eyJ...`).
- **Personal Access Token**: a `mc_...` token created via `POST /api/v1/auth/tokens`.

Both go through the standard `Authorization: Bearer <token>` header; the backend `JwtAuthFilter` dispatches by prefix (JWT → JWT verification, `mc_` → PAT verification). Once authorized, protected `@RequireWorkspaceRole` / `@RequireGlobalAdmin` endpoints can be called directly via Try it out.

> Debugging SSE streaming endpoints (`/chat/stream`, etc.) in Swagger UI is limited — the UI buffers `text/event-stream` responses. For real SSE integration, follow the [API Reference](./api.md) and use `curl -N` or a `fetch()` streaming reader.

## Endpoint coverage

SpringDoc auto-scans every `@RestController`. About 85% of controllers already carry `@Tag` (grouping) and `@Operation(summary)` (method summary), so the UI's grouping and endpoint descriptions are largely complete.

**Annotation enhancements not yet done** (out of scope for this pass, left for later):

- No `@Parameter` descriptions, `@ApiResponse` error codes, or request-body `@Schema` — for field-level docs, defer to the human-readable `api.md` walkthroughs.
- Public endpoints (login, SSE, etc.) are not individually opted out with `@SecurityRequirements({})`, so they show a lock icon in Swagger even though `SecurityConfig` already permits them — actual calls are unaffected.

## Configuration

Global OpenAPI metadata (title, description, version, server URL) is driven by the `OpenApiConfig` bean and overridable via `mateclaw.openapi.*` in `application.yml`:

```yaml
mateclaw:
  openapi:
    title: ${MATECLAW_OPENAPI_TITLE:MateClaw REST API}
    version: ${MATECLAW_OPENAPI_VERSION:1.0}
    server-url: ${MATECLAW_OPENAPI_SERVER_URL:}   # empty → derived from request host
    description: ${MATECLAW_OPENAPI_DESCRIPTION:}  # empty → built-in default
    expose-ui: ${MATECLAW_OPENAPI_EXPOSE_UI:true}  # whether the Swagger/OpenAPI paths are public, see the security section below
```

When `server-url` is empty, SpringDoc derives it from the request host so "Try it out" hits the right address; for fixed production URLs (e.g. behind a reverse proxy), set `MATECLAW_OPENAPI_SERVER_URL=https://mate.example.com`.

## 🔒 Access control: Swagger is locked down by default in production

Access to the Swagger UI / OpenAPI document paths (`/swagger-ui*`, `/v3/api-docs*`, `/webjars/**`) is controlled by the `mateclaw.openapi.expose-ui` flag and enforced explicitly in `SecurityConfig.filterChain` (no longer relying on the `.anyRequest().permitAll()` fallthrough):

| `expose-ui` | Behavior | Default profile |
|---|---|---|
| `true` | Publicly accessible — anyone can browse the full endpoint surface (incl. request/response schemas) without login | Local / default profile (H2, desktop) |
| `false` | Requires a global admin (`ROLE_ADMIN`); anonymous → 401, non-admin → 403 | Production database profiles (`mysql` / `kingbase` / `postgres`) |

- Local dev: defaults to `true`, so `http://localhost:18088/swagger-ui.html` is reachable directly.
- Public production: defaults to `false` (locked down). To temporarily open it on an internal/staging host, set `MATECLAW_OPENAPI_EXPOSE_UI=true`.
- Note: once locked, opening `/swagger-ui.html` in a browser won't automatically carry the SPA's JWT (the token lives in localStorage, not a cookie), so even an admin cannot open it from the browser directly. To debug, either set `expose-ui=true` temporarily, or fetch `/v3/api-docs` with a client that sends the `Authorization` header.
- The access rule lives only in `SecurityConfig`, not `OpenApiConfig`.

## See also

- [API Reference (human-readable)](./api.md) — flagship endpoint walkthroughs + conventions + full route inventory
- [WebChat integration guide](./webchat.md) — external-site HTTP / SSE integration (incl. the SSE event protocol)
