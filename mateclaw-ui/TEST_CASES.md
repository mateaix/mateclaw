# UI 精简改动 - 验证测试用例

> 基于 MateClaw 内置的 3 个 Agent、19 个工具、31 条 Guard 规则和审批工作流设计。
> 默认登录：admin / admin123

---

## 前置条件

1. 后端启动：`cd mateclaw-server && mvn spring-boot:run`（零环境变量，启动后到「设置 → 模型」加 LLM 供应商）
2. 前端启动：`cd mateclaw-ui && pnpm dev`
3. 访问 http://localhost:5173，登录

---

## 一、StreamLoadingBar 状态简化验证

### TC-1.1 思考中状态（Thinking）
- **Agent**: MateClaw Assistant（ReAct）
- **操作**: 发送 "请分析一下量子计算的发展趋势"
- **预期**:
  - 加载条显示 **"思考中…"** 和 ◐ 图标，不再显示 "准备上下文"/"读取记忆"/"推理中" 等内部阶段
  - 无 statusDetail 第二行解释文本
  - 无 slowHint（即使等待超过 8 秒也不出现 "耗时较长" 提示）
  - 仅显示耗时计时器（如 "12s"），不显示 token 计数

### TC-1.2 执行中状态（Working）
- **Agent**: MateClaw Assistant（ReAct），绑定 WebSearch 工具
- **操作**: 发送 "搜索一下今天的科技新闻"
- **预期**:
  - 工具调用时加载条切换为 **"执行中…"** 和 ⚙ 图标
  - 显示工具名（如 `search`）
  - 不显示 "正在执行工具" 的详情文本

### TC-1.3 撰写中状态（Writing）
- **Agent**: MateClaw Assistant（ReAct）
- **操作**: 发送 "写一篇 500 字的短文"
- **预期**:
  - 内容流输出阶段，加载条显示 **"生成中…"** 和 ▸ 图标
  - 流结束后加载条消失

### TC-1.4 错误/中断状态
- **操作**: 发送消息后立即点击停止按钮
- **预期**:
  - 加载条图标变为 ⊘，文本变红
  - 无 amber/blue 等其他颜色状态

---

## 二、审批 UI 精简验证

### TC-2.1 Shell 命令触发审批（高危操作）
- **Agent**: MateClaw Assistant（ReAct），绑定 Shell 工具
- **操作**: 发送 "帮我删除 /tmp/test 目录下的所有临时文件"
- **预期**:
  - Agent 推理后调用 `execute_shell_command`，参数含 `rm`
  - Guard 规则 `SHELL_RM` 触发 → 进入审批流程
  - **ChatInput 区域**：替换为审批栏，显示工具名 + 批准/拒绝按钮（保留）
  - **MessageBubble 中**：仅显示一行 "等待审批：`execute_shell_command`"，无完整的审批卡片（无 severity 徽章、无 findings 列表、无参数展示、无等待 spinner）
  - 点击"批准"后，气泡状态变为 "已批准：`execute_shell_command`"

### TC-2.2 文件写入触发审批
- **Agent**: MateClaw Assistant（ReAct），绑定 WriteFile 工具
- **操作**: 发送 "创建一个 hello.txt 文件，内容写 Hello World"
- **预期**:
  - `write_file` 工具触发审批
  - 输入栏显示审批操作，气泡仅一行状态
  - 拒绝后，气泡状态变为 "已拒绝：`write_file`"

### TC-2.3 危险命令直接阻断（CRITICAL 级别）
- **Agent**: MateClaw Assistant（ReAct），绑定 Shell 工具
- **操作**: 发送 "执行 rm -rf /"
- **预期**:
  - Guard 规则 `SHELL_RM_RF_ROOT` 直接 BLOCK
  - 不进入审批流程，直接返回阻断消息
  - 输入栏不显示审批栏

---

## 三、Plan-Execute 流程验证

### TC-3.1 PlanStepsPanel 渲染唯一性
- **Agent**: Task Planner（Plan-Execute）
- **操作**: 发送 "帮我调研 MateClaw 项目的技术栈，列出前端和后端分别用了哪些核心技术，然后生成一个技术概览文档"
- **预期**:
  - 生成计划后，PlanStepsPanel 只在消息气泡中出现**一次**
  - 步骤进度正确显示（pending → running → completed）
  - 不在分段式视图和传统模式中同时出现两个 PlanStepsPanel

