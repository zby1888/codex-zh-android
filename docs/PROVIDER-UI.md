# 服务商管理界面

## 已实现
- 服务商列表与默认服务商切换。
- 添加、编辑和删除服务商。
- 协议类型：Codex Agent Responses、OpenAI Chat Completions、Anthropic Messages、Google Gemini。
- 配置名称、API 基础地址、模型 ID、API Key 和自定义请求头。
- 编辑时不回显已保存的 API Key；密钥输入留空表示保留原密钥。
- 密钥和自定义请求头值由 Android Keystore AES-256-GCM 加密保存。
- HTTPS 为默认要求；仅 localhost / 127.0.0.1 允许 HTTP。

## 尚未实现
- 连接测试和协议兼容性探测。
- 将 Codex Agent 服务商写入私有 CODEX_HOME 的 Codex 配置。
- 根据实际服务商返回的模型动态填充模型选择器。
- Chat Completions、Anthropic 和 Gemini 的通用聊天网络适配器。
- Codex Agent Responses 中转站的端到端测试。

## 重要区分
添加服务商配置不等于已经可以连接。Codex Agent 模式必须确认中转站兼容 Responses API、流式事件和工具调用；其他协议不能直接用于 Codex Agent。