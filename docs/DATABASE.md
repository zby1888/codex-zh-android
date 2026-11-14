# 数据模型

SQLite 初始迁移位于 `database/migrations/001_initial.sql`。

核心实体包括模型服务商配置、MCP 服务、逐工具授权策略、聊天会话索引和审批审计事件。API 密钥仅保存 SecretStore 引用。Codex 原生会话文件由 Codex 引擎管理，数据库只保存必要索引，不复制敏感会话内容。
