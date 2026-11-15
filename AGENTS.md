# Codex Agent 开发约束

本文件是仓库内 Codex CLI、自动化 Agent 和人工开发者的长期项目指令。开始任何代码修改前必须先阅读本文件，并阅读 README.md、docs/ARCHITECTURE.md、docs/CLI-FEATURE-PARITY.md、docs/ANDROID-UI-UX-DESIGN.md、docs/TERMINAL-PTY-ARCHITECTURE.md 和 docs/ROOT-DEBUGGING-ARCHITECTURE.md。

## 项目唯一目标

尽可能把 Codex CLI 的主要能力完整搬进 Android 原生操作界面。不能把本项目降级成只有聊天输入框的套壳应用。

- 官方 Codex CLI 是 Agent 执行核心；Android App 是原生图形化客户端和私有运行时宿主。
- Android UI 自己负责完整中文化，不依赖 CLI TUI 是否汉化。
- 汉化版 Codex CLI 只能是可选构建变体，绝不能成为 APK 主线构建的强制依赖。
- 优先使用固定版本、经过 schema 核对的 Codex App Server JSON-RPC/stdio 接口实现 Agent 图形界面；不得用解析 TUI 文本来冒充 Agent 协议。
- 保留真正独立的 PTY 终端。Agent 通道、普通 PTY 和 Root PTY 必须分离，不能共用 stdin/stdout。Root 是可选能力，只有用户主动申请并通过系统 Root 管理器授权后，才能启动独立 Root PTY；不得把整个 Codex Agent / App Server 提权运行。开发 PTY 前阅读 `docs/TERMINAL-PTY-ARCHITECTURE.md`，设计设备级调试时阅读 `docs/ROOT-DEBUGGING-ARCHITECTURE.md`。处理 Termux/Operit 组件许可证，不得在许可证未确认前直接引入 GPL/LGPL 代码。
- 两个通道共享应用私有项目目录和必要配置，但不访问或修改用户已有 Termux、Codex 数据。
- 第三方服务商必须按协议区分。Codex Agent 只接受经过验证的 Responses API 兼容服务商；Chat Completions、Anthropic Messages、Gemini 走独立通用聊天适配器。

## 开源项目参考要求

开发 Android 图形界面和周边管理能力时，必须主动参考并对照以下项目的公开实现与文档，不要闭门造车：

- **OpenCode**（https://github.com/anomalyco/opencode）：重点研究会话/消息数据模型、事件流与状态归并、Agent/子 Agent、工具权限、MCP、Skills、自定义命令、项目配置和 worktree 工作流。借鉴产品结构和通用实现思路；Codex 执行仍通过本项目锁定版本的 Codex CLI / App Server，不混用 OpenCode 的执行协议。
- **Operit**（https://github.com/AAswordman/Operit）：重点研究 Android 原生 Agent 工作台、任务与工具执行进度、附件和工作区上下文、文件/终端/浏览器集成、工作流、ToolPkg、MCP、Skills，以及普通 Shell、Shizuku / ADB、Root 和调试器通道的权限分层。
- **RikkaHub**：重点研究服务商、模型、自定义 API 端点、请求头和 MCP 配置体验。

每次新增相关功能前，先对照参考项目，写清楚借鉴点、Codex 接入方式和本项目的差异；不能照搬与 Codex 不兼容的协议，也不能把“参考”理解成直接复制代码。移植源码、资源、图标、文案或依赖前必须核查许可证、版权声明和兼容条件。参考项目的功能只能作为设计输入，不能在本项目未实现和未测试时宣称已经具备。

## 每次任务开始前

1. 先查看当前分支、工作区状态、相关源码和现有测试，不要假设之前的任务已完成。
2. 查阅 runtime/sources.lock.yml，所有 Codex 协议请求和字段必须与锁定版本匹配。
3. 查阅 docs/CLI-FEATURE-PARITY.md，确认功能是否已有实现、缺失或仅规划。
4. 修改前先说明涉及的模块和兼容性边界；不要一次性重写无关代码。

## 实现要求

- Agent Server 的 stdout 是 JSONL 协议专用通道，不能写入调试文本；stderr 和日志不得泄露 API Key、自定义 Header 值或认证配置。
- API Key 和自定义 Header 值必须使用 Android Keystore 加密保存；写入 Codex 配置时优先使用环境变量引用，不能写入明文。
- 服务端主动发起的审批请求必须由用户明确处理，不能默认放行。
- Codex Agent 和普通 PTY 始终使用应用 UID。Root 只允许作为独立的可选 PTY / 受控工具通道；默认关闭、逐次授权、可撤销，不得把 API Key、App Server 或整个工作区 Agent 进程带入 root 身份。
- 逆向和动态调试功能仅用于自有或明确授权的设备 / 应用；进程附加、内存访问、Hook、注入和系统写入必须明确展示目标并由用户审批。
- UI、Runtime、CLI/App Server、完整 APK 和 Android 实机测试分别验收。
- 任何尚未实现、未经协议验证或未经实机验证的功能都必须明确标记，不得宣称已完成。
- 不要删除 codex-code-mode-host 等 CLI 必需 helper；不要把普通 Linux/glibc 二进制当成 Android/Bionic 可执行文件。

## 提交和构建

- 先在独立分支修改，核对 diff 和 Actions 结果，再考虑合并；未经明确授权不要直接修改 main 或合并 PR。
- 构建失败时先读取完整失败日志，定位实际失败步骤；不要凭猜测更换构建工具或删依赖。
- UI-only APK 不等于含完整私有运行环境的 APK；Runtime bootstrap 成功也不等于 Codex Agent、MCP 和 PTY 已通过实机测试。
- 提交说明应使用中文或清晰的英文，描述真实改动和未完成项。

## Android UI / UX 设计约束

- 开发聊天页面前必须先阅读 `docs/ANDROID-UI-UX-DESIGN.md`；优先采用聊天内容为主、会话导航抽屉、集中式输入工具菜单和固定底部 Composer 的移动交互。
- 不要把全部消息、命令输出和工具事件都渲染成相同的 Card。按用户消息、Agent 正文、命令执行、MCP 调用、文件变更和审批请求分别设计展示组件。
- 不要把模型、推理强度、项目目录、Agent 状态和会话操作全部长期堆在聊天顶部；常用信息精简显示，低频配置按需打开。
- 追加指令和停止任务必须有清楚、不同的操作入口；审批必须保持突出，不能为了视觉简洁而隐藏。
- 输入区工具菜单只显示已经接通的功能；未实现的麦克风、附件、文件选择或工具入口不能做成无动作的装饰按钮。
- 参考 ChatGPT、OpenCode、Operit 和 RikkaHub 时只借鉴交互与架构原则；不复制品牌资源、专有图标、受版权保护的文案或未经许可证审查的代码。
- UI 调整后必须执行 Android Debug 构建，并尽可能增加截图/交互测试；构建成功只能说明编译和打包通过，不等于真机体验验收完成。
