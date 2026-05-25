# 常见问题（FAQ）

常见问题 + 真答案。你的问题不在这里就看对应的功能页，或者去 [GitHub issue](https://github.com/matevip/mateclaw/issues) 开一个。

---

## 安装和搭建

### 需要什么 Java 版本？

**Java 17 或更高。** MateClaw 用了 Java 17 引入的特性。用 `java -version` 验证。

用桌面端的话，**完全不需要装 Java**——安装器自带 JRE 21。

### 要一个云 API key 才能开始？

不用。三条无 key 的路径：

- **Ollama**——本地 GPU 推理；MateClaw 启动时在 `localhost:11434` 自动探测
- **ChatGPT OAuth**——有 ChatGPT Plus 或 Pro 订阅的话走浏览器 OAuth 流程——**不需要 API Key**
- **OpenRouter 免费档**——200+ 免费模型，一个 key 就能访问

**启动 MateClaw 也不需要把任何 API Key 设成环境变量。** 所有供应商配置都在启动后通过 UI 的 `设置 → 模型` 来做。

### 怎么拿 DashScope API Key？

1. 去[阿里云 DashScope 控制台](https://dashscope.console.aliyun.com/)
2. 注册或登录
3. 创建一个 API Key
4. 在 MateClaw 里进 `设置 → 模型 → DashScope` 粘贴

### 后端起不来——18088 端口被占了

换端口：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=19090"
```

**桌面端会动态挑一个空闲端口**，所以在那里看不到这个错误。

### 启动时 H2 数据库锁错误

```bash
rm -f data/mateclaw.mv.db.lock
```

或者清空数据目录重新开始：

```bash
rm -rf data/
```

---

## 认证

### 默认凭证是什么？

用户名 `admin`，密码 `admin123`。**任何真实部署都要立刻改。**

### 我的 JWT token 老过期

MateClaw 实现了**滑动窗口续签**——token 剩余 25% 时服务器在响应头 `X-New-Token` 里发一个新 token。前端自动处理。

手动调 API（curl、Postman）的话，读 `X-New-Token` header 用新值做后续请求。

### 怎么改 admin 密码？

UI 里 `设置 → 安全` 最简单。或者直接改数据库（BCrypt 编码）：

```sql
UPDATE mate_user SET password = '$2a$10$...' WHERE username = 'admin';
```

---

## 模型

### 怎么配置模型？

**全部通过 UI。** `设置 → 模型 → 添加供应商`。选供应商、粘 API Key（或为 ChatGPT Plus OAuth、或 Ollama 跳过）、保存、测试。**模型配置 100% 通过 UI 管理**——没有 `spring.ai.*` YAML 需要改。

LLM API Key 不读环境变量——`DASHSCOPE_API_KEY` 之类的设置不会生效。容器零 Key 就能启动，登录后加供应商即可。

### 怎么在 MateClaw 里用 GPT-4？

`设置 → 模型 → 添加供应商`。要么粘你的 OpenAI API Key，要么如果你有 ChatGPT Plus/Pro 就用 **OpenAI OAuth**——浏览器窗口弹出让你登录。保存后从模型选择器挑 `gpt-4o`（或任何模型）。

### Ollama 模型很慢

本地模型性能取决于硬件：

- 内存小的机器用小模型（7B 而不是 14B）
- 确保 Ollama 有 GPU 访问（`ollama ps` 应该显示 GPU）
- 有条件调大 Ollama 内存上限
- `qwen2.5:7b` 或 `qwen3:latest` 是好平衡

### 能同时用多个供应商吗？

可以。配多个供应商，把不同的模型配置分给不同的 Agent。每个 Agent 用自己的模型——或者继承全局默认。

### 怎么给有些 Agent 挂便宜模型、给另一些挂推理模型？

- **全局活跃模型**设成便宜通用的（`qwen-plus`、`gpt-4o-mini`）
- 按 Agent 覆盖：推理重的 Agent 单独绑 `o3` 或 `qwen-max`
- 聊天窗口里的分组模型选择器也能按会话切

---

## 工具和搜索

### 怎么切换搜索 provider？

`设置 → 系统 → 搜索服务`。从 Serper、Tavily、DuckDuckGo、SearXNG 里挑。开启 **fallback**。立刻生效。

无 key 选项（DuckDuckGo、SearXNG）让你不需要 API Key 也能搜索。

### 怎么加一个自定义工具？

写一个 Spring `@Component`：

```java
@Component
public class MyCustomTool {

    @Tool(description = "获取天气信息")
    public String getWeather(@ToolParam(description = "城市名") String city) {
        return "晴，25°C";
    }
}
```

启动时自动注册。见 [工具系统](./tools)。

**工具做任何危险的事情时，给它加一条 Tool Guard 规则。**

### WebSearchTool 返回空结果

在 `设置 → 系统 → 搜索服务` 里配一个搜索供应商。无 key 选项（DuckDuckGo、SearXNG）不需要 API Key。

### Tool Guard 一直在挡我的工具调用

**这是刻意设计的**——危险工具要审批。三种放宽方式：

1. **给具体的命令模式加一条 allow 规则**（`设置 → 安全与审批 → Tool Guard 规则`）。例子：`ShellExecuteTool`，参数模式 `^(ls|cat|grep|find)\s` → `allow`。
2. **把默认策略调成 `allow`**（`application.yml`）：
   ```yaml
   mateclaw:
     tool:
       guard:
         default-policy: allow   # 生产不推荐
   ```
3. **完全关掉 Tool Guard**（**只开发**）：
   ```yaml
   mateclaw:
     tool:
       guard:
         enabled: false
   ```

**生产安全：** 保持 `default-policy: require_approval`，为你信任的具体模式加有针对性的 allow 规则。

### 怎么配 MCP 服务？

UI 里用 `工具 → MCP 服务`。三种传输模式：stdio、streamable_http、sse。配置变更立刻生效。见 [MCP 协议](./mcp)。

---

## LLM Wiki

### Wiki 和记忆有什么区别？

**Wiki 是刻意的。记忆是被动的。**

- **Wiki**——你扔文档进去，系统消化成结构化页面，Agent 读这些页面。**你建的**、**你编辑的**、**你审核的**。
- **记忆**——作为对话副产品自动构建。Agent 提取看起来值得记住的东西，每夜整合模式。

**源材料可查询**用 Wiki（产品规格、设计文档、过去决策）。**累积的上下文**用记忆（偏好、在做什么）。

### Agent 有知识库为什么还在瞎猜？

因为你没把 Agent 绑到 KB 上。`Agents → [某个 Agent] → 知识库`——在那里绑。**Agent 没显式绑定之前，wiki 工具不会被注入。**

### 消化很慢

调 `application.yml` 里的 `mate.wiki.digestion-concurrency`。默认 2——LLM 额度允许就调到 4 或 8。

---

## 记忆

### 记忆不工作

1. **确认自动提取开着**——检查 `mate.memory.auto-summarize-enabled`
2. **确认对话达到阈值**——`min-messages-for-summarize`（默认 4）、`min-user-message-length`（默认 10）
3. **检查冷却**——同一个 Agent 在 `cooldown-minutes`（默认 5 分钟）内不能触发第二次
4. **看日志**——`vip.mate.memory` 在 DEBUG 级别显示每一次尝试

### 记忆整合任务没跑

整合由 `mate_cron_job` 里的种子数据驱动，每个 Agent 每天凌晨 2 点。检查：

- `enabled` 列是 `1` 吗？
- 种子 cron 任务在吗？（`SELECT * FROM mate_cron_job WHERE task_type = 'memory_emergence'`）

### 我不喜欢 Agent 记住的关于我的东西

直接在 Agent 工作空间视图里编辑 `PROFILE.md` 或 `MEMORY.md`。**锁定**你编辑过的页面。见 [记忆系统](./memory)。

---

## 审批

### 我批准了一个工具调用但 Agent 没恢复

1. `AWAITING_APPROVAL` 还是 true 吗？（`GET /api/v1/agents/{id}`）
2. 审批真的持久化了吗？（`GET /api/v1/approvals/{id}`）
3. Agent 日志里 replay 尝试附近有错误吗？
4. Replay 失败的话，Agent 应该在聊天里暴露一个错误

### 我想批量批准这个 Agent 未来的工具调用

你想要的是**一条 allow 规则**，不是一次性全批准。`设置 → 安全与审批 → Tool Guard 规则 → 添加规则`。

### Pending 审批能放多久？

默认 10 分钟，之后过期变成 `rejected`。用 `mateclaw.tool.guard.approval-timeout-seconds` 配置。

---

## Agent

### Agent 卡在 RUNNING 状态

常见原因：

1. **工具调用超时**——某个工具在等挂住的外部服务
2. **超过迭代上限**——`MAX_ITERATIONS_REACHED` 处理器强制给尽力而为的答案
3. **等审批**——Tool Guard 暂停了执行
4. **看日志**：
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.vip.mate.agent=DEBUG"
   ```

### 怎么判断我的 Agent 在用对的工具？

展开聊天界面的**思考面板**。每次工具调用、参数、结果都看得见。Agent 在调错工具的话，**收紧 system prompt** 引导它。

---

## 渠道

### 钉钉 / 飞书 webhook 收不到消息

1. 服务器不是公网可达
2. 大部分平台要求 HTTPS
3. 验证 token 错了
4. Bot 没被加到群里或没有消息权限

**更简单：** 用 **stream / 长连接 / WebSocket 模式**而不是 webhook。钉钉 Stream、飞书 WebSocket、Telegram Long-Polling、Discord Gateway、Slack Socket mode——**都不需要公网 IP**。

### 能同时用多个渠道吗？

可以。每个渠道独立、绑一个 Agent。可以同时跑 web 控制台、钉钉、Telegram，绑不同的 Agent（或同一个——你说了算）。

### Telegram / Discord 访问不到 API（国内网络）

在渠道配置里配 `http_proxy`：

```json
{
  "bot_token": "...",
  "http_proxy": "http://127.0.0.1:7890"
}
```

---

## 数据备份

### 怎么备份数据？

**H2（开发 / 桌面）：** 停服务，拷贝 `./data/mateclaw.mv.db`：

```bash
cp ./data/mateclaw.mv.db ./backup/mateclaw-$(date +%Y%m%d).mv.db
```

**MySQL（生产）：**

```bash
mysqldump -u root -p mateclaw > mateclaw-backup-$(date +%Y%m%d).sql
```

**Docker：**

```bash
docker exec mateclaw-mysql mysqldump -u root -p${MYSQL_ROOT_PASSWORD} mateclaw > backup.sql
```

**桌面端**数据在每个用户目录下：

- macOS：`~/Library/Application Support/MateClaw/`
- Windows：`%APPDATA%/MateClaw/`
- Linux：`~/.local/share/MateClaw/`

---

## 桌面应用

### 桌面 app 启动不了

安装器自带 JRE 21。看日志：

- macOS：`~/Library/Logs/MateClaw/`
- Windows：`%APPDATA%/MateClaw/logs/`
- Linux：`~/.local/share/MateClaw/logs/`

从终端启动。Windows 右键 → 解除锁定。macOS "系统设置 → 隐私与安全性"允许未签名应用。

### 怎么更新桌面 app？

**自动更新**通过 electron-updater。启动时检查 GitHub Releases 并弹提示。也可以手动从 [Releases](https://github.com/matevip/mateclaw/releases) 下载。

---

## Docker

### Docker 容器起不来

```bash
docker compose logs mateclaw-server
docker compose logs mateclaw-mysql
```

常见：

- MySQL 还没就绪
- 端口冲突（18080、3306）
- 缺 `.env`——从 `.env.example` 拷一份

### 怎么在 Docker 里访问数据库？

```bash
docker exec -it mateclaw-mysql mysql -u root -p mateclaw
```

---

## 调试

### 怎么开 DEBUG 日志？

```yaml
logging:
  level:
    vip.mate: DEBUG
    vip.mate.agent: DEBUG
    vip.mate.agent.graph: DEBUG
    org.springframework.ai: DEBUG
```

或：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.vip.mate=DEBUG"
```

### 怎么访问 H2 console？

1. 访问 `http://localhost:18088/h2-console`
2. JDBC URL：`jdbc:h2:file:./data/mateclaw`
3. 用户名：`sa`
4. 密码：（空）

**生产环境关掉它。**

### 怎么观察 SSE 流式事件？

浏览器 DevTools → Network → 筛选 `EventStream`。或：

```bash
curl -N -H "Authorization: Bearer <token>" \
  "http://localhost:18088/api/v1/chat/1/stream?conversationId=1"
```

---

## 前端

### 构建后前端显示空白页

```bash
cd mateclaw-ui
pnpm build
ls ../mateclaw-server/src/main/resources/static/
# 应该包含 index.html 和资源文件
```

### 深色模式不持久化

存在 `localStorage`。清了浏览器数据会丢。

### UI 感觉卡顿

- 把日志调回 INFO
- 检查 `java -Xmx` 设置
- 在老会话上点**清空消息**

---

## 下一步

- [快速开始](./quickstart)——搭建 walkthrough
- [配置说明](./config)——完整配置参考
- [贡献指南](./contributing)——怎么报 bug 和提功能请求
- [GitHub Issues](https://github.com/matevip/mateclaw/issues)——文档没答案的时候去这里