### TC-3.2 Plan 中触发审批的步骤暂停与恢复
- **Agent**: Task Planner（Plan-Execute），绑定 Shell + WriteFile 工具
- **操作**: 发送 "查看当前目录结构，然后创建一个 project-summary.md 文件"
- **预期**:
  - 计划包含多个步骤
  - 涉及文件写入的步骤触发审批
  - 审批期间，PlanStepsPanel 该步骤显示 running 状态
  - 气泡中审批为一行极简文本
  - 批准后步骤继续执行，状态更新为 completed

---

## 四、BrowserTimeline 默认收起验证

### TC-4.1 浏览器操作时间线默认折叠
- **Agent**: MateClaw Assistant（ReAct），绑定 BrowserUse 工具
- **操作**: 发送 "打开浏览器访问 baidu.com，截图"
- **预期**:
  - 浏览器操作完成后，时间线默认**收起**
  - 仅显示标题栏 "Browser: N actions"
  - 点击标题栏可展开查看操作细节和截图

---

## 五、动画与视觉一致性验证

### TC-5.1 无 Typing Bounce Dots
- **Agent**: 任意 Agent
- **操作**: 发送消息，观察 AI 响应开始前
- **预期**:
  - 不再出现三个弹跳圆点的加载动画
  - 使用 TypingCursor（闪烁光标）代替

### TC-5.2 动画一致性
- **操作**: 在不同场景触发加载状态
- **预期**:
  - StreamLoadingBar 的 icon-pulse 动画时长统一为 1.2s
  - 所有 spinner 使用相同的旋转动画
  - 无竞争性的多重脉冲动画

---

## 六、ChatInput 占位符验证

### TC-6.1 加载中占位符不暴露键盘操作
- **Agent**: 任意 Agent
- **操作**: 发送消息，在 AI 生成过程中观察输入框
- **预期**:
  - 占位符仅显示原始 placeholder 文本
  - 不再显示 "(Enter to send / interrupt)"
  - 发送按钮变为红色停止图标已足够提示

---

## 七、深色模式主题变量验证

### TC-7.1 深色模式颜色正确性
- **操作**: 切换到深色模式（侧边栏底部主题切换）
- **检查项**:
  - StreamLoadingBar 文本颜色使用主题主色调（非硬编码 #f97316）
  - 审批状态文字颜色正确（成功绿/失败红均跟随主题）
  - 中断按钮使用 `--mc-warning` 变量的深色模式值 (#fbbf24)
  - 排队指示器使用 `--mc-info` 变量的深色模式值 (#60a5fa)
  - 工具调用状态图标（成功/失败/等待）颜色均来自 CSS 变量

### TC-7.2 浅色/深色快速切换
- **操作**: 在生成过程中快速切换浅色/深色模式
- **预期**:
  - 所有颜色即时切换，无残留的硬编码颜色

---

## 八、回归测试

### TC-8.1 普通对话流程（无工具调用）
- **Agent**: MateClaw Assistant
- **操作**: 发送 "你好，介绍一下你自己"
- **预期**: 正常生成回复，无 UI 异常

### TC-8.2 多轮对话 + 工具调用
- **Agent**: MateClaw Assistant，绑定多个工具
- **操作**: 连续发送 3-5 条消息，触发不同工具
- **预期**: 每轮消息的加载条、工具调用显示、内容输出均正常

### TC-8.3 消息中断与重试
- **操作**: 发送消息 → 中断 → 重试
- **预期**: 中断指示器正常显示，重试后正常生成

### TC-8.4 会话切换
- **操作**: 在多个会话间切换
- **预期**: 历史消息正确加载，审批状态（已批准/已拒绝）正确回显

### TC-8.5 移动端响应式
- **操作**: 浏览器宽度缩小到 768px 以下
- **预期**:
  - 审批栏在 ChatInput 中正常自适应
  - 气泡中审批状态一行文本不溢出
  - 加载条内容不截断
