# Codex 中文版 Android

独立 Android ARM64 Codex 中文客户端工程。目标不是简单套壳，而是**尽可能把 Codex CLI 的主要能力搬进 Android 原生图形界面**：由官方 Codex CLI 承担 Agent 执行，Android App 负责会话、项目、权限审批、工具、配置和结果展示；同时提供真正独立的交互式终端。

## 项目定位（长期约束）

**请所有后续开发者、自动化 Agent 和 Codex CLI 在开始修改前先阅读 [AGENTS.md](AGENTS.md)、本节及 [架构约束](docs/ARCHITECTURE.md)。**

- **Agent 核心**：优先使用固定版本的官方 Codex CLI 和经过版本核验的 App Server / JSON-RPC 接口。不能只解析 TUI 输出或把终端文字拼成聊天界面。
- **Android 原生界面**：尽可能覆盖 CLI 的会话管理、历史记录、模型选择、推理设置、任务进度、命令执行、文件修改与 Diff、权限审批、代码审查、MCP、Skills 和配置管理。功能清单见 [CLI 功能对照表](docs/CLI-FEATURE-PARITY.md)。
- **双执行通道**：Agent 使用独立 App Server 通道；终端使用真正的 PTY。二者共享应用私有项目目录和必要配置，但不能混用输入输出管道。
- **独立运行环境**：APK 使用自己的 Android/Bionic ARM64 私有运行时、PREFIX、HOME 和 CODEX_HOME，不依赖或修改用户原有 Termux。
- **第三方服务商**：Codex Agent 模式必须验证中转站对 Codex 所需 Responses API、流式事件和工具调用的兼容性；其他协议走独立通用聊天适配器，不得混为一谈。
- **中文由 App 自己负责**：Android UI、提示、设置和错误说明均由本项目维护中文文案。汉化版 CLI 可以作为以后可选方案，但**绝不能成为 APK 主线构建的强制依赖**。
- **诚实标记完成度**：UI 骨架、Runtime、CLI/App Server、完整 APK 和实机功能测试分开验收；没有通过的能力标记为未实现或待验证，不能仅凭 UI 构建成功宣称完成。

> **当前状态：基础 ARM64 私有运行环境曾在 Android 14 / ARM64 实机验证安装成功。** 最近的官方 CLI 解耦和聊天界面接线仍在独立分支验证。聊天页已有 App Server 连接、创建/恢复/分支会话、最近与已归档会话列表、重命名/归档/删除、历史消息、模型和推理强度选择、发送消息、流式文本、命令输出、文件变更和初步审批卡片代码；最新一轮模型与会话操作代码仍在 UI 构建验证，且尚未在包含当前 Codex CLI 的完整运行环境中完成端到端验证。真正 PTY、多会话历史持久化、通用协议适配器、MCP 状态页已接入并在 UI-only Build #76/#77 编译通过；当前分支还增加了 STDIO / Streamable HTTP 添加表单、删除入口、Bearer Token/STDIO 环境变量的 Keystore 加密存储与注入、MCP OAuth 浏览器授权入口；OAuth 和项目工作区最新改动正在等待 UI 构建验证。认证凭据已加入 Android Keystore 加密存储并在 Codex 子进程启动时注入；新增密钥需要显式重启 App Server。 最新待验证提交还加入了 `McpServerSecretRepository`、子进程环境注入、重启后恢复当前会话、`turn/diff/updated` 实时 Diff 时间线事件，以及 `item/tool/requestUserInput` 动态问答卡片。OAuth、MCP 工具调用审批、本地 STDIO 进程调试和完整 APK 实机验收仍待完成。

- 应用名：Codex 中文版
- Android applicationId：`com.codex.zhou`
- ABI：`arm64-v8a`
- 最低 Android：API 24；优先测试 Android 14
- 技术栈：Kotlin + Jetpack Compose
- 私有目录：独立 `PREFIX`、`HOME`、`CODEX_HOME`，不访问 Termux 私有目录，不覆盖原有 `~/.codex`

## 开源项目参考

除 Codex 官方 CLI / App Server 外，开发时明确参考以下开源项目的成熟设计：

- **OpenCode**（[anomalyco/opencode](https://github.com/anomalyco/opencode)）：参考会话生命周期与历史、事件驱动的消息/工具状态、Agent 与子 Agent、权限策略、MCP、Skills、自定义命令、项目工作区和 Git worktree 等设计。执行协议仍以本项目锁定版本的 Codex App Server 为准，不直接把 OpenCode runtime 当成 Codex runtime。
- **Operit**（[AAswordman/Operit](https://github.com/AAswordman/Operit)）：参考 Android 优先的 Agent 工作台、任务进度、附件与工作区上下文、工具执行卡片、Android 文件/终端/浏览器能力、工作流、ToolPkg、MCP 和 Skills 管理。只借鉴适合本项目的交互与模块设计，不把 Operit 的 Agent 执行引擎替换 Codex。
- **RikkaHub**：参考多服务商、多模型、自定义端点和请求头、配置管理及 MCP 入口；具体实现以核验后的上游版本和许可证为准。

参考项目是设计和交互参考，不代表可以直接复制代码。任何代码、资源、协议适配或文案移植前，必须检查许可证、依赖许可证、来源和兼容性，并保留必要的版权声明。

## 构建 UI 骨架

需要 JDK 17、Android SDK 35、Gradle 8.10.2：

```bash
gradle --no-daemon :app:assembleDebug
```

GitHub Actions 会构建 Debug APK 并上传为 Actions Artifact。

## 文档

- [产品需求](docs/PRODUCT-REQUIREMENTS.md)
- [架构](docs/ARCHITECTURE.md)
- [Codex App Server 接入设计](docs/APP-SERVER-INTEGRATION.md)
- [模型服务商](docs/PROVIDER-PROTOCOLS.md)
- [MCP](docs/MCP-CONFIG.md)
- [Codex CLI 功能对照表](docs/CLI-FEATURE-PARITY.md)
- [安全设计](docs/SECURITY.md)

## 原则

1. 保留现有 Termux、原版 Codex、旧版汉化 Codex 和用户数据。
2. 不要求 Root；默认不启动本地网络监听。
3. API 密钥不得写入日志或提交到仓库。
4. 工具调用和有副作用的操作默认需要用户审批。
5. 移植第三方代码、文案或资源前必须审查许可证。
