# Android PTY 终端与 Root Shell 架构

## 目标

客户端需要同时支持代码开发终端和设备级调试终端：

- 普通终端运行在应用 UID 下，使用 APK 内置的 ARM64 / Android Bionic 私有运行环境。
- Root 终端是可选独立通道，只有用户主动申请且系统 Root 管理器授权后才运行。
- Codex Agent / App Server 永远保持应用 UID。Root 不能作为 Agent 的默认运行身份。
- Agent、普通 PTY、Root PTY 三条通道必须分离，不能共用 stdin/stdout、进程句柄或权限状态。

## 当前代码状态

首版真实 PTY 已经写入项目，不再是只有运行环境状态页：

- NDK / JNI 创建 PTY master/slave、fork 子进程、创建控制终端、设置 cwd 和窗口大小。
- 普通终端启动私有 PREFIX 下的 Bash；支持读写、resize、退出码和进程组清理。
- libvterm 解析 ANSI / VT 状态，Compose Canvas 绘制字符网格、颜色、属性与光标。
- UI 已提供 Ctrl+C、Ctrl+D、Tab、Esc、方向键和命令输入。
- Kotlin 层使用 Mutex 串行化 native API，并在关闭时等待读取协程退出后再释放 native 句柄。
- 初始画面快照解码失败时，会关闭刚创建的 native PTY，防止遗留子进程。

Root 入口目前已接线：

- RootAccessManager 只在用户点击申请时查找 su，并运行 su -c id -u 确认实际 UID。
- 创建 Root PTY 前再次申请 / 检查授权；未找到 su、拒绝、超时或异常均失败关闭。
- Root PTY 使用同一 NativePtyBridge，但独立启动 su -c 受控脚本，再启动应用私有 Bash。
- JNI 以 argv 数组传递 Shell 程序和参数；不把字符串拆成 argv，也不把用户命令拼入 Root 启动脚本。
- Root 启动链移除 LD_PRELOAD，避免把 Termux exec helper 注入 root 进程。
- 当前只接好“独立 Root 终端”，还没有 Codex Agent 通过工具调用 Root 操作的能力。

仍未通过真机验收：普通 PTY / Root PTY 的 Magisk 与 KernelSU 兼容、完整 IME、滚动缓冲、多标签、vim/less/top、异常退出与孤儿进程清理、横竖屏 resize、SELinux 行为。CI 编译通过不能替代这些测试。

## Termux Codex 运行时构建参考

Runtime 采用固定的 Termux package recipe，不复制用户手机上的 Termux 安装。项目锁定的 Termux Packages commit 为：

- 仓库：termux/termux-packages
- Commit：1d8cc3f98c8a685f3a1475becbb0fe7d7dcda7fd
- Codex 包版本：0.169.0
- Codex 源码 tag：openai/codex rust-v0.169.0

该固定 recipe 已核对包含以下关键构建步骤：

1. 用 Termux 的 Rust toolchain，而不是上游过旧 rust-toolchain pin，以支持 Android 文件锁相关 API。
2. 对 cc crate 的 CFLAGS 处理、构建 warning 和 rusty_v8 target suffix 做 Android 构建补丁。
3. 根据 Codex Cargo.lock 中锁定的 V8 crate 版本拉取匹配 rusty_v8 与子模块。
4. 使用 Android NDK、clang sysroot、BINDGEN target 和 GN 参数从源码编译 V8。
5. 为 AArch64 链接补充 libgcc archive，规避 Android linker 的 __clear_cache 未定义符号问题。
6. 分别构建 codex-cli 的 codex 二进制和 codex-code-mode-host，并一起安装到私有 PREFIX。

不要删掉 codex-code-mode-host；不要把 Linux / glibc 二进制当作 Android / Bionic 程序使用。每次更新 Codex 或 Termux 包构建脚本时，要重新比较固定 commit 中的 packages/codex/build.sh，并更新 sources.lock 与可复现性记录。

## 终端与 VT 实现边界

