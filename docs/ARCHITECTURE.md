# 总体架构

分层：Compose UI → 应用协调层 → 协议层 → 私有 Runtime/PTY → 存储与密钥。

关键模块：
- RuntimePaths：唯一管理应用私有 PREFIX、HOME、CODEX_HOME 和 TMPDIR。
- RuntimeManager：私有目录初始化、ABI 检查、布局标记与运行时状态检查。
- RuntimeBootstrapBuilder：CI 从锁定的 Termux package recipes 按本应用 ID 和自定义 prefix 重建 Bionic/ARM64 软件包；不搬运用户现有 Termux 安装。
- RuntimeBootstrapInstaller：校验 APK 内置 SHA-256 清单、隔离解包、恢复安全符号链接、设置执行权限和原子切换。
- PtySessionManager：PTY 创建、恢复、关闭（待实现）。
- CodexAppServerClient：JSON-RPC 请求、通知和事件流（待实现）。
- ChatProviderAdapter：Responses、Chat Completions、Anthropic、Gemini 适配（待实现）。
- McpConnectionManager：STDIO/HTTP 连接与工具目录（待实现）。
- ApprovalController：命令、工具和文件写入审批（待实现）。
- SecretStore：Android Keystore 密钥封装（待实现）。

目录位于应用私有存储：files/runtime/prefix、files/runtime/tmp、files/home、files/codex。路径创建前会进行规范化边界检查，不提供 Termux 路径回退，也不访问 /data/data/com.termux/files/usr。

当前实现包含私有目录基础层、ARM64 运行时源码构建流水线和 APK 内置 bootstrap 安装器；基础工具链已在 Android 14 / ARM64 实机完成安装验证。接下来进入 PTY、Codex CLI、App Server、通用协议和 MCP 连接器集成。

## 开源项目参考与边界

本项目以 Codex CLI / Codex App Server 作为智能体执行核心，参考其他开源 Android AI 客户端的产品设计，不把其他项目的协议或运行时直接替换成 Codex：

- **RikkaHub**：参考模型服务商管理、自定义 API 地址/模型/请求头、配置持久化和 MCP 入口的组织方式。
- **Operit**：参考智能体工作区、工具调用进度、工具结果卡片、逐工具审批以及 MCP / Skills 的交互方式。
- **OpenCode**：参考会话生命周期、事件流归并、工具调用状态和终端/PTY 的体验；会话与执行协议仍以 Codex App Server 为准。
- **Termux**：参考 Android/Bionic 私有运行环境、PTY 和终端模拟器的集成方式；不复用用户现有 Termux 的私有目录。

只借鉴经过核对的功能与架构思路。移植任何源码、资源或文案前，必须单独检查上游许可证和依赖许可证；不能因为项目开源就默认可以直接复制。

聊天与终端共享应用私有 CODEX_HOME；需处理并发写入与会话恢复。
