# Doctor

Doctor is the in-app health drawer. It reports the current local instance status from the backend health service; it is not a separate scheduled diagnostics subsystem.

Open it from the layout status button / Settings area. The drawer calls the backend each time it opens or when you click refresh.

## Current Backend API

```bash
curl http://localhost:18088/api/v1/system/health \
  -H "Authorization: Bearer <token>"
```

Response shape:

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "overall": "healthy",
    "checks": [
      {
        "name": "default-model",
        "status": "healthy",
        "message": "Default model: qwen-plus",
        "action": null
      }
    ]
  }
}
```

`overall` is one of `healthy`, `warning`, or `error`. Each check has:

| Field | Meaning |
|---|---|
| `name` | Stable check key such as `default-model`, `database`, `browser`, `provider:<id>`, `mcp:<name>` |
| `status` | `healthy`, `warning`, or `error` |
| `message` | Short diagnostic text shown in the drawer |
| `action` | Optional `{ label, route }` hint for where to fix the issue |

## What It Checks Today

The current `SystemHealthService` checks:

| Check | What it verifies | Typical action |
|---|---|---|
| Default model | A default model is configured and loadable | `/settings/models` |
| Providers | API-key providers are configured when required | `/settings/models` |
| Enabled MCP servers | Enabled MCP servers have a successful connection result | `/settings/mcp-servers` |
| Database initialization | First-run bootstrap has completed | `/setup` |
| Browser diagnostics | Browser launch pre-flight for browser tooling | `/api/v1/system/browser-health` |

There is also a direct browser diagnostics endpoint:

```bash
curl http://localhost:18088/api/v1/system/browser-health \
  -H "Authorization: Bearer <token>"
```

## Not Implemented In The Current Source Tree

Older docs mentioned `/api/v1/doctor/run`, `/api/v1/doctor/checks`, `/api/v1/doctor/history`, scheduled background Doctor runs, `mate_doctor_check`, and `mate_doctor_check_history`. Those endpoints and tables are not present in the current backend source. Use `/api/v1/system/health` for the current health surface.

## Related Pages

- [API Reference](./api) - source-aligned route inventory
- [Models](./models) - model/provider setup
- [MCP](./mcp) - MCP server setup
- [Security & Approval](./security) - Tool Guard and approval behavior
