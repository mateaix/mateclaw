---
title: DeepSeek Harness 集成
description: 配置、验证、升级和恢复受管理的 DeepSeek Harness SDK 运行时。
---

# DeepSeek Harness 集成

DSH 是数字员工运行时。MateClaw 启动官方 `dsh --profile sdk` 进程，把 JSON-RPC 会话事件投影到聊天界面。先在 **设置 → 模型** 配置 DeepSeek 提供方，再为有工作空间的数字员工选择 **DSH / DeepSeek Harness**。

## 环境与安装

受管理安装要求 npm 和 npm 仓库网络访问。**0.2.2-rc.1 要求 Node.js 24.9+（24.x）**；保留的旧版本接受 Node.js 22.x 或 24.x。安装器接受 macOS arm64 与 Linux x64 候选环境。实际 npm 安装、Java 适配器握手和真实模型任务已在 macOS arm64、Node 22.20.0、npm 11.6.2 上通过；Linux x64 仍待实机验证，Windows/WSL 未验证。

在 DSH 管理控制台安装固定目标 **`0.2.0-rc.1`**，另一个受审查版本为 **`0.1.7-rc.2`**。两者均为预发布版本。安装使用仓库内依赖锁文件，在独立候选目录运行 `npm ci`，不会追踪移动的 `latest` 标签，也不会修改全局 npm 包。

依次运行 **校验**、**测试连接**、**测试任务**：分别检查配置、执行 SDK `initialize` 握手、调用真实模型并要求精确回答 `OK`。握手成功不等于模型可用。协议中的服务版本 `0.0.1` 不是 npm 包版本；受管理安装单独记录固定包的来源。外部可执行文件需要另行验证版本。

Docker 运行镜像锁定 Node.js 24.22.2，并包含 Linux 沙箱执行所需的 bubblewrap。安装受管理 DSH 包之前请重建或拉取更新镜像；仅替换应用 JAR 不会更新容器依赖。宿主机安装需自行准备运行时依赖。

## SDK 配置

可执行文件必须是 `node_modules/.bin/dsh` 的绝对路径，profile 必须为 `sdk`，工作目录必须存在，并设置专用 home 根目录。可选 patch 列表是按顺序传入的绝对文件路径 JSON 数组。MateClaw 按工作空间/员工分配独立 `DSH_HOME`，直接传递参数，不经过 shell。

| 配置键 | 用途 |
| --- | --- |
| `dsh.executable_path` | SDK CLI 可执行文件绝对路径 |
| `dsh.profile` | 固定为 `sdk` |
| `dsh.working_directory` | 默认工作目录 |
| `dsh.home_root` | 隔离运行时 home 的根目录 |
| `dsh.patch_paths` | 有序 patch 路径 JSON 数组，例如 `[]` |

旧 `dsh-jsonrpc-agent`/Cordis 入口配置必须迁移。清空 `dsh.cordis_config_path`，移除旧 `DSH_CORDIS_CONFIG` 环境变量，改用 SDK CLI。可执行文件字段不能包含 shell 命令或附加参数。`DSH_JSONRPC_AGENT` 仍作为兼容环境变量名接受可执行文件路径，并不表示继续使用旧二进制。

凭证保存在模型提供方或受保护的运行时配置中，由 MateClaw 注入子进程环境。对于 DeepSeek 官方域名，适配器将普通 API 根地址映射到这些包要求的 Messages API `/anthropic` 路径；其他自定义地址仍须提供兼容的 Messages API。

MateClaw 通过 `initialize.maxTokens` 显式设置输出上限：使用模型配置的正数值，缺省为 4096，并限制为已知上下文窗口的一半。这不替代 DSH 自身的上下文管理。

## 升级与回滚

受管理升级先准备独立包版本，阻止新 DSH 回合进入，最多等待 60 秒让现有回合结束；随后复制运行时 home 和配置的 patch 文件，在各受影响 home 中执行候选握手与新模型任务，通过后原子切换可执行文件、配置及 home 所属代次。原 home 和旧代次保留；激活前失败继续使用旧选择。原生运行时不受 DSH 准入门控制。

home 复制拒绝符号链接与特殊文件，不会跟随链接。包含这些文件的自定义 home 需要显式迁移。代次记录和包目录保留，但活动 home 在正常运行时仍会变化。

