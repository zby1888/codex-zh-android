# ARM64 Runtime

## 当前设备阶段

用户已在 Android 14 / ARM64 设备上成功创建应用私有目录。目录布局为：

- `files/runtime/prefix`：ARM64 用户空间前缀。
- `files/runtime/tmp`：运行时临时目录。
- `files/home`：独立 HOME。
- `files/codex`：独立 CODEX_HOME。

## 构建策略

不直接解压其他 Termux 安装中的软件包。Termux 软件包中的路径通常在构建时写入，不能简单移动到另一个前缀。我们使用仓库锁定的 `termux/termux-packages` commit，在 CI 中按 Codex 中文版的 Android applicationId 和自定义前缀重新构建 ARM64/Bionic 软件包。

运行时构建配置见 `private-runtime-config.json`。当前 Debug applicationId 为 `io.github.zby1888.codexzh.debug`，构建时的 PREFIX 必须与应用的 `files/runtime/prefix` 完全一致。正式 Release 版本必须单独按 `io.github.zby1888.codexzh` 重新构建运行时，不能混用 Debug bootstrap。

首批目标包：Bash、Coreutils、Git、OpenSSL、CA certificates、libcurl、jq、termux-exec，以及常用文本/文件工具。包均为 Android Bionic/ARM64 构建，不是 glibc Linux rootfs。

## 构建和验证

- `.github/workflows/build-private-runtime.yml`：从锁定源码构建私有 ARM64 bootstrap。
- `scripts/prepare-private-runtime-source.py`：为本应用 applicationId、HOME 和 PREFIX 定制构建参数。
- `scripts/validate-private-runtime-bootstrap.py`：检查关键命令、链接路径和 ZIP 路径安全，并输出 SHA-256 来源清单。
- Actions artifact：`codex-zh-private-runtime-arm64`。

当前提交只增加了运行时构建流水线；**尚未把 bootstrap 集成进 APK，也尚未在手机上安装 Bash/Git**。下一步是在 APK 中实现校验、隔离解包、符号链接恢复、原子切换和运行时自检。
