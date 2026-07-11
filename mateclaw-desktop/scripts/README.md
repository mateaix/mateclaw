# MateClaw Desktop - Build & Publish Scripts

## Scripts

| Script | Description |
|--------|-------------|
| `build.sh` | Build backend JAR (frontend + Spring Boot) |
| `download-jre.sh` | Download Adoptium JRE 21 for target platform |
| `build-all-platforms.sh` | One-click build for all platforms (macOS + Windows) |
| `publish-github.sh` | Publish release artifacts to GitHub Releases |

## Quick Start

### Full Build + Publish (recommended)

```bash
cd mateclaw-desktop

# Step 1: Build for all platforms (no publish)
bash scripts/build-all-platforms.sh --all

# Step 2: Publish to GitHub (via proxy)
bash scripts/publish-github.sh --proxy
```

### Step-by-Step Build

```bash
cd mateclaw-desktop

# 1. Build backend JAR (frontend + Spring Boot)
bash scripts/build.sh

# 2. Download JRE for target platform
bash scripts/download-jre.sh mac-arm64    # Apple Silicon
bash scripts/download-jre.sh mac-x64      # Intel Mac
bash scripts/download-jre.sh win-x64      # Windows x64
bash scripts/download-jre.sh win-arm64    # Windows ARM

# 3. Build frontend and package
npm run build
npx electron-builder --mac               # macOS
npx electron-builder --win --x64         # Windows x64
npx electron-builder --win --arm64       # Windows ARM
```

## build.sh

Build the backend JAR, includes three steps:

1. Build Vue 3 frontend to `mateclaw-server/src/main/resources/static`
2. Package Spring Boot fat JAR via Maven
3. Copy JAR to `mateclaw-desktop/resources/app.jar`

```bash
bash scripts/build.sh
```

**Prerequisites:** Node.js (pnpm or npm), Maven (or mvnw)

## download-jre.sh

Download Adoptium JRE 21 for the target platform.

```bash
# Auto-detect current platform
bash scripts/download-jre.sh

# Specify platform
bash scripts/download-jre.sh mac-arm64
bash scripts/download-jre.sh mac-x64
bash scripts/download-jre.sh win-x64
bash scripts/download-jre.sh win-arm64
```

JRE will be saved to `resources/jre/{os}-{arch}/`.

## build-all-platforms.sh

Orchestrates the full build pipeline: JAR build -> JRE download -> frontend compile -> electron-builder package.

```bash
bash scripts/build-all-platforms.sh --all       # macOS + Windows
bash scripts/build-all-platforms.sh --mac-only   # macOS only
bash scripts/build-all-platforms.sh --win-only   # Windows only
```

Build artifacts are output to `release/` directory.

## publish-github.sh

Publish build artifacts from `release/` to GitHub Releases via `gh` CLI.

### Basic Usage

```bash
bash scripts/publish-github.sh                   # Direct upload
bash scripts/publish-github.sh --draft            # Create draft release
bash scripts/publish-github.sh --tag=v1.1.0       # Custom tag
```

### With Proxy (for slow or restricted networks)

```bash
bash scripts/publish-github.sh --proxy                      # Default proxy: 127.0.0.1:7890
bash scripts/publish-github.sh --proxy=192.168.1.1:8080     # Custom proxy
bash scripts/publish-github.sh --proxy --draft               # Draft + proxy
```

### Retry Failed Upload

```bash
bash scripts/publish-github.sh --proxy --retry               # Delete old release, re-upload
bash scripts/publish-github.sh --proxy --retry --draft        # Retry as draft
```

### All Options

| Option | Description |
|--------|-------------|
| `--proxy` | Use proxy `127.0.0.1:7890` for GitHub upload |
| `--proxy=host:port` | Use custom proxy address |
| `--draft` | Create as draft release |
| `--tag=vX.Y.Z` | Custom tag (default: `v{version}` from package.json) |
| `--retry` | Delete existing release for this tag before re-uploading |

**Prerequisites:** [gh CLI](https://cli.github.com/) installed and authenticated (`gh auth login`)

## npm Scripts

These scripts are also available as npm commands:

```bash
npm run setup:jar              # build.sh
npm run setup:jre              # download-jre.sh
npm run setup                  # build.sh + download-jre.sh
npm run package:mac            # Build frontend + electron-builder --mac
npm run package:win            # Build frontend + electron-builder --win
npm run package:all            # build-all-platforms.sh --all
npm run publish:github         # publish-github.sh
npm run publish:github:draft   # publish-github.sh --draft
```

## Typical Workflow

```
build.sh                     download-jre.sh
    |                              |
    v                              v
resources/app.jar            resources/jre/{os}-{arch}/
    |                              |
    +--------- electron-builder ---+
                    |
                    v
              release/
              ├── MateClaw_1.0.0_arm64.dmg
              ├── MateClaw_1.0.0_x64.dmg
              ├── MateClaw_1.0.0_arm64.zip
              ├── MateClaw_1.0.0_x64.zip
              ├── MateClaw_1.0.0_arm64_Setup.exe
              ├── MateClaw_1.0.0_x64_Setup.exe
              ├── latest-mac.yml
              └── latest.yml
                    |
                    v
            publish-github.sh
                    |
                    v
            GitHub Releases
```
