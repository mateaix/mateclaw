# Docker 部署

桌面端之外的唯一推荐生产部署方式。一条 `docker compose up -d` 起三个容器：PostgreSQL、SearXNG、mateclaw-server。

> **⚠️ 从 MySQL 栈升级**：Docker 栈已从 MySQL 切换到 PostgreSQL 16。老部署直接 `git pull` 后 `up -d` 会启动一个**全新的空 PostgreSQL 卷**——旧 `mysql_data` 卷不会被读取（数据不丢，但新栈看不到）。要携带数据请先在旧栈上 `mysqldump`，再用 pgloader 等工具导入 PostgreSQL；或钉在切换前的 tag 上继续用 MySQL（`mysql` Spring profile 仍然支持）。

这一页覆盖**要求、步骤、验证、常见坑**。配置变量明细请看 [配置说明](./config)。

---

## 前置要求

| 项 | 最低 | 推荐 | 备注 |
|---|---|---|---|
| Docker Engine | 24.0+ | 最新稳定 | `docker --version` 确认 |
| Docker Compose | v2.20+ | v2.30+ | `docker compose version`（注意是 `compose` 不是 `compose`） |
| 宿主 RAM | 4 GB | 8 GB+ | 浏览器工具启动时 Chromium 会吃 1-2 GB |
| 磁盘空间 | 6 GB | 20 GB+ | 镜像约 2 GB + PostgreSQL 数据 + 工作空间文件 |
| /dev/shm | 默认 | compose 已自动设 2 GB | Chromium 用共享内存做渲染，默认 64 MB 会 SIGBUS |
| 网络 | 出公网 | — | 拉镜像 + 调 LLM API |

**不需要**：宿主装 Java / Node / Maven / Chrome / Python —— 全部在镜像里。

---

## 三个容器

| 服务 | 镜像 | 作用 | 暴露端口 |
|---|---|---|---|
| `postgres` | `postgres:16` | 业务数据存储 | 仅容器网络内（默认不映射宿主端口） |
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

后端镜像以 `mcr.microsoft.com/playwright:v1.62.0-noble` 为基础（Ubuntu Noble 24.04，glibc），由 `mateclaw-server/Dockerfile` 的第三阶段拉起，额外装：

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
git clone https://github.com/mateaix/mateclaw.git
cd mateclaw

