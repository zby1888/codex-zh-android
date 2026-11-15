# Codex CLI → Android 图形界面功能对照表

## 目标

尽可能把 Codex CLI 的日常能力搬到 Android 原生界面。Codex CLI / Codex App Server 继续负责智能体执行；Android 负责会话、项目、权限、工具、设置和结果的图形化呈现。终端作为独立入口保留，不能用聊天输入框假装终端。

主线使用固定版本的官方 Codex 源码和 Termux Android/Bionic 构建配方，不要求汉化补丁仓库参与 APK 构建。Android 界面自身完整中文化。

## 能力分层

| CLI 能力 | Android 界面入口 | 接入方式 | 验收要求 |
|---|---|---|---|
| 新建、继续、恢复会话 | 聊天 / 会话列表 | App Server thread/start、thread/resume | 关闭 App 后仍能找回会话 |
| 会话分支、归档、恢复 | 会话详情菜单 | thread/fork、thread/archive、thread/unarchive | 原会话不被覆盖 |
| 历史记录和分页 | 聊天时间线 | thread/turns/list、thread/items/list | 长会话可分页加载 |
| 发送、追加指令、停止 | 聊天输入栏 | turn/start、turn/steer、turn/interrupt | 流式响应、停止和继续状态准确 |
| 智能体思考与执行进度 | 消息时间线 / 任务详情 | item 生命周期、delta、turn 事件 | 区分回答、命令、文件修改、工具调用 |
| 命令执行与输出 | 执行卡片 | commandExecution 事件 | 显示命令、工作目录、实时输出和退出状态 |
| 文件修改与 Diff | 文件变更卡片 / Diff 查看器 | fileChange item、turn/diff/updated | 能查看变更、审批、拒绝和应用状态 |
| 命令、文件、网络权限 | 审批弹层 | App Server server-initiated approval 请求 | 显示具体动作和范围；支持允许一次、允许本会话、拒绝、取消等协议支持的选项 |
| 模型和推理强度 | 聊天工具栏 / 设置 | model/list、线程/轮次参数 | 以服务端返回的可用模型为准，不写死列表 |
| 沙盒和审批策略 | 项目设置 / 会话设置 | App Server 配置与线程参数 | 清楚显示权限边界；危险选项二次确认 |
| 项目目录与工作区 | 项目选择器 | cwd、项目发现、私有 HOME | 聊天和终端使用同一项目目录 |
| 项目指令文件 | 项目详情 | Codex 指令发现 / 文件查看 | 显示已加载的指令来源 |
| MCP 服务 | MCP 管理 | config/mcpServer、mcpServerStatus/list、reload | 添加、编辑、启停、认证、发现工具和查看错误 |
| MCP 工具调用 | 聊天工具卡片 | mcpServer/tool/call、工具事件 | 展示参数、进度、结果和审批 |
| MCP OAuth / 表单询问 | 授权页 / 表单弹层 | mcpServer/oauth/login、elicitation | 可跳转授权并回到原会话 |
| Skills | 技能管理 | skills 配置与可用的 App Server 方法 | 启停技能、显示来源；不伪造不可用能力 |
| 目标 / 任务预算 | 会话目标面板 | thread/goal/get、set、clear | 显示目标、预算和使用情况（协议有返回时） |
| 代码审查 | 会话操作菜单 | review/start | 支持未提交变更、分支、提交和自定义审查 |
| 配置查看和编辑 | 设置中心 / 高级配置 | config/read、config/value/write、config/batchWrite | 修改前预览，保存后重新读取验证 |
| 终端交互 | 终端页 | Android PTY + 终端模拟器 | 真正 TTY、交互程序、信号、窗口尺寸、多会话 |
| 文件浏览 | 工作区文件页 | App Server 文件接口或安全文件层 | 读写、重命名、删除均有边界和确认 |
| 快捷命令 / 斜杠命令 | 输入框命令面板 | 对应 App Server 方法或 CLI 命令映射 | 每条命令标注是否支持、是否需要终端回退 |
| 日志、错误和诊断 | 运行状态 / 诊断页 | App Server 通知、进程 stderr、结构化错误 | 不泄露 API Key、环境变量密钥或隐私内容 |

## 必须保留的两个独立执行通道

1. **Agent 通道**：Android 启动私有运行时里的 `codex app-server`，通过 JSONL/stdin/stdout 与 App Server 通信。它负责聊天、会话、工具调用、审批和 Diff。
2. **Terminal 通道**：Android 为 Shell 建立独立 PTY 会话，负责 Bash、交互式命令、编辑器、Git 等终端程序。

两者共享项目目录和必要的 Codex 配置，但不能共用同一个输入输出管道。聊天不能通过拼接终端文本来模拟 Agent 协议；终端也不能用一次性 ProcessBuilder 代替 PTY。

## 服务商兼容边界

- Codex Agent 模式走 Codex App Server 和 Codex 支持的 Responses API 服务商配置。第三方中转站必须实际兼容所需的 Responses API、流式事件和工具调用行为。
- 仅支持 Chat Completions、Anthropic Messages 或 Gemini 的服务商，不能直接冒充 Codex Agent 服务商。它们可以由独立的通用聊天适配器接入。
- 每个服务商配置都要记录协议类型和能力；连接测试要做协议探测，不能只用 HTTP 200 判定兼容。
- API Key 使用 Android Keystore 保护的密钥存储；日志、崩溃报告和普通配置文件不得出现明文密钥。

## 版本与功能门槛

- 以 `runtime/sources.lock.yml` 锁定的 Codex 版本为准。先实现该版本稳定公开的 App Server 方法，再逐项验证新增能力。
- 标记为 experimental、under development 或文档明确要求不要用于生产客户端的方法，默认不作为首发依赖。
- 某项能力若只能通过 TUI 内部交互或未公开接口完成，先保留终端回退入口，不得宣称已由图形界面完整支持。
- 服务端、模型和中转站的能力会不同；界面必须展示真实可用能力，不能把某个 CLI 功能无条件承诺给所有服务商。

## 分阶段验收

### A. 私有运行环境与进程
- APK 内有固定版本的官方 Codex CLI 和所需 helper。
- 首次离线初始化成功；`codex --version` 与 `codex app-server --listen stdio://` 均通过启动检查。
- App 进程退出、后台切换、设备重启和升级后，运行时与会话数据可恢复。
- 不读取或修改用户原有 Termux。

### B. Agent 图形界面
- 完成 initialize 握手、thread 生命周期、turn 生命周期、事件流、停止/追加指令。
- 完成命令执行、文件修改、Diff、审批和错误恢复。
- 会话历史、项目目录和模型选择可用。

### C. 终端
- 真正 PTY，多会话，终端尺寸变化，复制粘贴，快捷键，Ctrl-C/信号和进程清理。
- 终端与聊天切换时保持同一项目目录。

### D. 工具与设置
- 第三方 Responses 兼容服务商、通用聊天协议适配器、MCP STDIO/HTTP、OAuth、Skills、配置导入导出。
- 权限策略、密钥安全、日志脱敏和危险操作确认通过测试。

### E. 发布门槛
UI 构建、私有 Runtime 构建、完整 APK 构建和 Android 实机测试分别报告。只有完整 APK 内含 CLI/helper，且实机通过启动、聊天、审批、PTY、MCP 冒烟测试，才可以标记为可用版本。
