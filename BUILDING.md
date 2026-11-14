# 构建说明

## 本地环境
- JDK 17
- Android SDK Platform 35
- Android Build Tools compatible with AGP 8.7.3
- Gradle 8.10.2

## 构建
```bash
gradle --no-daemon :app:assembleDebug
```

Debug APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

当前 CI 只验证 Compose UI 工程骨架能否构建。它不会生成 ARM64 Linux Runtime，也不会证明 Codex CLI、PTY、App Server 或 MCP 已可运行。