Termux 的 TerminalSession / TerminalEmulator / TerminalView 分层值得研究，但 Termux Terminal View / Emulator 属于 GPL-3.0 系列。Operit Android 仓库标注 LGPL-3.0-only，OperitTerminal 标注 GPLv3。当前项目没有因为开源就直接复制这些实现；移植源码、AAR、JNI 文件或资源前必须完成整体许可证兼容审查。

当前路线：

- PTY / 进程层：自维护的 NDK JNI bridge，负责 PTY、进程、信号、resize 和回收。
- VT 状态层：固定版本 libvterm（MIT）解析控制序列。
- Android 显示层：Compose / Canvas 自行绘制网格、颜色、属性和光标。
- 终端输入层：继续补齐软键盘组合输入、IME、选区、复制粘贴、快捷键和滚动。

libvterm 仅是 VT parser，不负责创建 PTY，也不是现成 Android TerminalView。其来源仓库为 neovim/libvterm 的只读镜像；升级前要复核固定 commit、LICENSE、NOTICE、构建脚本和生成文件。

参考链接：
- Termux App：https://github.com/termux/termux-app
- Termux Codex recipe：https://github.com/termux/termux-packages/blob/1d8cc3f98c8a685f3a1475becbb0fe7d7dcda7fd/packages/codex/build.sh
- Operit：https://github.com/AAswordman/Operit
- OpenCode：https://github.com/anomalyco/opencode
- libvterm：https://github.com/neovim/libvterm

## 进程身份与启动方式

### 普通 PTY

- executable 为应用私有 PREFIX 下的 Bash。
- argv 独立传递，不通过 shell 解析。
- HOME、PREFIX、CODEX_HOME、TMPDIR、PATH、TERM 和 PWD 指向应用私有目录和当前项目。
- 普通终端不访问用户现有 Termux 私有目录。

### Root PTY

- 只有 RootAccessManager 已验证且用户开启“本次终端使用 Root 身份”后才能启动。
- executable 为已检测到的 su 绝对路径；argv 为 su、-c 和应用生成的固定启动脚本。
- 脚本先切换到已校验的项目目录，再导出私有 PREFIX / HOME 等环境，最后 exec 私有 Bash。
- 启动脚本里的路径必须 shell-quote；不得将聊天内容、模型输出或任意用户命令拼入 su 启动脚本。
- 启动 su 时不传 LD_PRELOAD；不要把 API Key、自定义 Header 或 App Server 进程环境交给 Root shell。
- Root 授权不能跨会话视为永久有效；每次新建 Root PTY 前重新校验。
- Root PTY 进程必须独立跟踪并停止；应用退出或用户关闭会话时清理其进程组。

## 后续与 Codex Agent 的安全联动

若要让 Codex 在聊天中请求 Root 调试动作，必须创建独立 Root Tool Gateway，而不是提升 Agent：

- 用 action/resource 权限模型描述动作和目标；默认 ask，拒绝优先，没有规则不放行。
- 用户逐次确认目标设备、包名 / PID、命令、路径、影响和权限身份。
- Root 工具不得继承 Agent 的 API Key / App Server stdin/stdout；审批取消后终止对应子进程。
- “始终允许”只可在明确限定 action、resource、项目和有效期后提供，禁止 root:* 一键全开。
- 读日志、读包信息等只读动作和进程附加、Hook、注入、写系统分开授权。

具体安全和动态调试规划见 [ROOT-DEBUGGING-ARCHITECTURE.md](ROOT-DEBUGGING-ARCHITECTURE.md)。

## 阶段验收

1. 无 Root 设备可以完成普通 PTY 启动、输入、终止和项目目录切换。
2. Root 未启用时不启动 su；拒绝、超时、撤销后不能继续使用 Root PTY。
3. 普通 PTY 的 UID 是应用 UID；Root PTY 的 UID 确认是 0；Codex Agent 仍是应用 UID。
4. Root PTY 启动和停止后没有残留 su / Bash 子进程；连续创建关闭多次不泄漏文件描述符。
5. 分别验证 Magisk、KernelSU 环境和至少一个无 Root 环境。
6. 验证 Ctrl+C、Ctrl+D、resize、横竖屏、后台恢复、终端输出和全屏交互程序。
7. 所有构建和真机结果分开记录；没有 Android 真机日志前不得标记 Root PTY 已验收。
