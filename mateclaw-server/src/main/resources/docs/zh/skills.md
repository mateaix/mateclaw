# 技能系统

**一个技能是一个"用句子思考"的工具。**

工具是原子的——读一个文件、发一个 HTTP 请求、跑一个命令。技能是**组合**——"调研这个话题然后写一份简报"、"审这段代码并给出评论"、"把我的 git log 变成 standup 汇报"。一个技能是一份 `SKILL.md` 文件，把指令、参数、prompt 模板、可选脚本和需要的工具列表组合在一起。

如果工具是手，那技能就是**菜谱**。

---

## 五种技能

| 类型 | 来源 | 维护者 |
|------|------|--------|
| **`builtin`** | 跟着 MateClaw 一起发布 | 核心团队 |
| **`custom`** | 你通过 UI、API 或直接往工作空间扔文件创建 | 你自己 |
| **`dynamic`** | Agent 在工作中自动合成 | Agent + 你的审批 |
| **`mcp`** | 由 MCP 服务暴露的工具支撑（同名技能会被 `custom` 真技能覆盖） | MCP 服务作者 |
| **`acp`** | 桥接到外部 Agent 客户端协议端点（Claude Code、Codex 等） | 上游 Agent 服务 |

全都走同一条运行时管道。唯一的区别是来源。

---

## SKILL.md 协议

每个技能是一份带 YAML frontmatter 的 Markdown 文件。**frontmatter 是契约，正文是 prompt。**

```markdown
---
name: web-researcher
title: 网页调研员
description: 搜索网页并对给定话题做总结
version: 1.0.0
type: custom
author: your-name
tools:
  - WebSearchTool
  - ReadFileTool
tags:
  - research
  - search
parameters:
  - name: topic
    type: string
    required: true
    description: 调研的话题
  - name: depth
    type: string
    required: false
    default: brief
    description: 详细程度（brief, detailed, comprehensive）
---

# 网页调研员

你是一个网页调研助手。给你一个话题时，你应该：

1. 用 WebSearchTool 搜索关于 {{topic}} 的相关信息
2. 评估信息源的可信度
3. 把发现整理成 {{depth}} 级别的总结
4. 在回答里包含来源 URL

## 输出格式

把你的发现按这个格式呈现：
- **总结**：2–3 句话概述
- **关键事实**：要点列表
- **来源**：带编号的 URL 列表
```

注意两件事。第一，正文是一份 prompt——不是对 prompt 的**描述**。它是技能在运行时会对 Agent 说的话。第二，`tools:` 列表是一份契约：运行时保证这些工具在技能运行时可用。

### Frontmatter 字段

| 字段 | 必填 | 用途 |
|------|------|------|
| `name` | ✅ | 唯一标识符（kebab-case） |
| `title` | ✅ | 人类可读的显示名 |
| `description` | ✅ | 一句话总结 |
| `version` | ✅ | 语义化版本 |
| `type` | ✅ | `builtin`、`custom`、`mcp` |
| `author` | — | 作者 |
| `tools` | — | 技能需要的工具名列表 |
| `tags` | — | 分类标签 |
| `parameters` | — | 类型化的输入参数 |

### 参数 schema

| 字段 | 必填 | 用途 |
|------|------|------|
| `name` | ✅ | 参数名（`{{name}}` 插值时用） |
| `type` | ✅ | `string`、`number`、`boolean`、`array` |
| `required` | — | 是否必须提供（默认 false） |
| `default` | — | 调用者省略时的默认值 |
| `description` | ✅ | 这个参数控制什么 |

### 脚本的类型化包装工具（v1.4 新增）

SKILL.md 可以声明一个 `scripts:` 块，把每个脚本入口变成**独立的、带类型化 JSON Schema 的命名工具**。Agent 看到的不再是一个泛用的 `runSkillScript`，而是一个个 `skill_<skill>_<entrypoint>` 工具，直接填写 schema 描述的参数。

