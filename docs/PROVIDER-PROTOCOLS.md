# 模型服务商与协议

配置字段：名称、协议、API 基础地址、密钥引用、模型 ID、自定义请求头、启用状态和能力声明。API 密钥只保存到加密 SecretStore，不写入普通配置文件。

## 两种模式
- **Codex 智能体模式**：使用 Codex App Server。自定义 Codex 服务商需满足 Responses API 兼容性；`wire_api = "responses"` 不会把 Chat Completions 服务自动转换为 Responses。
- **通用模型聊天模式**：由独立适配器支持 OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Google Gemini。

每种协议分别处理鉴权、请求体、流式事件、工具调用格式、错误码和上下文限制。连通性测试不得显示密钥；不兼容协议需明确提示。
