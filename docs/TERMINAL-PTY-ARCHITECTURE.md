# Android 独立 PTY 终端设计

## 目标与边界

终端页最终必须是应用自己私有 ARM64/Bionic 环境里的真实交互式 PTY，不是单次 `ProcessBuilder` 命令，也不是聊天消息列表伪装的控制台。聊天 Agent 和 Shell Terminal 是两个独立执行通道，但共享用户选择的私有项目工作区。

- Agent：由 `codex app-server --listen stdio://` 负责会话、工具调用、审批和文件变更。
- Terminal：由 Android Terminal Session Manager 管理一个或多个 PTY 子进程，运行应用私有 PREFIX 下的 Bash。
- 两者共用经过路径校验的 `HOME/projects/<project>` 工作目录；权限和输入输出不共用。

## 参考实现与借鉴点

1. **Termux**（https://github.com/termux/termux-app）：研究 `TerminalSession`、`TerminalEmulator`、`TerminalView` 的分工、终端尺寸变化、IME 输入、快捷键、滚动缓冲和进程生命周期。Termux Libraries 文档说明可通过 JitPack 引入 `terminal-view`，但必须锁定具体版本和校验其完整许可证、传递依赖及构建可复现性。
2. **Operit / OperitTerminal**（https://github.com/AAswordman/Operit；https://github.com/AAswordman/OperitTerminal）：参考 Android 移动端的终端会话 UI、项目上下文、工具进度及终端与 Agent 工作台的结合。仅借鉴架构和交互，不直接复制源码。
3. **OpenCode**（https://github.com/anomalyco/opencode）：参考项目/会话上下文、工具执行与终端任务的关联；本项目 Shell 实际执行仍由 Android 私有 PTY 提供，Agent 仍由 Codex App Server 提供。

## 许可证门槛（必须先处理）

Termux 的 `terminal-view` / `terminal-emulator` 源码属于 GPL-3.0 系列；Termux 官方发布的 AAR 也要按其源代码和分发条款评估，不能因 Maven/JitPack 可下载就忽略许可证。Operit Android 当前仓库标注 LGPL-3.0-only，OperitTerminal 标注 GPLv3。当前 Android 仓库尚无根目录 LICENSE 文件，不能默认把 GPL/LGPL 代码直接加入或复制到本项目。

在引入任何终端 UI 依赖前，必须：
- 核实该固定版本的 SPDX 标识、传递依赖和 NOTICE；
- 明确本项目整体发布许可证是否允许采用该组件；
- 保存依赖版本、来源提交、校验和及许可证清单；
- 当前优先评估 **libvterm**（https://github.com/neovim/libvterm）：上游为 MIT 许可证、C99 VT220/xterm 解析器，通过回调把终端状态变化交给宿主；可由 NDK/CMake 编译进 ARM64 APK，再由项目自行实现 Compose/Canvas 字符网格、字体、光标、选区、IME 和快捷键层。它只是 VT parser，不是现成 Android TerminalView，也不会替我们创建 PTY。
- libvterm 的来源仓库 `neovim/libvterm` 已归档为只读镜像，正式接入前要确认可追溯的上游版本、固定 commit、MIT LICENSE、所有生成文件和构建脚本；不得只凭搜索结果里的许可证标签就跳过审查。
- 如果许可证不合适，改为其他许可证兼容的 VT parser 并自行实现 Android View/Compose 渲染层；
- 在许可证决策完成前，不复制 Termux/Operit/OpenTerminal 的实现源码。

## 推荐实现路径（当前建议，不代表已经实现）

采用两层分离：

1. **PTY/进程层**：单独的 NDK helper 或经安全审查的 JNI PTY bridge，负责创建 master/slave、启动私有 Bash、I/O、resize、信号和退出状态。必须验证 Android Bionic API 兼容、fork/exec 子进程稳定性以及 app lifecycle；不要直接照抄 Termux 的 forkpty/JNI 实现。
2. **VT/渲染层**：先评估 MIT libvterm 作为 VT 状态机，通过回调输出 cell、光标、颜色和滚动变化；Android Compose/Canvas 自己渲染。PTY 与渲染层之间用有界队列传输字节和 resize 事件，防止 UI 阻塞。

不要把 PTY 创建和 VT 解析混在同一个 Compose Composable 中；TerminalSessionManager 统一拥有文件描述符和进程，UI 只订阅状态与渲染快照。

## 建议模块拆分

### 1. PtyProcess / NativePtyBridge
- 通过 JNI/NDK 创建 PTY master/slave，启动私有 PREFIX 下的 Bash。
- 设置 `HOME`、`PREFIX`、`PATH`、`TMPDIR`、`LANG`、`TERM` 和当前项目 `PWD`。
- 不继承不必要的宿主环境；不把 API Key 写入命令行参数。
- 提供读写、窗口尺寸调整（TIOCSWINSZ）、信号/进程组终止和退出码回调。
- 确保关闭 Activity/UI 不会误杀用户选择保持后台运行的会话；显式关闭会话时清理进程组和文件描述符。

### 2. TerminalSessionManager
- 管理多个命名会话、当前选中会话、前后台生命周期和滚动缓冲。
- 会话独立保存 cwd、进程状态、退出码和最近输出。
- 进程退出、读写异常、应用升级和运行环境缺失时给出明确状态。
- 不允许两个 UI 同时消费同一个 PTY master stream。

### 3. TerminalSurface
- 负责 VT/ANSI 解析、字符网格、光标、颜色、选区、复制粘贴和滚动。
- 软键盘输入与 Esc、Tab、Ctrl、Alt、方向键、Home/End、PgUp/PgDn 快捷键栏。
- 横竖屏和窗口变化时同步列/行数到 PTY。
- xterm-256color/truecolor 只有在 VT parser 实际支持时才设置对应 TERM。

### 4. 与 Codex UI 的联动
- 聊天页和终端页使用相同的项目路径。
- 可从命令执行卡片打开终端查看同一工作区，但不能把 Agent 的 tool output 当作 PTY 输出。
- 支持从聊天页请求打开终端并填入命令草稿；必须由用户主动确认执行。
- 显示当前目录、运行时状态、会话数和前台进程状态。

## 阶段性验收

1. 私有 PREFIX 中 Bash 可从 PTY 启动，确认进程 UID/数据路径属于 `com.codex.zhou`，与外部 Termux 完全隔离。
2. 支持交互输入、回显、Ctrl-C、Ctrl-D、信号、窗口尺寸变化及退出码。
3. 运行 `vim`、`less`、`top`、`git status` 等交互/颜色命令，检查 ANSI、全屏重绘和滚动。
4. 两个以上 PTY 会话可切换且不会串流；App 后台/前台切换后生命周期符合预期。
5. 在聊天与终端切换后 cwd 始终为同一已验证项目目录。
6. Android 14 ARM64 真机通过；确认没有读取、写入或依赖用户已有 Termux 的私有目录。

在以上测试通过前，终端页面只能标记为“运行环境状态 / 共享工作区预览”，不得宣称 PTY 已完成。