```yaml
scripts:
  - id: summarize
    path: scripts/dispatch.py
    fixedArgs: ["summarize"]        # 每次调用前原样拼在最前
    parameters:
      - name: url
        type: string
        required: true
  - id: translate
    path: scripts/dispatch.py
    fixedArgs: ["translate"]
    parameters:
      - name: lang
        type: string
        required: true
```

- **每个入口一个类型化工具**——模型拿到的是类型化参数，不是一串自由格式的 arg。
- **`fixedArgs` 让一个 dispatcher 脚本支撑多个入口**——上面两条都调 `dispatch.py`，靠固定的首参区分，不用每个命令一个文件。
- **包装工具随技能生命周期注册/注销**——技能上线时出现，禁用或归档时消失。路径穿越被拦死：只能触达技能自己 `scripts/` 目录下的脚本。纯数据库技能（没有目录）不暴露任何包装工具。

---

## 运行时管道

```
1. RESOLVE     在 mate_skill 里按名字查技能
       │
       ▼
2. VALIDATE    检查必填参数都提供了
       │
       ▼
3. RENDER      替换 SKILL.md 正文里的 {{parameter}} 占位符
       │
       ▼
4. INJECT      把渲染后的指令追加到 Agent 的 system prompt
       │
       ▼
5. BIND TOOLS  校验技能要求的工具都可用；缺任何一个就早失败
       │
       ▼
6. EXECUTE     Agent 用增强后的 prompt 和绑定的工具处理任务
```

技能默认**不跑脚本**——它在调用期间**塑形 Agent 的行为**。例外是自带脚本的技能——`SkillScriptTool` 可以执行技能捆绑的脚本文件，走 Tool Guard。

### 模板渲染

技能正文支持 `{{parameterName}}` 占位符。参数 `{topic: "量子计算", depth: "detailed"}` 下：

```markdown
用 {{depth}} 级别的详细程度调研话题 "{{topic}}"。
```

…渲染成：

```markdown
用 detailed 级别的详细程度调研话题 "量子计算"。
```

缺失参数回退到默认值。未知占位符原样保留。

---

## 技能存储

数据库是真相源，文件系统是物化缓存。这条规则对 **SKILL.md** 一直成立，**v1.3 起对 scripts/ 和 references/ 也成立**。

### 数据库：`mate_skill` + `mate_skill_file`

`mate_skill`——技能身份与正文：

| 列 | 用途 |
|----|------|
| `id` | 主键 |
| `name` | 唯一名 |
| `title` | 显示标题 |
| `description` | 一句话总结 |
| `type` | `builtin` / `custom` / `mcp` |
| `content` | 完整的 `SKILL.md` 内容 |
| `version` | 语义化版本 |
| `enabled` | 开关 |
| `tags` | JSON 数组 |
| `create_time` / `update_time` | 时间戳 |

`mate_skill_file`（v1.3 新表，迁移 `V112`）——bundle 文件的**权威副本**：

| 列 | 用途 |
|----|------|
| `id` | 主键 |
| `skill_id` | 外键到 `mate_skill` |
| `file_path` | `scripts/run.py` 或 `references/cfg.md` 这种相对路径 |
| `content` | UTF-8 文本（单文件 ≤1 MB，bundle ≤50 MB） |
| `content_size` | 字节数（不用拉 blob 就能列） |
| `sha256` | 内容指纹，给同步器做幂等 diff |

### 文件系统：技能工作空间

```
~/.mateclaw/skills/
├── translate/
│   ├── SKILL.md               # 技能定义
│   ├── references/            # 参考资料
│   └── scripts/               # 可选的可执行脚本
├── code-review/
│   ├── SKILL.md
│   └── ...
└── .archived/                 # 归档的旧版本
    └── translate-20260401-143000/
```

把它想成"**Maven 本地仓库，但用来存技能**"——区别是"本地仓库"现在能从数据库 hydrate 出来。

### 启动时自动同步

启动时跑两遍同步，保证每个节点拿到的都是最新的：

