# 产品需求

## 定位
独立 Android 客户端，目标是尽可能完整地把 Codex CLI 的主要能力搬进 Android 原生图形界面。官方 Codex CLI 负责 Agent 执行；App 负责图形化操作、会话和项目管理、执行过程展示、权限审批、MCP、模型配置和文件变更查看。终端是独立的真实 PTY 入口，不是聊天界面的替代品。

汉化版 CLI 不是 APK 的必需依赖。主线使用固定版本的官方 Codex CLI；Android UI 自己维护完整中文文案。任何自动化 Agent 或后续开发者不得擅自改变这一项目定位。

## 平台
- Android API 24+，优先验证 Android 14。
- 首期仅 arm64-v8a；基础功能不要求 Root，不依赖 Termux；Root 作为用户主动申请的可选能力。
- 首次启动离线初始化应用私有目录和预置运行时。
- 独立 `PREFIX`、`HOME`、`CODEX_HOME`，不触碰外部 Termux 和原有 `~/.codex`。

## 功能
- 终端：普通身份 PTY、可选 Root PTY、终端模拟器、复制粘贴、快捷键、多会话、进程管理。
- 逆向 / 动态调试：优先支持授权范围内的 APK / Manifest / DEX 分析、Logcat、设备和进程诊断；逐步评估 ARM64 GDB / LLDB / Frida 等工具。进程附加、Hook / 注入、系统文件写入等高风险动作必须单独审批。
- 聊天：Codex App Server JSON-RPC、通用模型聊天、流式输出、停止生成、历史会话。
- 服务商：协议、Base URL、API 密钥、模型 ID、自定义 Header、默认模型、连通性测试。
- MCP：STDIO、Streamable HTTP、Bearer Token/OAuth、工具发现、逐工具审批和调用日志。
- 配置导入：Codex TOML、OpenCode JSON、Operit JSON；RikkaHub 格式待核实具体版本。

## 功能完整性原则
- 按 [Codex CLI 功能对照表](CLI-FEATURE-PARITY.md) 逐项盘点、实现和验收，不把“有聊天页面”视为 CLI 能力移植完成。
- 优先接入当前锁定版本公开且经过 Android 实测的 App Server 能力；不稳定或不可用的能力标明限制，并提供终端回退。
- Agent 通道与 PTY 通道独立，二者共享项目目录和必要配置。
- UI、Runtime、CLI/App Server、完整 APK 和实机测试分开验收。

## 非目标
- 不修改现有 Termux 安装。
- 不默认提权或启动本地监听；Root 仅通过系统 Root 管理器明确授权后供独立终端 / 受控工具使用，Codex Agent 本身始终不以 root 运行。
- 不自动执行高风险命令或有副作用的 MCP 工具。
- 逆向、动态调试和 Root 能力仅面向自有或明确授权的设备及软件，不绕过用户授权或隐藏进程操作。
- 不承诺未预置的第三方 MCP 包离线可用。


## Android UI / UX 设计方向

以聊天为主、工具为辅，借鉴成熟 GPT 客户端的移动交互：简洁侧栏、集中式输入工具入口、固定底部输入区、清楚的流式状态和易浏览的历史会话。具体布局、消息样式、工具活动卡片、审批和实施顺序统一遵循 [Android UI / UX 设计规范](ANDROID-UI-UX-DESIGN.md)。不能把每条消息和执行事件都做成同一种大卡片；也不能为了视觉相似而隐藏 Codex 的命令、文件 Diff、项目和权限信息。
