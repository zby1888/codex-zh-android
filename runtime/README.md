# ARM64 Runtime

## 当前设备阶段

**实机验证通过**

截至 2026-11-14，用户已在 Android 14 / ARM64 设备上确认：安装 APK 后，应用内置的 ARM64 bootstrap 可以成功安装到应用私有目录。验证范围是基础 Bionic/ARM64 运行环境的离线安装，不代表 Codex CLI、PTY、App Server 聊天或 MCP 已经可用。

应用私有目录：

- `files/runtime/prefix`：ARM64 用户空间前缀。
- `files/runtime/tmp`：运行时临时目录。
- `files/home`：独立 HOME。
- `files/codex`：独立 CODEX_HOME。

## 构建策略

不直接解压其他 Termux 安装中的软件包。Termux 软件包中的路径通常在构建时写入，不能简单移动到另一个前缀。我们使用仓库锁定的 `termux/termux-packages` commit，在 CI 中按本应用 applicationId 和自定义前缀重新构建 ARM64/Bionic 软件包。

运行时构建配置见 `private-runtime-config.json`。Debug 和 Release 使用完全相同的 applicationId：`com.codex.zhou`。因此两种构建使用相同的私有运行时路径与 bootstrap，不再需要为 Debug/Release 分别编译不同包名的运行时。

首批目标包：Bash、Coreutils、Git、OpenSSL、CA certificates、libcurl、jq，以及常用文本/文件工具。包均为 Android Bionic/ARM64 构建，不是 glibc Linux rootfs。

为保持运行环境精简，bootstrap 不安装 `termux-tools`、`termux-am` 和 `termux-am-socket`。它们主要提供 Termux 应用启动/系统集成命令；`termux-am` 还会额外触发 Gradle Android APK 构建，而 Codex CLI 本身不依赖这些功能。Bash 配置中的 `command-not-found` 是可选功能，只有对应脚本存在时才会启用。

## 离线安装

APK 内置 bootstrap ZIP 和 JSON 来源清单。安装器会：
- 核对 applicationId、ABI、运行时前缀和 SHA-256。
- 限制 ZIP 文件数量与解压总量，拒绝绝对路径和目录穿越。
- 单独解析并验证符号链接，拒绝指向应用私有前缀以外的任意数据目录。
- 在独立 staging 目录解包，设置私有文件权限，然后原子切换 prefix。
- 写入安装状态标记；失败时尝试回滚旧 prefix。

安装过程不需要 Root、Termux 或网络连接。当前只实现首批基础工具链安装，PTY、Codex CLI、App Server、Node/Python 和 MCP 连接器仍需后续集成。