1. `SkillWorkspaceBootstrapRunner` → `BundledSkillSyncer` 扫描 classpath 的 `skills/` 目录，把**捆绑技能**同步到工作空间根。**只在目标目录不存在时同步**，不会覆盖本地修改。
2. `SkillFileSyncer` 比对 `mate_skill_file`（DB）与本地工作空间（FS），按 `sha256` 增量物化缺失或过期的文件。

**多实例部署的意义**：一个节点上传 zip，DB row 与 file rows 写入；其他节点重启或调一次 `POST /api/v1/skills/{id}/sync-files` 就能拿到完整 bundle，不用 NFS、不用脚本拷贝、桌面端跨机也能接力。

> 升级路径：v1.3 之前的安装在 FS 有文件但 DB 没 row。`SkillFileSyncer` 第一次启动会**从磁盘回填**到 `mate_skill_file`，之后两边保持同步。

### 鲁棒的 zip 安装

第三方打包者千奇百怪——有人把 `setup.sh` 直接放 zip 根，有人 `scripts/` 排在 `SKILL.md` 之前。`ZipSkillFetcher` v1.3 起：

- **两遍扫描**——先把所有条目缓存（受 50 MB 上限保护），定位 `SKILL.md` 算出 wrapper 前缀，再分类。**条目顺序不再影响结果**。
- **根目录扩展名兜底**——SKILL.md 同级的非约定文件按扩展名归类：`.sh / .py / .js / .rb / ...` → `scripts/`，`.md / .json / .yaml / .csv / ...` → `references/`，未识别扩展名落 `WARN` 日志。
- **写后裁剪 + 空 bundle 守卫**——重装时**先写新文件再裁剪不在新 bundle 里的旧文件**。如果新 bundle 某个桶（`scripts/` 或 `references/`）一个条目都没有，**保留磁盘上的旧文件**——一个解析失败的损坏 zip 不会再把你的 skill 擦干净。要强制清空就传 `forcePrune=true`。

> 这道门管得住的实际场景：上次实测中腾讯会议 zip 的 `setup.sh` 在根（不在 `scripts/` 子目录），旧 extractor 静默丢弃；新 extractor 自动归到 `scripts/setup.sh`，安装完直接可跑。

### 配置

```yaml
mateclaw:
  skill:
    workspace:
      root: ${user.home}/.mateclaw/skills
      auto-init: true
      delete-policy: archive
      bundled-skills-path: skills
```

---

## 技能市场（以及 ClawHub）

**技能市场** 页面（`/skills`）是你浏览、安装、编辑、管理技能的地方。三个来源：

- **内置**——MateClaw 出厂带的技能
- **你的自定义技能**——你创建或上传的
- **ClawHub**——一个社区技能库，浏览上千个社区技能、预览、一键安装

ClawHub 是**可选**的——离线或不想用外部技能，就别碰那个 tab。

---

## 技能市场 API

```bash
# 列出所有技能
curl http://localhost:18088/api/v1/skills \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 创建一个自定义技能
curl -X POST http://localhost:18088/api/v1/skills \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "code-reviewer",
    "title": "代码审查员",
    "description": "审查代码，找 bug、风格问题、改进点",
    "type": "custom",
    "content": "---\nname: code-reviewer\n...",
    "tags": ["development", "review"]
  }'

# 启用 / 禁用
curl -X PUT http://localhost:18088/api/v1/skills/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"enabled": true}'

# 删除
curl -X DELETE http://localhost:18088/api/v1/skills/1 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

删除策略可配——默认把技能工作空间移到 `.archived/` 而不是清掉。

---

## 写一个自定义技能 —— 一步一步

1. **决定技能干什么。** 一句话。
2. **列出它需要的工具。** 三个以内是个好目标。
3. **写参数。** 必填的在前，可选的带默认值。
4. **写正文。** 直接对 Agent 说话：*"你是 X。给你 Y 时，做 Z。"*
5. **上传**——通过技能市场 UI 或 API。
6. **绑定**到一个或多个 Agent。
7. **测试**——发一条应该触发这个技能的消息。

示例——"每日站会"技能：

```markdown
---
name: daily-standup
title: 每日站会生成器
description: 根据最近的 git 活动生成站会汇报
version: 1.0.0
type: custom
tools:
  - ShellExecuteTool
