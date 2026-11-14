# Codex 中文版 Android

独立 Android ARM64 Codex 中文客户端工程。目标是将 Codex 智能体、终端、通用聊天、模型服务商和 MCP 工具整合到一个不依赖 Termux 的应用中。

> **当前状态：基础 ARM64 私有运行环境已构建，并已在 Android 14 / ARM64 实机验证安装成功。** APK 已内置离线 bootstrap，应用可以在自己的私有目录中安装基础 Bionic 工具链；这不代表完整 Codex 客户端已经完成。真正的 PTY 终端、Codex CLI 启动、Codex App Server 聊天、通用模型协议适配器和 MCP 执行器仍需继续集成。

- 应用名：Codex 中文版
- Android applicationId：`com.codex.zhou`
- ABI：`arm64-v8a`
- 最低 Android：API 24；优先测试 Android 14
- 技术栈：Kotlin + Jetpack Compose
- 私有目录：独立 `PREFIX`、`HOME`、`CODEX_HOME`，不访问 Termux 私有目录，不覆盖原有 `~/.codex`

## 构建 UI 骨架

需要 JDK 17、Android SDK 35、Gradle 8.10.2：

```bash
gradle --no-daemon :app:assembleDebug
```

GitHub Actions 会构建 Debug APK 并上传为 Actions Artifact。

## 文档

- [产品需求](docs/PRODUCT-REQUIREMENTS.md)
- [架构](docs/ARCHITECTURE.md)
- [模型服务商](docs/PROVIDER-PROTOCOLS.md)
- [MCP](docs/MCP-CONFIG.md)
- [安全设计](docs/SECURITY.md)

## 原则

1. 保留现有 Termux、原版 Codex、旧版汉化 Codex 和用户数据。
2. 不要求 Root；默认不启动本地网络监听。
3. API 密钥不得写入日志或提交到仓库。
4. 工具调用和有副作用的操作默认需要用户审批。
5. 移植第三方代码、文案或资源前必须审查许可证。
