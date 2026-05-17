# Desktop App

**Double-click. Wait thirty seconds. Log in. Use it.**

That's the desktop app in four sentences. No Java to install. No browser to open. No docker compose file. No port to remember. MateClaw's desktop edition bundles Electron, a JRE 21 runtime, and the packaged Spring Boot server JAR into a single installer. **Your users never know Java is underneath.**

This page is for people who want to run it, build it, or debug it.

---

## Architecture

```
┌──────────────────────────────────────────┐
│            Electron Shell                 │
│  ┌────────────────────────────────────┐  │
│  │      BrowserWindow (Chromium)      │  │
│  │  ┌──────────────────────────────┐  │  │
│  │  │   Vue 3 Frontend (dist/)     │  │  │
│  │  │   Element Plus + Tailwind    │  │  │
│  │  └────────────┬─────────────────┘  │  │
│  └───────────────┼────────────────────┘  │
│                  │ HTTP / SSE             │
│  ┌───────────────▼────────────────────┐  │
│  │   Spring Boot Backend (child proc)  │  │
│  │   dynamic port on 127.0.0.1         │  │
│  │   Bundled JRE 21 + H2 file DB       │  │
│  └────────────────────────────────────┘  │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │   electron-updater Auto Update      │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

Three things living inside one process tree:

1. **Electron main process** — window, tray, IPC, backend lifecycle
2. **BrowserWindow (Chromium)** — renders the Vue 3 frontend (same code as the web version)
3. **Spring Boot backend** — spawned as a child process, listens on localhost only

The backend picks a **free port dynamically** at startup so you don't collide with anything else on your machine. The frontend queries the main process for the actual port before the first API call.

### Key features

- Native window, no browser dependency
- System tray integration for background operation
- **Bundled JRE 21** — users never install Java
- **Auto update** via electron-updater (GitHub Releases)
- **Local-first data** — everything in a user directory
- **Dynamic backend port** — no port collisions
- **UI hot update** — frontend assets can be updated without repackaging the installer
- Cross-platform (macOS, Windows, Linux)

---

## Supported platforms

| Platform | Architecture | Status |
|----------|-------------|--------|
| macOS | Intel (x64) | Stable |
| macOS | Apple Silicon (ARM64) | Stable |
| Windows | x64 | Stable |
| Linux | x64 | Stable |

---

## Prerequisites (for building, not for running)

If you're **running** the app: download and install. Full stop.

If you're **building** the app:

| Tool | Version | Purpose |
|------|---------|---------|
| Node.js | 18+ | Frontend build + Electron |
| pnpm / npm | 8+ / 9+ | Package manager |
| Java | 21+ | Backend compilation + dev mode (production builds bundle the JRE) |
| Maven | 3.8+ | Backend build |

---

## Module layout

```
mateclaw-desktop/
├── electron/
│   ├── main/index.ts           # Main process — backend lifecycle, auto-update, tray
│   └── preload/index.ts        # IPC bridge
├── src/                         # Vue 3 renderer source
├── resources/
│   ├── jre/                     # Bundled JRE (per platform/arch)
│   └── app.jar                  # Packaged Spring Boot backend JAR
├── build/                       # App icons
├── electron-builder.json        # Packaging config
├── package.json
└── vite.config.ts
```

---

## Development mode

```bash
cd mateclaw-desktop
pnpm install
pnpm dev
```

In dev mode:

1. Vite starts the frontend dev server (HMR enabled)
2. Electron main process launches and loads the Vite dev URL
3. Main process spawns the Spring Boot JAR as a child process on a free port
4. Frontend talks to the backend via HTTP/SSE

Frontend changes trigger HMR. Main-process changes restart Electron.

---

## Production build

```bash
cd mateclaw-desktop
pnpm build && npx electron-builder --mac     # macOS
pnpm build && npx electron-builder --win     # Windows
pnpm build && npx electron-builder --linux   # Linux
```

Output lands in `release/`:

| Platform | Artifact | Notes |
|----------|----------|-------|
| macOS | `.dmg` + `.zip` | Drag into Applications |
| Windows | `.exe` (NSIS) | Custom install dir |
| Linux | `.AppImage` | Add execute permission and run |

### Build prerequisites — the full sequence

```bash
# 1. Build frontend static assets
cd mateclaw-ui
pnpm install && pnpm build

# 2. Build backend JAR (includes frontend assets in static/)
cd ../mateclaw-server
mvn clean package -DskipTests