只有已完成操作的候选代次仍处于活动状态时，才能对该操作回滚。回滚复制并检查保留的旧 home/配置后再切换，不会合并升级后产生的会话，也不会逆向修改数据库。验收完成前保留旧代次和备份。

候选检查创建新会话，不能证明每个历史 DSH 会话均可重新打开或迁移。**真实持久化 V3 → V4 历史迁移尚未验证。**

DSH 使用 profile 管理的工具，不接入 MateClaw 宿主审批；启用成员文件隔离时禁止运行此运行时。回滚不会撤销工具对工作目录文件的写入，也不会删除 MateClaw 聊天记录。

## 会话与诊断

每个 MateClaw 回合使用新的 SDK 会话 ID，并回放有界的已完成用户/助手文本历史；这不是恢复 DSH 工具状态。不要用已存在的持久化 SDK ID 创建新会话。是否完成由 SDK 终止事件决定，不能只依赖入队回执或 idle 状态。

管理 API 前缀为 `/api/v1/admin/dsh`：`GET /status`、`POST /verify`、`/test-connection`、`/test-task`、`/upgrades`，以及 `GET /upgrades/{id}`。升级请求包含精确 `targetVersion`、当前 `expectedRevision`、`idempotencyKey`；回滚接口为 `POST /upgrades/{id}/rollback`，携带版本修订号与幂等键。通过操作记录查询进度。日志及状态不得输出凭证。

## 孤儿运行时恢复

`dsh.orphaned_runtime_requires_recovery` 表示某个租约所属 JVM 已退出。系统继续拒绝准入，因为遗留 DSH 进程或工具子孙进程仍可能写入 home。

1. 停止 DSH 流量，定位受管理安装根目录下 `leases/` 中的租约；默认根目录为 `~/.mateclaw/runtimes/deepseek-harness`。
2. 确认记录的 JVM 已退出，识别并停止其孤儿 DSH 进程及所有工具子孙进程，确认受影响 home 已无写入进程。
3. 备份状态后，仅删除已确认过期的租约。不要删除活动租约或直接清空租约目录。
4. 检查持久化升级操作与活动代次，重新验证后再恢复任务。

仅重启后端不能完成孤儿进程清理。不要为绕过错误直接删除租约。

## 0.2.2-rc.1 候选（2026-11-05）

目录新增精确的 `0.2.2-rc.1`，保留全部已审查旧版本。默认仍为 `0.2.0-rc.1`，新增候选不自动激活。安装前确保后端进程 PATH 优先使用 Node24。旧版本仍可在 Node22 / Node24 下安装，旧 generation 保留用于回滚。

受管理安装返回 `bin/dsh` 启动器，在管理员 patch 之前加载内置 patch，显式恢复 `fs-observation-policy`，并使用 MateClaw 的模型上下文与输出容量。原生 SDK 文件工具保留先读后写和外部修改检查；Bash 使用自己的沙箱，不受文件工具观察策略覆盖。管理员显式 patch 仍可覆盖这些默认值。直接调用 `node_modules/.bin/dsh` 会跳过这些设置，应使用安装返回的启动器路径；Docker 镜像使用相同 patch。外部安装需自行配置等效策略。

最终回答在关联的 `turn/end` 后发送，不再等待 idle 和 SDK 退出，之后发生 EOF 也不会重复追加。SDK 未提供实时文本增量通知，因此这属于结算消息提前交付，不是逐 token 流式输出。工具回执保留成功状态与错误码，区分 PTC 的 `ABORTED` 与普通失败；结算用量独立提供缓存读写计数。宿主问答、审批桥接仍未开放，不声明新增 SDK capabilities。

每轮仍创建独立进程与会话，不能据上游前缀缓存优化声称跨轮节省；显式上下文容量避免静默继承较大的新版默认值。精确验证范围见仓库 `docs/verification/dsh-022-2026-11-05.md`。

## 0.2.1-rc.2 演进候选（2026-10-20）

版本目录新增精确的 `0.2.1-rc.2` 可选候选，保留 `0.2.0-rc.1` 和 `0.1.7-rc.2`。默认安装目标仍为 `0.2.0-rc.1`，增加候选不会切换当前 active generation。三个版本均为预发布。管理页从服务端读取版本目录，保留已安装且仍在目录内的选择，不自动选中新版本。

