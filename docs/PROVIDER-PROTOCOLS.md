# 模型服务商与协议

配置字段：名称、协议、API 基础地址、模型 ID、自定义请求头和默认服务商。`ProviderRepository` 已建立非敏感配置存储；`SecretStore` 使用 Android Keystore 中不可导出的 AES-256-GCM 密钥加密 API Key 与自定义请求头值，密文单独保存在应用私有 SharedPreferences。普通服务商配置 JSON 不写入明文密钥。

当前已完成服务商列表、添加/编辑/删除、设为默认的中文界面，以及配置数据层和加密存储层。尚未完成 Codex `config.toml` / 凭据文件适配、网络连接测试和真实中转站兼容性验证。

## 两种模式
- **Codex 智能体模式**：使用 Codex App Server。自定义 Codex 服务商需满足 Responses API 兼容性；`wire_api = "responses"` 不会把 Chat Completions 服务自动转换为 Responses。
- **通用模型聊天模式**：由独立适配器支持 OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Google Gemini。

每种协议分别处理鉴权、请求体、流式事件、工具调用格式、错误码和上下文限制。连通性测试不得显示密钥；不兼容协议需明确提示。

## 安全边界
- 服务商地址默认要求 HTTPS；仅 localhost / 127.0.0.1 回环地址允许 HTTP。
- 自定义 Header 的值与 API Key 一样视为敏感信息，必须和 API Key 一起加密保存。
- 日志、崩溃报告和错误提示不得输出请求头、密钥或完整认证配置。
- 删除服务商时同步删除密文和对应 Keystore key；修改服务商配置时不得把旧密钥复制到普通 JSON。
- 第三方 Responses 兼容性必须实测流式事件、工具调用和错误处理；单纯 HTTP 200 不代表 Codex Agent 可用。