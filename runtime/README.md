# ARM64 私有运行环境

## 设备验证基线

截至 2026-11-14，用户已在 Android 14 / ARM64 设备上确认：安装 APK 后，应用内置的 ARM64 bootstrap 可以成功安装到应用私有目录。这个验证只证明基础 Bionic/ARM64 bootstrap 可离线安装，不代表当前分支的 Codex CLI、PTY、App Server、第三方 API 或 MCP 已通过端到端测试。

应用私有目录：

- `files/runtime/prefix`：ARM64 用户空间前缀。
- `files/runtime/tmp`：运行时临时目录。
- `files/home`：独立 HOME。
- `files/codex`：独立 CODEX_HOME。

## 新构建策略：小底座 + 按需扩展

运行环境拆成三层，避免把代码开发、逆向工具链全部塞进初始 APK：

1. **基础 Codex 环境（APK 内置）**：Bash、核心文件/文本工具、Git/HTTPS、OpenSSL/CA、termux-exec、ripgrep，以及官方 Codex CLI 和 `codex-code-mode-host`。
2. **开发工具扩展（按需）**：gawk、jq、进程工具、Python、Node.js、Rust、CMake 等，不进入初始 bootstrap。
3. **逆向调试扩展（按需）**：APKTool、Binutils、Dex2jar、GDB、radare2、apksigner、strace、ltrace 等；只有在 ABI、Android 版本、许可证和工具兼容性检查通过后才逐项开放。

`runtime/private-runtime-config.json` 中的 `optionalPackageProfiles` 目前只是经过上游包目录核对的扩展目录，**不是已经完成的在线安装器**；当前 bootstrap 只包含 `packages` 中的基础包。后续需要实现扩展包索引、依赖安装、签名/哈希校验、安装回滚和 Root 权限审批，才能把这些扩展开放给用户。

## 为什么不直接下载 Termux 官方 ARM64 .deb

不能把 Termux 官方仓库里按 `com.termux` 前缀构建的二进制包直接解压到本应用的 `com.codex.zhou` 前缀。Termux 的构建脚本会把安装前缀写入脚本、配置和二进制路径；上游 `termux_download_deb_pac.sh` 也会在仓库 applicationId 与目标 applicationId 不同时拒绝下载依赖包。直接搬包会留下指向 `/data/data/com.termux/files/usr` 的错误路径。

因此采用：

- 第一次构建：使用锁定的 Termux Packages 配方，按 `com.codex.zhou` 和本项目私有 PREFIX 构建需要的 Bionic/ARM64 包。
- 后续构建：缓存同一 Termux commit、同一私有前缀、同一 package profile 的 `.deb`、已安装 PREFIX、`.built-packages` 版本标记、源码目录、Cargo registry、Rustup toolchain 和 rusty_v8 构建结果。
- Termux 的 `termux_step_start_build` 会按版本标记跳过已构建包；缓存键一旦因来源锁、配置或准备脚本变化而改变，就不跨配置复用旧标记。
- Codex 0.169.0 的上游 Termux 配方必须从源码构建 `codex` 和 `codex-code-mode-host`，并从匹配 Cargo.lock 的 rusty_v8 版本构建 V8；不能用 Linux/glibc 二进制代替 Android/Bionic 版本。

**预编译优先的边界**：可复用的是本项目私有前缀下先前构建并经版本锁定的本地包，不是未经重定位的官方 Termux .deb。后续若建立独立 `com.codex.zhou` 包仓库，再考虑从可信预编译仓库下载；在此之前不搬运其他 applicationId 的包。

## 构建机与容器优化

Docker builder 直接使用锁定的 Termux Packages commit 自带的 `scripts/setup-ubuntu.sh` 完整主机依赖清单，不再手工猜测并删减一份精简清单。上游包配方可能调用 `makeinfo`、`gtkdocize` 等工具；漏掉它们会让基础包在运行到一半时失败。完整主机工具只安装在一次性的 Docker 构建镜像里，不会进入 APK 内置的 ARM64 bootstrap，也不会增加手机端运行环境体积。

CI 资源策略：

- 单包/单任务编译，Rust Cargo jobs = 1，禁用增量编译和 Thin LTO，控制编译峰值。以实际日志为准：最近一次失败运行的 Runner 报告约 15 GiB 内存，容器峰值约 4.3 GiB，OOM 计数为 0；磁盘峰值也远未接近耗尽。
- Cargo、Rustup、Termux source/build/cache、安装 PREFIX 和 `/data/data/.built-packages` 版本标记都放在 GitHub Actions 缓存卷中。缓存按来源锁、包配置和补丁脚本隔离；每次运行使用新的快照键，并在成功或失败后保存已完成包标记与部分编译状态，下一轮从最近快照接着做。
- Docker builder 只构建一次，并使用 BuildKit GHA cache 保存镜像层，避免重复构建后又覆盖带缓存的镜像。
- 每 2 分钟采集宿主机/容器磁盘、内存、Docker 使用量和 cgroup OOM 事件；构建结束输出明确退出码和最后日志。
- 私有 Runtime 工作流不因纯 UI 文件变更重跑；运行环境任务不因新提交直接取消正在执行的昂贵编译。

## 离线安装与完整性

APK 内置的 bootstrap ZIP 和来源清单由安装器处理：

- 核对 applicationId、ABI、运行时前缀和 SHA-256。
- 限制 ZIP 文件数量与解压总量，拒绝绝对路径和目录穿越。
- 单独解析并验证符号链接，拒绝指向应用私有前缀以外的任意数据目录。
- 在独立 staging 目录解包，设置私有文件权限，然后原子切换 PREFIX。
- 写入安装状态标记；失败时尝试回滚旧 PREFIX。

安装过程不需要 Root、Termux 或网络连接。Root 仅是后续设备调试的可选权限，与基础运行环境安装无关。

## 当前交付边界

基础运行环境构建通过、bootstrap ZIP 上传、完整 APK 二次打包是三个独立验收结果。只有对应 GitHub Actions 成功且产物列表可验证，才能称为成功。UI-only APK 不包含 bootstrap；bootstrap-only artifact 也不是完整 APK。
