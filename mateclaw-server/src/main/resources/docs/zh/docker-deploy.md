# Docker 部署

桌面端之外的唯一推荐生产部署方式。一条 `docker compose up -d` 起三个容器：MySQL、SearXNG、mateclaw-server。

这一页覆盖**要求、步骤、验证、常见坑**。配置变量明细请看 [配置说明](./config)。

---

## 前置要求

| 项 | 最低 | 推荐 | 备注 |
|---|---|---|---|
| Docker Engine | 24.0+ | 最新稳定 | `docker --version` 确认 |
| Docker Compose | v2.20+ | v2.30+ | `docker compose version`（注意是 `compose` 不是 `compose`） |
| 宿主 RAM | 4 GB | 8 GB+ | 浏览器工具启动时 Chromium 会吃 1-2 GB |
| 磁盘空间 | 6 GB | 20 GB+ | 镜像约 2 GB + MySQL 数据 + 工作空间文件 |
| /dev/shm | 默认 | compose 已自动设 2 GB | Chromium 用共享内存做渲染，默认 64 MB 会 SIGBUS |
| 网络 | 出公网 | — | 拉镜像 + 调 LLM API |

**不需要**：宿主装 Java / Node / Maven / Chrome / Python —— 全部在镜像里。

---

## 三个容器

| 服务 | 镜像 | 作用 | 暴露端口 |
|---|---|---|---|
| `mysql` | `mysql:8.0` | 业务数据存储 | `3306` |
| `searxng` | 本地构建 `./docker/searxng/` | 无 API Key 搜索兜底 | `8088` |
| `mateclaw-server` | 本地构建 `mateclaw-server/Dockerfile` | Spring Boot 后端 + 内置浏览器 | `18080` |

---

## SearXNG 搜索服务

### 为什么要自己打镜像

`docker/searxng/Dockerfile` 从官方 `searxng/searxng:latest` 派生，**把自定义 `settings.yml` 打进 `/etc/searxng/settings.yml`**。这不是洁癖，是**必须**：

- **上游默认只开 `html` 格式**，mateclaw 后端请求的是 `GET /search?q=...&format=json` —— 默认配置下直接返回 HTML 错误页，`SearXNGSearchProvider` 解析失败返回空列表，UI 显示"搜索暂时不可用"
- **上游默认启用反爬 Limiter 插件**，拦截没有 JS / Cookie 的服务端调用，回 HTTP 429

我们的 `docker/searxng/settings.yml` 做了三件事：

1. `search.formats: [html, json]` —— 放开 JSON
2. `server.limiter: false` —— 关闭反爬限流
3. 收敛引擎列表到可靠子集（DuckDuckGo / Bing / Brave / Wikipedia / Google / Startpage），裁掉默认那几十个不常用的

**不要**改成从宿主 bind-mount `settings.yml`，早期版本踩过坑 —— 宿主目录不存在时 Docker 会自动创建空目录把文件盖掉，容器起来就没配置了。如果要改 settings.yml，编辑 `docker/searxng/settings.yml` 然后：

```sh
docker compose build searxng
docker compose up -d searxng
```

### 搜索 provider 降级链

后端 `SearchProviderRegistry` 按以下优先级选：

1. 用户在「设置 → 搜索」里显式指定的 provider（`searchProvider` 配置项）
2. 按 `autoDetectOrder` 遍历，**优先选已配置 API Key 的付费 provider**（Serper order=1，Tavily order=2）
3. 回退到 keyless —— SearXNG（order=50）优先于 DuckDuckGo（order=100）

一台全新容器、啥 API Key 都没配的情况下，默认就是 **SearXNG 接所有搜索流量**。

### 验证 SearXNG 通路

```sh
# 1. 直接打容器
curl -s 'http://localhost:8088/search?q=test&format=json' | head -5
# 期望：{"query": ..., "results": [...]}
# 如果拿到 HTML：settings.yml 没生效

# 2. 从 mateclaw-server 容器内部打
docker exec mateclaw-server wget -qO- 'http://searxng:8080/search?q=test&format=json' | head -5
# 如果不通：compose 网络有问题

# 3. 在 UI 聊天里让 agent 搜点东西，看后端日志
docker compose logs -f mateclaw-server | grep "搜索 provider"
# 期望看到：搜索 provider 解析: searxng (source=keyless-fallback)
```

### 想用外部 SearXNG

假如你已经在别处部署了 SearXNG 实例，可以在 `.env` 里：

```properties
SEARXNG_BASE_URL=https://your-searxng.example.com
```

然后把 `docker-compose.yml` 里的 `searxng` 服务块注释掉。但记得**你那个实例也要满足同样的 JSON + Limiter 要求**。

---

## 浏览器自动化

### 镜像里到底装了什么

后端镜像以 `mcr.microsoft.com/playwright:v1.52.0-noble` 为基础（Ubuntu Noble 24.04，glibc），由 `mateclaw-server/Dockerfile` 的第三阶段拉起，额外装：

