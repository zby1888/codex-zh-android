# ARM64 Runtime 私有目录基础层

当前阶段已实现 Android 应用私有目录布局管理：

- files/runtime/prefix：未来 ARM64 用户空间前缀。
- files/runtime/tmp：运行时临时目录。
- files/home：独立 HOME。
- files/codex：独立 CODEX_HOME。
- 启动时检查设备 ABI；只接受 arm64-v8a。
- 初始化前检查规范化路径，拒绝逃逸出应用私有目录。
- 生成不含密钥的布局标记，供后续版本检查。

**重要：这不是已安装的 Linux Runtime。** 当前不会下载或解包软件包，不会执行 shell 命令，也不会访问 Termux。只有后续打包 Bash、Coreutils、动态库和 Codex CLI，并通过 Android 14 实机验证后，终端才会真正可用。

下一步：确定可重定位的 Android ARM64 软件包来源，构建依赖闭包和许可证清单，再实现安全解包、哈希校验与启动器。