parameters:
  - name: repo_path
    type: string
    required: true
    description: git 仓库路径
---

# 每日站会生成器

通过分析最近的 git 活动生成站会汇报。

## 步骤

1. 在 {{repo_path}} 目录下执行 `git log --oneline --since="yesterday" --author=$(git config user.name)`
2. 总结完成的工作
3. 识别所有 work-in-progress 分支
4. 按以下格式输出站会汇报：
   - **昨天**：完成了什么
   - **今天**：根据未合并的分支计划做什么
   - **阻塞**：合并冲突或失败的测试
```

---

## 工作空间隔离

每个工作空间都有自己的一份技能副本。给某个工作空间启用一个技能时，它的文件被 stage 到那个工作空间的目录下、技能的工具被 scope 到这个工作空间、技能写任何文件都在工作空间边界内。v1.4 起技能**目录与运行时也按工作空间隔离**，每个工作空间只看到、只运行属于自己的技能。见 [工作空间](./workspaces)。

---

## 自动技能合成

Agent 用得多了，会发现自己反复在做同样的事——某种特定的数据库查询方式、某种报表格式、SSH 到你机器的命令序列。Agent 能**主动把这些套路变成技能**。

工作流程：

1. Agent 在任务执行中识别出一个可复用的模式
2. Agent 提议创建一个新技能（创建 / 编辑 / 修补 / 删除）
3. 你在 ChatConsole 里审批——看内容、改名字、确认或拒绝
4. 审批通过后，技能自动保存为 `dynamic` 类型，下次直接复用

**安全扫描在保存前自动执行**——危险模式（prompt 注入、脚本注入）会被阻断。技能可以跨 Agent 迁移，也可以打包成 ZIP 分享。

Agent 的记忆和你一起长大。不用再反复说"记住我喜欢按这个格式排表格"。

---

## 模板向导：从起步模板开始

不会写 SKILL.md？打开向导。

`技能 → 创作向导`：

1. 选一个**起步模板**（8 个：调研员、代码审查员、写作助手、客户支持脚本、数据分析、Claude Code helper、Codex helper、空白模板）
2. 填变量——名字、参数、几句描述
3. 上传任何辅助文件（脚本、参考资料、prompt 片段）
4. 设置密钥（API key 之类的）——**密钥进密钥库，不进 SKILL.md**
5. 保存

你得到的不是一份 SKILL.md，是一个**多文件 bundle**——SKILL.md、references/、scripts/、密钥引用，全在一起。

### `skill-authoring` 元技能（v1.4 新增）

现在有一个内置的 `skill-authoring` 技能，启动时自动播种，教 Agent（或你）正确编写 SKILL.md。它涵盖：

- **必填 frontmatter** 以及每个字段的含义
- **校验器限制**——name 必须匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`，内容 ≤ 100k 字符
- **内置 vs 自定义**的编写流程
- scripts/ 与 references/ 的**目录摆放**
- 会导致校验失败或静默出错的**常见坑**

把它绑到一个 Agent 上，"给我写一个能……的技能"第一次就产出合法 bundle，不用来回校验三轮。

---

## 安装前的 Preflight 检查

技能装进来不一定能用——它可能需要某个 API key、某个 CLI 工具、某个 MateClaw 开关被打开。

之前你装完一跑才发现少东西。现在：

**Pre-flight install 对话框**——技能在变活之前自动跑一遍前置检查：

- 需要的工具在不在
- 需要的 API key 配了没
- 需要的 feature flag 开了没
- 依赖的 MCP / ACP 端点连得上吗