- `openjdk-21-jre-headless` —— 跑 Spring Boot JAR
- `fonts-noto-cjk` —— 中文页面截图不出豆腐块
- `fonts-noto-color-emoji` —— Emoji 渲染
- `tzdata` —— 时区 `Asia/Shanghai`

Playwright 官方镜像已经把三大浏览器预装在 `/ms-playwright/`：

- `chromium-XXXX/chrome-linux/chrome` —— 主力
- `firefox-XXXX/firefox/firefox`
- `webkit-XXXX/pw_run.sh`

所有系统依赖（`libnss3` / `libgbm1` / `libasound2` / `libx11-xcb1` / `libxkbcommon` / …）随镜像装好了。**不需要 `playwright install`，也不怕 Alpine-musl 兼容性坑**。

Dockerfile 里显式设的环境变量：

```dockerfile
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
```

—— Playwright Java 启动时按这个路径找预装浏览器，**不会**再去 `$HOME/.cache/ms-playwright` 联网下载。

### BrowserLauncher 的 7 级降级

`vip.mate.tool.browser.BrowserLauncher` 按如下优先级启动浏览器，任何一级命中即止：

1. `CONFIG_CDP` —— 配了 `MATECLAW_BROWSER_CDP_URL` → 直接 attach 已运行的 Chrome
2. `CONFIG_PATH` —— 配了 `MATECLAW_BROWSER_CHROME_PATH` 或 `CHROME_PATH` env → 用指定 exe
3. `CONFIG_CHANNEL` —— 配了 `MATECLAW_BROWSER_CHANNEL=chrome|msedge` → 走 Playwright channel
4. `AUTO_CHANNEL` —— 自动试 `chrome` / `msedge` channel（Docker 镜像里这一步必然命中）
5. `AUTO_PATH` —— 扫标准安装路径（`/usr/bin/google-chrome` / `chromium-browser` / `snap/bin/chromium` / `microsoft-edge` / `brave-browser`）
6. `BUNDLED` —— Playwright bundled chromium（镜像里这一步也一定通）
7. `EXTERNAL_CDP` —— 最后兜底，自己 `fork` 系统 chrome 带 `--remote-debugging-port=0`，读 stderr 抠 DevTools URL，`connectOverCDP` 接回来（openfang 的套路）

Docker 部署下 **默认走第 4 或第 6 级**，零配置可用。如果你的用例要接外部 Chrome，配第 1 级；要用宿主机装的某个特殊 Chrome，配第 2 级。

### `/dev/shm` 必须 2 GB

`docker-compose.yml` 给 `mateclaw-server` 设了 `shm_size: 2gb`。Docker 默认给每个容器只 64 MB `/dev/shm`，Chromium 用共享内存做 GPU / 页面渲染，跑 3 个 tab 就会 SIGBUS 挂掉，表现为 Playwright `TargetClosedError: Target page, context or browser has been closed`。**不要改小这个值**。

### SSRF 防护

BrowserUseTool 在 `navigate` 前会过 `UrlSafetyChecker`，**硬阻断**以下 host：

- `localhost` / `127.0.0.1` / `::1` / `0.0.0.0`
- 169.254.169.254（AWS / GCP / Azure IMDS）、100.100.100.200（阿里云 IMDS）、192.0.0.192（Azure IMDS alt）
- 所有 link-local / private / multicast IP 段

也就是说 LLM 生成一个恶意 URL 指向云元数据端点偷凭据这条路是封死的。如果你有内网抓取需求需要放行特定地址，关 `mateclaw.browser.ssrf-check-enabled` 或改 `UrlSafetyChecker` 的白名单。**生产环境谨慎**。

### 验证浏览器通路

```sh
# 1. 启动自检（不实际启浏览器，查环境齐不齐）
curl -s http://localhost:18080/api/v1/system/browser-health | jq .
# 期望：overall: "healthy"，system.browsers 找到 chromium 路径

# 2. 在 agent 里让它调浏览器
#    browser_use(action="diagnose")  # 返回策略链 trace
#    browser_use(action="start")     # 实际启动
#    browser_use(action="open", url="https://example.com")
#    browser_use(action="screenshot")  # 返回 base64 PNG
```

---

## 第一次部署

```sh
git clone https://github.com/matevip/mateclaw.git
cd mateclaw

# 1. 必填项写到 .env
cp .env.example .env
vi .env   # 见下方必填表
```

**必填**（compose 启动会强制校验，缺项直接退出避免把默认值带进生产）：

| 变量 | 说明 |
|---|---|
| `DB_PASSWORD` | 业务库账号密码，建议 16+ 位 + 大小写 + 数字 + 符号 |
| `DB_ROOT_PASSWORD` | MySQL root 密码，**与上面不同** |

**强烈建议**（不填不会报错，启动日志里 WARN）：

| 变量 | 说明 |
|---|---|
| `JWT_SECRET` | JWT 签名密钥，`openssl rand -base64 48` 生成 |
| `MATECLAW_CORS_ALLOWED_ORIGINS` | 生产白名单，如 `https://mateclaw.example.com` |

然后起服务：