macOS arm64 / Node 22.20.0 / npm 11.6.2 上的候选检查覆盖干净 `npm ci`、真实 SDK initialize/shutdown、DeepSeek V4 Flash 短回答（`SDK_OK`）和一次真实 Bash 工具任务（`printf DSH_TOOL_OK`）。去敏的回答/工具记录已进入 Java 回合适配器回归。后续 macOS 验证还通过了真实取消/异常退出恢复、中断后文件检查点核验，以及 20 轮独立进程测试（501 秒）。这不代表此候选已完成多小时持续执行、持久历史迁移、Linux/WSL 或生产回滚验收。激活前仍需执行已有受管理升级检查，并保留旧 generation。

适配器识别候选的任务注册/结算和工作目录元数据事件，不将其视为完成。显式设置 `DSH_AGENTS_HOME=<员工 generation home>/agents`，避免隐式读取宿主用户的 `~/.agents`；指令根目录为符号链接时拒绝启动。管理员 patch 文件仍可覆盖 SDK 配置，属于受信任输入。这不是 OS 沙箱，DSH 仍未接入 MateClaw 宿主工具审批桥。

上游新增模型/搜索、极简 profile 的图片与压缩、交互提问及工作目录 API，需要分别适配；更新 npm 包不会自动在 MateClaw 中开放它们，也不会将 DSH 上下文管理合并到 Native 提示词预算，或恢复历史工具状态。现有文本历史回放和输出上限继续保留。

## 取消与工作目录检查点（2026-10-20）

真实 SDK/模型测试在 Bash sleep 执行期间发起取消，得到一次 CANCELLED；强制终止 SDK 根进程则使流失败。两个实测场景中记录到的 SDK/工具 PID 均退出，新建应用连接也可完成任务。另一次中断写入测试只核验已提交的检查点，没有重复写入。20 轮独立进程逐轮读取、追加、等待、回读台账，20 份精确文件快照全部通过，历时 501 秒。这是有界的分钟级验证，不是多小时运行保证。

传输层在根进程存活时保留观察到的后代 ProcessHandle，根崩溃后仍可据此清理。该机制是 best effort：在观察间隙内出生并被重新挂接、或提前脱离进程树的后代可能漏捕。独立 home 不等于 OS containment，更强的进程监督仍是待完成验收；候选状态和严格成员隔离限制保持不变。

文件检查点恢复需要先核验已有产物，再决定是否重做写入；它不会恢复 SDK 工具状态、自动重放结果不确定的副作用，也不能证明外部系统的 exactly-once。Goal 验收、调用预算、审批和应用权限保持原状，其回归与这些直接运行时探针分别记录。

## 可选 Docker 进程监督（实验性）

默认仍为 `native`。原生后代进程观察无法保证捕获观察间隙内 fork、创建独立 session 并重新挂靠的进程。可选 `docker` 模式在 SDK 启动前建立独立 Linux PID 命名空间，取消、SDK 退出或 Docker attach 客户端丢失时，仅删除本次记录的完整容器 ID。确认清理后才发布完成或 CANCELLED；删除失败按错误处理，会话的运行代际租约保留到显式重试取消且确认删除。不会自动回退原生执行。

在仓库根目录使用内置可信锁构建独立镜像：

```bash
docker build -f mateclaw-server/src/main/resources/dsh/container/Dockerfile \
  --build-arg DSH_VERSION=0.2.2-rc.1 \
  --build-arg DSH_LOCK_SHA256=ee63227735abbbbceb9c2f5442bdbdc286b8fdd9d82b15396f3e6f749ffbffe5 \
  -t mateclaw-dsh-local:0.2.2-rc.1 mateclaw-server/src/main/resources/dsh
docker image inspect --format '{{.Id}}' mateclaw-dsh-local:0.2.2-rc.1
```

Dockerfile 通过摘要固定 Node 24.22.2。先审查基础镜像、锁文件、平台原生依赖及构建产物，再配置返回的完整 `sha256:…` **镜像 ID**，不能填写可变标签。重启后端使配置生效：

```bash
export MATECLAW_DSH_CONTAINMENT_MODE=docker
export MATECLAW_DSH_DOCKER_EXECUTABLE=docker
export MATECLAW_DSH_DOCKER_IMAGES_JSON='{"0.2.2-rc.1":"sha256:<image inspect 返回的 64 位十六进制值>"}'
```