少什么直接告诉你，提供一键 **`[Set Up]`** 按钮跳到对应配置页。**不再装完才报错让你自己 debug。**

---

## LESSONS.md：让技能从经验里学习

每个技能可以带一份 `LESSONS.md`——技能在执行中学到的经验。

- 技能跑完一次，可以**主动写一行 lesson**："上次用户在这种格式下不满意，下次别这样"
- 下次同名技能再被调用，LESSONS 自动注入到 prompt 上下文
- 用得越多，技能越知道**什么时候该出场、什么时候该闭嘴**

这是技能"自我进化"的第一版。技能从一组指令，变成有套路、有经验、能成长的东西。

LESSONS 在技能详情抽屉的 **Memory tab** 里查看和编辑。

---

## 密钥（Secrets）：把 token 放对地方

很多技能要 API 凭证才能跑——腾讯会议要 `TENCENT_MEETING_TOKEN`、Slack 要 bot token、Linear 要 personal API key。这些东西**不能写进 SKILL.md**（会进 prompt，泄露给 LLM）、不能写进 scripts（commit 到代码就完了）、改 `~/.zshrc` 又要重启服务器、桌面端跨机更带不走。

v1.3 起，每个技能自带一个 **per-skill 密钥仓**。

### 在 UI 里管

技能详情抽屉 → **密钥** tab。一张表加一个表单：

```
键名                       值          最近更新       操作
TENCENT_MEETING_TOKEN     sk••••ef    2026-05-12     [改] [删]

[+ 新增密钥]
```

- **明文永不出后端**——列表只回 `preview`（`sk••••ef` 这种 mask），新增/修改弹窗的值字段从空开始，提交即覆写。
- **客户端预校验**——key 必须 `^[A-Za-z_][A-Za-z0-9_]{0,127}$`，错的 key 在浏览器就拦掉。
- **value 字段是 password 型 input + autocomplete=off**——肩窥、截屏、密码管理器都不会沾。

### 怎么落盘 / 怎么注入

| 阶段 | 怎么做 |
|---|---|
| 写入 | `POST /api/v1/skills/{id}/secrets` `{key, value}` → AES 加密 → `mate_skill_secret` |
| 读出 | 子进程启动前 `SkillScriptService.getDecrypted(skillId)` AES 解密 |
| 注入 | `ProcessBuilder.environment().putAll(...)`——**覆盖父进程同名环境变量** |

注入语义是**密钥仓里有的覆盖 `.zshrc` 里的；密钥仓里没有的沿用 `.zshrc`**。多人 / 多机部署、桌面端用户、公司域账户互相不串库时，密钥仓是更靠谱的事实源。

### REST 接口

```bash
# 列（masked）
GET    /api/v1/skills/{id}/secrets
# upsert（value 为空等同删除）
POST   /api/v1/skills/{id}/secrets   {"key":"...", "value":"..."}
# 删
DELETE /api/v1/skills/{id}/secrets/{key}
```

### 一个完整例子：腾讯会议

```
SkillMarket → tencent-meeting-mcp 卡片 → 详情抽屉 → 密钥 tab
  → +新增密钥 → key=TENCENT_MEETING_TOKEN, value=<paste 你的 token>
  → 保存

之后 agent 跑 setup.sh 或 scripts/tencent_meeting.py 时：
  ProcessBuilder env 里就有 $TENCENT_MEETING_TOKEN
  → mcporter / Python 脚本走腾讯 API → 会议号回来
```

不再需要改 `~/.zshrc`、不需要重启 mateclaw。

---

## 技能发现：装完就能被用上

之前装一个新技能，agent 经常找不到。三个原因，三个修复，v1.3 都补了。

### 1) 新技能在 prompt catalog 里**优先**

agent 的 system prompt 里有一个紧凑的 Skills 表。每个模型按 max input tokens 给一个上限——qwen-turbo 这种 8192 上限的模型只塞 **8 个**。一个全新的技能没有使用记录，原本的 RECOMMENDED 排序会把它埋在 ~40 个老技能后面，进不了 top-8。