# 1. 必填项写到 .env
cp .env.example .env
vi .env   # 见下方必填表
```

**必填**（compose 启动会强制校验，缺项直接退出避免把默认值带进生产）：

| 变量 | 说明 |
|---|---|
| `DB_PASSWORD` | 应用账号密码（最小权限角色，仅拥有 `mateclaw` schema），建议 16+ 位 + 大小写 + 数字 + 符号 |
| `DB_ADMIN_PASSWORD` | PostgreSQL 引导超级账号密码，仅用于首次初始化与运维，**与上面不同** |

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
SearXNG 容器没起来或 JSON 格式被镜像默认 settings 禁用。我们自己构建 `./docker/searxng/` 已经改好；先重建并重新创建搜索容器：`docker compose up -d --build --force-recreate searxng`。

**LLM 回答里出现乱码 / 豆腐块**
镜像已经装了 `fonts-noto-cjk` 和 `fonts-noto-color-emoji`，不是字体问题。检查前端浏览器 locale。

---

## 升级

1. 暂停新请求和 Goal/Cron 调度，等待工具结束，记录当前版本、Compose 配置和镜像。
2. 停止应用写入，备份数据库、`server_data`、工作空间、插件和 `.env`；保留 JWT 密钥，并在副本上演练恢复与迁移。备份必须位于待升级卷之外。
3. 选择已发布的目标 tag，阅读对应[更新与升级说明](./changelog#升级与验证)，再构建和启动。下面以 3.1.0 为例：

```sh
docker compose stop mateclaw-server
git fetch origin --tags
git checkout v3.1.0
docker compose build mateclaw-server
docker compose up -d mateclaw-server
docker compose logs --tail=100 mateclaw-server
```

`postgres_data` 在重建时保留，Flyway 校验通过后执行增量迁移。**校验和变化默认阻止启动，不会自动修复**；按[Flyway 故障处理](./config)检查历史与实际 schema，确认后才可单次启用修复，处理完恢复默认。版本由根 `pom.xml` 的 `<revision>` 和 git tag 确定。

升级后验证登录、模型调用、工具执行、历史消息、取消后续聊、搜索及所用渠道，再恢复调度。回滚需要旧版本及配套的升级前数据库和文件备份；只切换旧镜像不能逆转迁移，也不能撤销外部工具效果。已有 MySQL 部署不能直接切换本 PostgreSQL Compose 期待自动迁移数据。

---

## 3.1.0 离线升级：已存在的受管理部署

这条流程用于已经运行 3.0.0、PostgreSQL 16、linux/amd64、Compose 项目 `deploy`，且数据库、应用数据、技能运行时与缓存均已持久挂载的部署。它只替换后端（包含管理界面和 WebChat），保留官网／文档、搜索、代理和数据库容器；后端变更后重载代理。

内部运维脚本与生产回执不包含在公开源码仓库中。以下命令适用于**维护方提供并经审核的 3.1.0 离线包**，不是在普通源码目录中直接执行的命令。原 3.0.0 全栈脚本固定版本并假定首次创建运行时目录，不能直接套用于本流程。不同版本、架构、数据库或挂载布局须另行审核。

### 离线包与构建核对

在联网构建机完成 UI、WebChat 和 Java 21 后端构建、相应回归测试，再组装 linux/amd64 镜像。服务器只执行镜像导入，不构建、不拉取镜像。

| 文件 | 用途 |
| --- | --- |
| `manifest.json` | 3.1.0、来源 commit、架构及五个服务的镜像 tag／ID；仅后端替换为新镜像 |
| `images.tar.gz` | 后端镜像；其余服务的镜像必须已经存在并与清单一致 |
| `backend_upgrade.py` / `original_upgrade.py` | 后端专用入口与其依赖快照；不直接运行依赖快照的 CLI |
| `acceptance.py` | 特权运维验收：升级前基线、业务验证和恢复调度后的复检 |
| `migration/run.sh`、Java 源文件与 `.class` | 独立 Flyway 验证器，不加载 Spring 应用 |
| `SHA256SUMS` | 覆盖镜像、清单、运维脚本和迁移入口的校验文件 |

核对 JAR 版本 3.1.0、内嵌 UI／WebChat，以及 Boot 4.1.2、Spring AI 2.0.2、AgentScope Core 2.0.5、Playwright 1.62.0。保留经过审核的运行时和现有 DSH 配置；本次保留 Node 22.23.4，没有自动切换可选 Node 24／DSH 候选。包中不应包含生产 `.env`、密钥、测试令牌、数据库或用户文件。

### 维护流程

先审核包内固定版本、部署目录、状态路径和镜像清单；这不是通用版本升级器。维护前暂停新请求，等待工具结束并检查活动 Goal 租约。包内 Goal 租约检查不能代替完整业务流量管控。

```bash
# 服务器上的全新、已审核离线包目录；需要已有 Docker／数据库管理权限
sha256sum -c SHA256SUMS
python3 backend_upgrade.py preflight
python3 acceptance.py before
python3 backend_upgrade.py rehearse
python3 backend_upgrade.py apply
```

1. **预检与基线**：检查架构、镜像、卷、home／cache 挂载、现有 JWT 密钥、image-only Compose 和空间。按实际备份大小预留空间；脚本最低空间门槛不是容量保证。
2. **第一次演练**：在线 `pg_dump -Fc`，恢复到内部隔离网络中的独立 PostgreSQL；只运行 Flyway 验证／迁移，不启动应用、任务或渠道。禁止自动 repair／baseline／clean。
3. **停服备份**：停止后端写入，保存数据库、应用数据、workspace、技能／插件、缓存、环境文件、部署及代理配置／证书、旧镜像信息。生成并复核逐文件校验清单。
4. **第二次演练**：用刚取得的停服数据库备份再次恢复与验证，确保回滚材料对应切换时点。V203 是本次目标；3.0.0 已到 V203 时仍须校验历史，不能以“无新增迁移”跳过验证。
5. **验证启动**：暂存原启用 Cron ID，暂缓 Cron 和 Goal 自动续跑，保留 JVM 时区／编码参数，使用 `--no-build --pull never --no-deps` 只重建后端。代理仅重载。

应用中断覆盖文件复制、逐文件校验、副本恢复和启动时间。本次约 6.7 GiB 的技能／运行时目录带来约 35 万个备份条目；不能按数据库 dump 的体积估计维护窗口。不要在备份过程中重复启动升级或删除状态。

`apply` 成功表示 `validationReady=true`，仍处于 `applying`，并未完成最终发布。保持调度暂缓，继续：

```bash
python3 acceptance.py validation
# 检查 JSON 每一项 passed，并审核模型／工具和历史证据后再继续
python3 backend_upgrade.py resume
python3 acceptance.py final
python3 backend_upgrade.py verify
```

验收脚本使用现有管理员身份和 JWT 签名密钥生成短时测试令牌，不改用户密码或轮换密钥；只能在已有管理权限的受限环境使用。它会调用已配置模型、创建部署测试会话，使用常量文本测试真实回答、Shell 的 `printf MATECLAW_TOOL_OK_31`、持久化和历史追问。会产生调用费用、审计记录及测试会话，不需要向业务 IM／邮件发送消息。遇到工具审批或策略限制时不绕过，记录未完成项并清理该测试运行。

脚本正常退出不等于所有业务检查通过；逐项检查报告。令牌过期也不等于密钥变化。验收后删除临时令牌；报告和备份不得包含公开可用的凭据。`resume` 恢复原调度配置并重新启动后端，成功后才进入 `live` / `schedulingRestored=true`。最终复检包含任务恢复数量、health 和 readiness 均为 UP、环境参数保留、公开页面和 JS/CSS 正确加载。

### 失败处理与回滚

PostgreSQL 初始化时可能先开放临时 Unix socket，不能据此判断正式服务已就绪；恢复副本应等待 `pg_isready -h 127.0.0.1`。本次首次副本恢复曾失败，补充私有日志后复测通过；没有足够证据把那次失败归因于就绪竞态。所有成功迁移验证均未使用 repair。

切换前验证失败应保持旧后端，先查日志；切换后内部检查失败时专用入口尝试回滚，仍须确认回滚结果。业务验收失败不要恢复调度，审核后执行：

```bash
# 同一个经校验的版本化包目录
python3 backend_upgrade.py rollback
```

回滚要求旧镜像和完整停服备份，会丢弃升级后的新增写入。恢复配套数据库、文件、环境与旧后端，不能只换旧镜像；也不能撤销外部工具已产生的效果。保留旧镜像和备份，不执行 `down -v`、prune、清空检查点或强制 Flyway repair。进程中断／回滚失败时先检查状态和日志，不假定已经恢复，也不删除 live 状态重跑。

### 3.1.0 历史验收结果与边界

验收记录时间：2026-11-23 04:34:33 UTC（纽约 2026-11-22 23:34:33）。这是该次受控环境的历史记录，不代表所有平台或当前线上状态。

| 检查 | 结果 |
| --- | --- |
| 完整备份 | 352,110 个清单条目，完成逐文件校验，回滚材料保留 |
| 数据库 | 两次成功副本恢复及 Flyway V203 校验；新增迁移 0，无 repair |
| 业务验收 | 17/17：鉴权、升级前测试令牌、配置接口、真实聊天、持久化、成功 Shell 工具事件及历史追问 |
| 调度恢复后 | 14/14 接口复检；原 33 个 Cron 与 Goal 调度恢复 |
| 状态与配置 | health／readiness 均 UP；环境参数保留；其他四个服务未重建 |
| 公开 UI | 页面与构建产物一致，5 个 JS/CSS 资源通过；浏览器登录页正常 |
| 配套官网 | 7 个页面与包一致；中英文公司署名与公司链接经过验证 |

测试中，模型发现／连接探测失败不一定代表生成失败；已配置可用模型的真实生成、工具执行和追问通过。部分供应商已有凭证错误仍存在，未因本次升级修复，不能宣称所有模型和外部渠道均已通过验收。消息收发到所有 IM、所有可选插件及各平台安装包不在这次检查覆盖范围内。

---

## 下一步

- [配置说明](./config) —— 所有环境变量和运行时开关
- [Doctor 健康检查](./doctor) —— UI 里自带的启动体检
- [安全与审批](./security) —— 生产部署前的加固清单
