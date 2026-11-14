# 产品需求

## 定位
独立 Android 客户端，提供终端与聊天双入口，将 Codex 智能体、第三方模型服务商和 MCP 工具纳入统一界面。

## 平台
- Android API 24+，优先验证 Android 14。
- 首期仅 `arm64-v8a`；不要求 Root，不依赖 Termux。
- 首次启动离线初始化应用私有目录和预置运行时。
- 独立 `PREFIX`、`HOME`、`CODEX_HOME`，不触碰外部 Termux 和原有 `~/.codex`。

## 功能
- 终端：PTY、终端模拟器、复制粘贴、快捷键、多会话、进程管理。
- 聊天：Codex App Server JSON-RPC、通用模型聊天、流式输出、停止生成、历史会话。
- 服务商：协议、Base URL、API 密钥、模型 ID、自定义 Header、默认模型、连通性测试。
- MCP：STDIO、Streamable HTTP、Bearer Token/OAuth、工具发现、逐工具审批和调用日志。
- 配置导入：Codex TOML、OpenCode JSON、Operit JSON；RikkaHub 格式待核实具体版本。

## 非目标
- 不修改现有 Termux 安装。
- 不默认提权或启动本地监听。
- 不自动执行高风险命令或有副作用的 MCP 工具。
- 不承诺未预置的第三方 MCP 包离线可用。
