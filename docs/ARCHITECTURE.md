# 总体架构

分层：Compose UI → 应用协调层 → 协议层 → 私有 Runtime/PTY → 存储与密钥。

关键模块：
- RuntimePaths：唯一管理应用私有 PREFIX、HOME、CODEX_HOME 和 TMPDIR。
- RuntimeManager：私有目录初始化、ABI 检查、布局标记与运行时状态检查。
- PtySessionManager：PTY 创建、恢复、关闭（待实现）。
- CodexAppServerClient：JSON-RPC 请求、通知和事件流（待实现）。
- ChatProviderAdapter：Responses、Chat Completions、Anthropic、Gemini 适配（待实现）。
- McpConnectionManager：STDIO/HTTP 连接与工具目录（待实现）。
- ApprovalController：命令、工具和文件写入审批（待实现）。
- SecretStore：Android Keystore 密钥封装（待实现）。

目录位于应用私有存储：files/runtime/prefix、files/runtime/tmp、files/home、files/codex。路径创建前会进行规范化边界检查，不提供 Termux 路径回退，也不访问 /data/data/com.termux/files/usr。

当前实现新增了私有目录基础层，但**尚未安装 Linux 用户空间**。Bash、Coreutils、动态库、PTY、Codex CLI、App Server、通用协议和 MCP 连接器仍未集成。

聊天与终端共享应用私有 CODEX_HOME；需处理并发写入与会话恢复。