# 3. Copy JAR to desktop resources
cp target/mateclaw-server.jar ../mateclaw-desktop/resources/app.jar

# 4. Download platform-specific JRE
cd ../mateclaw-desktop
bash scripts/download-jre.sh

# 5. Build the installer
pnpm build && npx electron-builder
```

---

## Java backend lifecycle

The Electron main process manages the Spring Boot backend through Node.js `child_process`:

1. **Startup** — spawn the JAR using the bundled JRE, hand it a dynamic port, wait for ready
2. **Readiness check** — poll `http://127.0.0.1:{port}` until the backend responds, then load the frontend
3. **Runtime** — frontend communicates via REST + SSE
4. **Shutdown** — graceful shutdown signal (SIGTERM / taskkill), wait for exit, close the window

If the backend crashes mid-session, the main process notices and shows an error dialog with the log tail. **No blank white window.**

---

## Auto update

electron-updater integration with GitHub Releases.

### Flow

1. On startup, checks GitHub Releases for a new version
2. When one is found, an in-UI notification shows version + changelog
3. On confirmation, downloads with a progress bar
4. Once downloaded, install now / install on next launch
5. App exits, replaces files, restarts

### Configuration

```json
{
  "publish": [
    {
      "provider": "github",
      "owner": "matevip",
      "repo": "mateclaw"
    }
  ]
}
```

### UI hot update (no repackage)

Frontend assets can be **hot-updated independently** — a frontend-only fix doesn't require a new installer. See `mateclaw-desktop/scripts/` and `desktop-ui-hot-update.md` for the hot-update build flow.

---

## Data storage

| OS | Path |
|-----|------|
| macOS | `~/Library/Application Support/MateClaw/data/` |
| Windows | `%APPDATA%/MateClaw/data/` |
| Linux | `~/.local/share/MateClaw/data/` |

Logs, workspace files, skill scripts, wiki content all live alongside the database in the same user directory. Back it up before major changes.

---

## `electron-builder.json` reference

| Setting | Purpose |
|---------|---------|
| `appId` | `vip.mate.mateclaw` — system registration and code signing |
| `productName` | App name in title bar and installer |
| `publish` | Auto-update source (GitHub Releases) |
| `extraResources` | JRE and `app.jar` |
| `mac.target` | `dmg` + `zip`, `arm64` and `x64` |
| `win.target` | `nsis` installer |
| `linux.target` | `AppImage` |
| `mac.hardenedRuntime` | Required for signing + notarization |
| `nsis.oneClick` | `false` — lets users choose install directory |

---

## Environment variables

The desktop app reads env vars the same way the standalone backend does. But there's an easier way: **configure everything through the Settings page** after launch. API keys go into the encrypted `mate_model_provider` table and stay there.

---

## Troubleshooting

### Blank window

1. Backend failed to start — check logs for crash details
2. Port conflict — dynamic port picker handles most cases, restrictive firewalls can break it
3. Bundled JRE corrupted — reinstall
4. Check logs below

### Code signing warnings

- **macOS** — right-click → **Open** to bypass Gatekeeper (first launch). Production: Apple Developer certificate + notarization. See `mateclaw-desktop/CODESIGNING.md`.
- **Windows** — SmartScreen warning → **More info → Run anyway**. Production: EV code signing certificate.

### Desktop app won't start

1. Installed app bundles JRE — you don't need Java. Dev build from source: verify `java -version` shows 21+.
2. Check logs:
   - macOS: `~/Library/Logs/MateClaw/`
   - Windows: `%APPDATA%/MateClaw/logs/`
   - Linux: `~/.local/share/MateClaw/logs/`
3. Launch from terminal to see console output
4. Confirm backend port isn't blocked

### WeCom auth popup

The WeCom QR-code authorization flow **must open in an in-app popup** (not the system browser) so the `postMessage` callback works. MateClaw handles this in `setWindowOpenHandler` — `work.weixin.qq.com` domain opens as an in-app popup window.

---

## Notes

- First launch takes 10–30 seconds (database init)
- Closing the window doesn't stop the background service — use the system tray menu to fully quit
- Back up the user data directory regularly
- Bundled JRE makes the installer 80–120 MB

---

## Next

- [Quick Start](./quickstart) — fastest path through the desktop experience
- [Configuration](./config) — runtime settings
- [Admin Console](./console) — the UI inside the Electron window
