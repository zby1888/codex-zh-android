# ARM64 Runtime（待实现）

目标是在应用私有目录初始化 ARM64 Linux 用户空间，不复用 Termux APK 的固定路径，也不要求安装 Termux。

计划组件：Bash、Coreutils、Git、OpenSSL、CA 证书、libc++、tar、gzip、grep、sed；按需提供 Node.js、Python/uv 以运行常见本地 MCP 服务。

**当前尚未构建或打包 Runtime。** 来源锁定检查不等于 Runtime 构建。后续还需完成私有 PREFIX 重定位、依赖闭包、编译、许可证归档、哈希校验和 Android 14 实机测试。
