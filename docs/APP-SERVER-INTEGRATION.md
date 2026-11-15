# Codex App Server 接入设计

## 协议版本依据

- Codex 源码标签：`rust-v0.169.0`。
- 该标签解析后的源码提交：`d32063f96c2dff1dc96af839b3952abb6acfeda4`。
- 该版本的协议定义位于 `codex-rs/app-server-protocol/schema/json/v2/`；初始化参数位于 `schema/json/v1/InitializeParams.json`。
- Android 侧请求字段必须以这份固定版本 schema 为准。不要直接把最新文档中的新字段复制到旧版本客户端。

## 当前实现状态

- 已新增 `CodexAppServerClient`：在应用私有运行时启动 `codex app-server --listen stdio://`，通过 JSONL 读写 JSON-RPC 消息。
- 已实现请求 ID 与响应关联、初始化握手、通知分发、服务端请求回调、服务端请求响应/拒绝、进程退出处理和连接关闭。
- 已新增 `CodexAgentController`，封装 model/list、thread/start、thread/resume、thread/list、thread/turns/list、thread/items/list、thread/fork、thread/setName、thread/archive、thread/unarchive、thread/delete、thread/compact/start、review/start、goal get/set/clear、turn/start、turn/steer 和 turn/interrupt。
- Agent 协议进程与交互式 PTY 终端分离；该类不得复用终端的 stdin/stdout。
- 已接入基础聊天 UI：连接 `CodexAgentRuntimeFactory`、创建/列出/恢复会话、通过 `thread/items/list` 还原历史、发送用户消息、处理 `turn/start`、追加指令和中断轮次；UI 会接收 `item/agentMessage/delta`、命令输出增量、item 开始/完成、文件变更和 turn 完成事件，并显示对应卡片。已增加命令执行与文件修改审批卡片，未知服务端请求不会自动放行。
- **尚未完成实机端到端验证**：当前代码构建不等于 Codex 进程已在 Android 私有运行时成功启动，也不等于第三方 Responses 中转站已经兼容。
- 会话列表、最近/已归档切换、恢复、分支、重命名、归档/取消归档、删除的 UI 已接入；模型目录通过 `model/list` 动态读取，模型与推理强度会传入 `thread/start` / `turn/start`。这些新增交互正在新一轮 UI 构建验证；历史分页、断线自动恢复、本地索引/收藏和跨进程状态恢复尚待实现。MCP 协议操作层已建立，但配置编辑、完整审批交互和本地 STDIO MCP 运行时仍待实现。服务商管理和密钥注入底层已实现，但连接测试与真实中转站兼容性尚未验证。真正 PTY 终端仍待实现。

## 协议握手

1. 启动私有 PREFIX/bin/codex，参数为 `app-server --listen stdio://`。
2. 发送一次 `initialize` 请求，包含本客户端名称、版本等信息。
3. 收到 initialize 响应后发送 `initialized` 通知。
4. 握手完成后才允许调用 thread、turn、model、config 等方法。
5. 持续读取 stdout：带 id 的响应交给对应请求；带 method 且无 id 的消息作为通知；带 method 和 id 的消息作为服务端请求（例如审批）。

## 安全与稳定性

- stdout 是协议专用通道，任何调试输出都不能写入 stdout；stderr 只消费，不把原始内容直接展示或写入普通日志，避免泄露路径和密钥。
- 所有请求有超时；进程退出或输出流关闭时，必须结束等待中的请求。
- 服务端主动请求必须由 UI/审批层明确答复，不能自动默认允许。
- 运行时环境变量从 `RuntimePaths.environment()` 注入；API 密钥不得拼进命令行参数。
- Android 最低版本为 API 24，进程存活检测和关闭流程不能直接调用高于 minSdk 的 API。
- Codex App Server 的公开文档当前标记其生产适用性有限/实验性；具体方法和字段必须以锁定版本生成的 schema 和实际运行测试为准。通信模块保持可替换，不把未验证方法当成稳定承诺。

## 下一步

1. 在 ARM64 私有运行时实际启动该版本 App Server，验证 initialize / initialized 往返。
2. 生成并保存该版本的 JSON Schema，作为 Kotlin 请求/事件解析的版本依据。
3. 已在聊天 UI 接入 model/thread/turn 控制路径、thread/list、resume 和基础 history item hydration；下一步补齐分页、本地会话索引、收藏/搜索和断线恢复。
4. 已把 agentMessage delta、command output、plan delta、reasoning summary、MCP progress、item lifecycle、file change patch update、turn completion 和初步 approval request 映射为 UI 状态；不支持的服务端请求可显式拒绝而不会自动放行。下一步补齐事件去重、历史恢复和权限/用户输入/elicitation 等审批类型。
5. 实现 thread 生命周期持久化、断线重连和进程恢复。
6. 在 Android 14 实机上验证第三方 Responses 兼容中转站；不能以 HTTP 200 代替完整协议兼容测试。