v1.3 起在排序链最前加一档「**最近 7 天安装的优先**」。装完到周一回来都还在第一屏——足够长，又不会无限期占位。Builtin 与虚拟 MCP/ACP 行不算（你不是"刚装"它们）。

### 2) `listAvailableSkills()` 教会 LLM 怎么搜更多

工具描述里现在明确写了：

- 默认页 20 条；如果看到 `Showing: 20 of 47` 这种字样，**用 `keyword=<部分名>` 或 `limit=50` 重试**
- 如果用户报上来一个具体技能名，**别走目录**，直接 `readSkillFile(skillName="<exact-name>", filePath="SKILL.md")` 验证

返回结果末尾被截断时也带一行截断提示，小模型也能看见怎么续搜。

### 3) skill 名误调成 tool 时**自动重定向**

LLM 偶尔会把 skill 名当 tool 调（`tencent-meeting-mcp({...})`）。旧版本会返回一段文字提示叫它去 `readSkillFile`——qwen-turbo 这种小模型经常理解不了，回一句"好的我去查"就把回合结束了，进死循环。

v1.3 起，`ToolExecutionExecutor` 检测到这种情况且 `readSkillFile` 已绑定到 agent，**透明地代它跑 readSkillFile**，把 SKILL.md 全文（带 `[auto-redirect]` 前缀 + 原始参数回显）作为工具结果返回。模型一看就有 SKILL.md 里的可执行示例可以照抄，下一步直接 `runSkillScript`，不会再卡。

> 这条修复对所有小模型都有效，对大模型也无害（它们本来就会看完提示直接 retry）。

---

## 渐进式技能披露（v1.4 新增）

把每个技能完整的 SKILL.md 全塞进 system prompt 不可扩展——既炸 token 预算，又让 prompt 缓存每回合失效。v1.4 反过来：prompt 里只放一张紧凑目录，Agent **按需拉取**某个技能的指令。

**`load_skill(skillName, filePath?)`** 在 Agent 决定用某个技能的当下，加载它的 SKILL.md（或通过可选的 `filePath` 加载 bundle 里任意文件）：

- **经消息历史注入，不进 system prompt**——加载的内容作为一个会话回合到达，所以 system prompt（及其缓存）整个会话保持逐字节稳定。
- **加载过的技能会被置顶**到后续回合的运行时目录顶端，Agent 一直看得见自己刚拉进来的东西。
- 目录引导会告诉模型用技能前先 `load_skill(skillName=<name>)`，用户点名某个具体技能时直接调它。

```yaml
mateclaw:
  skill:
    disclosure:
      load-skill-tool:
        enabled: true     # 默认；设为 false 回退到旧的 readSkillFile 流程
```

关闭时，目录引导改指向 `readSkillFile`，`load_skill` 不再注册。

---

## 技能生命周期管理员（v1.4 新增）

会合成技能的 Agent 会攒下垃圾——三周前的一次性技能还在目录里占着位子。**管理员（curator）** 是一个每日扫描，把闲置的、**Agent 创建的**技能沿 `active → stale → archived` 老化，让它们退场而不删除任何东西。

- 闲置超过 `staleAfterDays`（默认 30 天）→ **stale**；闲置超过 `archiveAfterDays`（默认 90 天）→ **archived**（工作空间移到 `.archived/` 子目录）。`restore` 把归档技能拉回来。
- **永不触碰**：内置、置顶、MCP/ACP/虚拟技能，以及任何以受保护前缀开头的名字（默认 `sys-`、`ops-`）。

### 设置 → 技能管理员 面板

- **预览（dry-run）**——在真正执行前，看清下一次扫描会移动哪些技能。
- **暂停 / 恢复**整个扫描；**激活 / 停用**单个技能。
- **上次运行 / 下次运行**时间戳，以及**各状态计数**（active / stale / archived）。

### 配置