```sh
docker compose up -d --build   # 首次构建，约 3-10 分钟
docker compose logs -f mateclaw-server
```

首次启动会跑 Flyway 迁移（~5 秒）+ 应用内种子数据（~3 秒），然后绑 `0.0.0.0:18080`。

浏览器打开 `http://localhost:18080`，`admin / admin123` 登录，**立刻在「设置 → 安全」改密码**。

---

## 构建加速

### 美国 / 欧洲服务器

**默认就是最快的**：`mateclaw-server/pom.xml` 里 `<repositories>` 的优先级是 `Maven Central → Google CDN → Aliyun`，Central 直连最快。

### 中国服务器

切 Aliyun 优先：改 `mateclaw-server/Dockerfile` 的 `mvn` 命令加 `-Paliyun-first`，或者（更简单）在 `docker-compose.yml` 加一行 `build args` 传进去。

```dockerfile
# 原
RUN mvn dependency:go-offline -q
RUN mvn package -DskipTests -q

# 改
ARG MAVEN_PROFILE=
RUN mvn dependency:go-offline -q ${MAVEN_PROFILE:+-P${MAVEN_PROFILE}}
RUN mvn package -DskipTests -q ${MAVEN_PROFILE:+-P${MAVEN_PROFILE}}
```

然后：

```sh
docker compose build --build-arg MAVEN_PROFILE=aliyun-first mateclaw-server
```

Aliyun Spring 镜像和公共仓库会被推到最前，中国出口不用经美国骨干。

---

## 可选开关

全部支持在 `.env` 里通过环境变量 override，**不填即用容器内默认**：

| 变量 | 默认 | 用途 |
|---|---|---|
| `SERPER_API_KEY` | — | Google 搜索 API（付费，质量高） |
| `SEARXNG_SECRET` | 内置开发 secret | 只在把 8088 端口暴露到公网时才填 |
| `SEARXNG_BASE_URL` | `http://searxng:8080` | 想接外部 SearXNG 实例时填 |
| `MATECLAW_BROWSER_CDP_URL` | — | 接外部 Chrome sidecar（CDP 端点） |
| `MATECLAW_BROWSER_CHROME_PATH` | — | 用宿主机 Chrome 覆盖镜像内置 |
| `MATECLAW_BROWSER_CHANNEL` | — | `chrome` / `msedge` 等，强制指定 Playwright channel |

**LLM 的 API Key（DashScope / OpenAI / Anthropic / DeepSeek / Kimi / ...）不在 `.env` 里配** —— 启动后在 UI「设置 → 模型 → 添加供应商」里添加，支持热更新。容器**零 API Key 也能起来**，登录后到模型页配第一家供应商即可。

---

## 验证

起来之后按顺序跑：

```sh
# 1. 三个容器都 healthy
docker compose ps

# 2. 基础健康检查
curl -s http://localhost:18080/api/v1/system/health | jq .

# 3. 浏览器工具自检（Linux 上最容易挂的地方）
curl -s http://localhost:18080/api/v1/system/browser-health | jq .
# 期望 overall: "healthy"

# 4. SearXNG 返回 JSON（不是 HTML 错误页）
curl -s 'http://localhost:8088/search?q=hello&format=json' | head -5
```

任何一条不通再翻下一节。

---

## 常见坑

**构建阶段 `mvn dependency:go-offline` 卡死**
美国服务器拉 Aliyun 镜像慢。pom.xml 默认把 Maven Central 放最前，应该快。如果还是慢，网络不通——检查出站防火墙。

**`mateclaw-server` 启动前就 unhealthy**
`docker compose logs mateclaw-server` 看 Flyway 迁移是否成功。通常是 DB_PASSWORD 含特殊字符被 shell 吃了 —— 用双引号包住。

**浏览器工具报 "Target page closed" / SIGBUS**
`shm_size: 2gb` 没生效。`docker inspect mateclaw-server | grep ShmSize` 看实际值。老版本 Docker Engine 要升级到 24.0+。

**搜索返回 "搜索暂时不可用"**
SearXNG 容器没起来或 JSON 格式被镜像默认 settings 禁用。我们自己构建 `./docker/searxng/` 已经改好；如果用了旧的 volume 缓存要清：`docker compose down -v searxng && docker compose up -d searxng`。

**LLM 回答里出现乱码 / 豆腐块**
镜像已经装了 `fonts-noto-cjk` 和 `fonts-noto-color-emoji`，不是字体问题。检查前端浏览器 locale。

---

## 升级

```sh
git pull
docker compose build mateclaw-server   # 只重建后端
docker compose up -d mateclaw-server
```

MySQL 数据卷（`mysql_data`）不会动，Flyway 自动跑增量迁移 + 自愈 checksum 变化。**版本号写在 `mateclaw-server/pom.xml` 和 git tag**，生产环境建议钉 tag 而不是 `dev` 分支。

---

## 下一步

- [配置说明](./config) —— 所有环境变量和运行时开关
- [Doctor 健康检查](./doctor) —— UI 里自带的启动体检
- [安全与审批](./security) —— 生产部署前的加固清单