每个需要运行的 SDK 目录版本都要提供独立审查过的镜像，包括候选检查与回滚版本。宿主机托管可执行文件用于识别可信 SDK 版本，容器运行对应镜像中的 SDK，版本及锁摘要标签必须匹配。运行时不会拉取镜像或切换活动代际。`/status` 展示配置的监督模式，不代表兼容性认证。

运行要求：可信的本地 Docker daemon、Unix socket、匹配架构的 Linux 镜像，以及 Java Unix 身份接口支持的宿主机 UID/GID。拒绝远程 Docker endpoint 和 Windows 宿主机；整个清理周期固定使用启动时的 daemon endpoint。容器移除全部 capabilities，使用 `no-new-privileges`、只读镜像文件系统和 PID 数量上限。独立 `/tmp` 允许执行，因为 SDK 原生加载器需要把动态库复制到该目录再加载。仅挂载明确的工作区、员工隔离 home 和只读管理员 patch。Docker 管理权限等同于高权限宿主机访问，Docker 管理员可以读取以环境变量传入的凭据。容器内不挂 Docker socket，不使用宿主机 PID 命名空间或 privileged 模式。

**平台限制：** 当前实测 macOS arm64 Docker Desktop 环境可执行 SDK 模型调用，但 SDK 原生 Landlock 拒绝读取宿主机共享工作区，报 `EACCES`。该环境暂不能用于工作区任务；不会关闭沙箱或自动提权绕过。Linux 原生 bind mount 和其他 Desktop 文件共享后端仍需独立完成读写、工具验收，握手成功不能替代验收。进程清理测试使用容器 `/tmp`，与宿主机工作区兼容性分开验证。

JVM 整体退出、daemon 不可用仍是明确的恢复边界。容器带有 `vip.mateclaw.dsh.owner=<随机 UUID>` 和 `vip.mateclaw.dsh.jvm=<创建 JVM PID>` 标签。确认记录的 JVM 已退出后，在原 daemon 上核对并删除其对应容器，再执行上文的失效租约恢复流程。daemon 不可用时不能确认清理；不得仅为恢复接入删除租约或重放结果未知的工具操作。成员隔离拒绝规则、应用权限、Goal、受限文本历史及输出预算保持不变。进程监督不会新增宿主机工具审批桥，也不代表完整安全沙箱认证。

### 原生共享工作区排查（2026-10-21）

当前 Desktop bind mount 实际是 `fakeowner`（文件系统 magic 为 `0x6a656a63`），不是原生 Linux 文件系统。使用 SDK 原生 Landlock launcher，即使显式授予目录或单文件权限仍无法读取；未受该规则约束的读取正常。一次性 Docker ext4 卷在相同沙箱探针下读写通过，且直接越界写入与符号链接越界都被拒绝。这定位了文件系统边界，但不代表 Desktop 配置已修复。

已选择优先验证 Docker Desktop 原生 [Synchronized file shares](https://docs.docker.com/desktop/features/synchronized-file-sharing/)，使用 ext4 缓存和双向同步。它要求登录具备 Pro、Team 或 Business 权益的账号。在 Settings → Resources → File sharing 中先对临时测试目录 Create share，等待 Watching 状态。不要将 bind consistency 设为 `consistent`，该值会绕过同步共享。正式工作区启用前必须验证真实沙箱行为与宿主文件可见性，不能仅根据文件系统类型或握手成功推断兼容。目前本机仍等待 Docker Desktop 登录与订阅权益，同步共享尚未验收。

显式 opt-in 的 `DshDockerSharedWorkspaceAcceptanceTest` 使用 `MATECLAW_DSH_ACCEPTANCE_DOCKER_IMAGE` 和 `MATECLAW_DSH_ACCEPTANCE_SHARED_WORKSPACE`（已存在的测试共享目录）。它创建独立临时子目录，验证两轮宿主→沙箱→宿主文件内容，并要求直接越界和符号链接越界写入报 `EACCES`。只配置镜像变量时可以运行独立 ext4 卷对照。测试仅清理自行创建的容器、卷和目录。未引入应用层工作区写回、关闭沙箱、自动提权或运行时依赖升级。
