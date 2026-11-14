# 总体架构

分层：Compose UI → 应用协调层 → 协议层 → 私有 Runtime/PTY → 存储与密钥。

关键模块：
- RuntimeManager：私有运行时初始化、升级、校验。
- PtySessionManager：PTY 创建、恢复、关闭。
- CodexAppServerClient：JSON-RPC 请求、通知和事件流。
- ChatProviderAdapter：Responses、Chat Completions、Anthropic、Gemini 适配。
- McpConnectionManager：STDIO/HTTP 连接与工具目录。
- ApprovalController：命令、工具和文件写入审批。
- SecretStore：Android Keystore 密钥封装。

目录位于应用私有存储，例如 `files/runtime`、`files/home`、`files/codex`。不得硬编码访问 `/data/data/com.termux/files/usr`。

聊天与终端共享应用私有 `CODEX_HOME`；需处理并发写入与会话恢复。

**当前实现仅为 Compose UI 骨架。** Runtime、PTY、App Server、通用协议和 MCP 连接器均未集成。
