# MateClaw v1.0.101

## What's New

### Mobile Responsive UI
- Sidebar transforms to slide-in drawer with hamburger menu on mobile (<=768px)
- Conversation panel becomes a toggleable overlay on mobile
- Welcome screen centers properly with auto text wrapping, single-column suggestion cards
- Chat header auto-simplifies: icon-only agent badge, adaptive model selector
- Reduced padding/gaps across all chat components for mobile screens

### Drag & Drop File Upload
- Drag-and-drop files and folders directly into the chat area
- Electron: directory references via local path; Web: recursive file collection and upload

### Multi-Agent Collaboration
- `DelegateAgentTool` for agent-to-agent task delegation

### LLM Context Awareness
- Current datetime automatically injected into LLM context for time-aware responses

### MCP Server
- Pre-configured GitHub MCP Server in seed data (ready to use out of the box)

### Ollama Auto-Discovery
- Auto-detect local Ollama instance on startup
- Pre-configured 6 popular local models (Qwen3, Llama, DeepSeek, Gemma, Phi, Mistral)
- Local providers sorted first in model management UI

### Model Management Enhancements
- Provider list grouped by Local / Cloud with section headers
- Zhipu AI models updated to GLM-5 series (GLM-5-Turbo / GLM-5V-Turbo / GLM-5 / GLM-5.1)
- 20+ model providers supported

### API Docs
- Replaced Knife4j with SpringDoc OpenAPI 2.8.16 (`/swagger-ui.html`)

## Bug Fixes

- **Security**: Fixed SPA frontend route refresh returning 401
- **i18n**: Window title dynamically set from language pack instead of hardcoded
- **i18n**: Fixed 5 hardcoded Chinese strings in approval bar
- **i18n**: Fixed hardcoded time formatting (locale-aware now)
- **Guard**: Aligned tool guard rule names with runtime `@Tool` method names
- **LLM**: Fixed Zhipu connection test 404
- **UI**: Fixed suggestion cards grid misalignment with longer text
- **Upload**: File upload size limit raised to 100MB

## Download

| Platform | File | Note |
|----------|------|------|
| macOS Apple Silicon | `MateClaw_1.0.101_arm64.dmg` | M1 / M2 / M3 / M4 / M5 |
| macOS Intel | `MateClaw_1.0.101_x64.dmg` | Intel Mac |
| Windows | `MateClaw_1.0.101_Setup.exe` | Windows 10/11 (x64+arm64) |
| Windows x64 | `MateClaw_1.0.101_x64_Setup.exe` | Windows 10/11 x64 |
| Windows ARM64 | `MateClaw_1.0.101_arm64_Setup.exe` | Windows ARM64 |

> zip / blockmap / yml files are for auto-update support.

## Links

- GitHub: https://github.com/matevip/mateclaw
- Gitee: https://gitee.com/matevip_admin/mateclaw
- Documentation: https://mateclaw.com

**Full Changelog**: https://github.com/matevip/mateclaw/compare/v1.0.0...v1.0.101