```yaml
mateclaw:
  skill:
    curator:
      enabled: true
      cron: "0 0 2 * * *"        # 每天 02:00
      staleAfterDays: 30
      archiveAfterDays: 90
      scope: AGENT_CREATED       # AGENT_CREATED | ALL_DYNAMIC | OFF
      protectPrefixes: ["sys-", "ops-"]
```

`scope: AGENT_CREATED` 只动有来源对话的技能；`ALL_DYNAMIC` 还会扫手动创建的 dynamic 技能；`OFF` 无视 `enabled` 直接关闭扫描。

### 技能市场里的生命周期

技能页接住了生命周期：

- **生命周期标签页**——已启用 / Stale / 已归档。
- 卡片显示**「最近使用」**徽章。
- 详情抽屉新增**手动归档 / 恢复 / 置顶**。
- 手动归档一个**仍被绑定**的技能会触发**二次确认握手**——不会在某个数字员工还在用它时悄悄把技能抽走。

---

## ACP 桥接：把外部编码 Agent 接进来

ACP（Agent Client Protocol）是一种把外部 Agent 客户端（Claude Code、Codex、其他兼容客户端）以技能身份接入 MateClaw 的协议。

接入之后：

- ACP 端点**自动桥接成技能卡**——出现在技能页，自带一组包装工具
- **可视化环境编辑器**——每个 endpoint 需要的 key、URL、CWD 都在 UI 上配
- **会话级 cwd**——每个 ACP 会话自己的工作目录
- **错误翻译**——上游 "Request not allowed" 这种话翻成你看得懂的
- **OAuth 钥匙串劫持检测**——发现 OAuth token 被其他应用占了，提示你重新登录

模板：`claude-code-helper`、`codex-helper`——开箱可用。

数字员工调用 ACP 技能的方式，和调内置工具没区别。

### MCP/ACP 技能的虚拟 SKILL.md（v1.4 新增）

MCP / ACP 衍生的技能过去是不透明的工具包，没有可读指令。v1.4 从每个 MCP/ACP 服务的元数据（transport、command、args、env、暴露的工具）**合成一份只读的虚拟 SKILL.md**，让这些集成在技能页里变成**可浏览的技能目录**。因为是合成的，虚拟 SKILL.md 每次列举调用都重建——没有过期的持久副本要维护——而且 `load_skill` 能像读真技能一样读它，让 Agent 在调用第一个工具前就拿到这个集成能干什么的说明。

---

## 详情抽屉：所有信息一处看

每个技能卡片点开是一个抽屉，八个 tab：

- **概览**——身份字段、manifest 投影、来源、版本
- **正文**——`SKILL.md` 编辑器（接管整个抽屉宽度）
- **工具**——这个技能用哪些工具（含 effective tool 展开）
- **特性**——能力矩阵
- **安全**——内容扫描结果、Tool Guard 关联规则
- **经验**——`LESSONS.md` 内容
- **密钥**——env-var 形态的凭证（v1.3 新增，详见下方"密钥（Secrets）"小节）
- **记忆**——绑定到这个技能的数字员工

卡片本身瘦身——只 6 个字段、一个状态徽章。**清楚比全面重要。**

---

## 安全

自定义技能在变活之前会过几道检查：

- **内容扫描**——上传时 `SKILL.md` 会被扫描 prompt 注入和脚本注入
- **工具需求检查**——`tools:` 列表只能引用存在的工具
- **Tool Guard 合规**——列出危险工具的技能继承那些工具的 Tool Guard 规则
- **MCP 技能约束**——MCP 支撑的技能继承它背后 MCP 服务的安全约束

完整的技能安全审核在 [安全与审批](./security) 里。

---

## 下一步

- [工具系统](./tools)——技能能用的工具
- [Agent 引擎](./agents)——Agent 怎么在回合中调用技能
- [MCP 协议](./mcp)——MCP 支撑的技能
- [安全与审批](./security)——技能扫描的细节
