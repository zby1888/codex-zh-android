# 安全设计

## 默认安全边界

- 基础功能不要求 Root；应用私有 Runtime、HOME、CODEX_HOME 和项目工作区由应用 UID 管理。
- 默认不启动本地网络监听；TLS 默认开启。
- API 密钥和自定义 Header 使用 Android Keystore 加密封装；日志、崩溃信息和导出内容必须脱敏。
- 命令执行、MCP 副作用、文件覆盖和删除必须经过权限策略；没有匹配规则时按“每次询问”处理。
- 防范恶意 MCP、提示词注入、路径穿越、命令注入、密钥泄漏和供应链风险。
- Android 12+ 后台启动前台服务受限；Android 14 需遵循前台服务类型与权限要求。
- Runtime 来源固定到 tag / commit，下载后校验 SHA-256，并生成第三方许可证清单。

## Root 专项边界

- Root 默认关闭。只有用户在 UI 主动点击申请，并由系统 Root 管理器（例如 Magisk / KernelSU）授予后，才认为 Root 可用。
- Root 授权能力通过独立 RootAccessManager 探测；启动 Root PTY 前重新运行 su 权限检查。找不到 su、拒绝、超时或异常都必须失败关闭。
- Codex App Server、Agent、普通 PTY 和常规项目操作始终以应用 UID 运行。严禁为了减少权限提示而把整套 Agent Runtime 或 App Server 作为 root 启动。
- Root 终端使用独立 PTY、独立子进程和进程组。普通终端不得继承 Root UID，Root 输出也不能混入 Agent 的 JSONL 协议流。
- Root 启动链不继承 LD_PRELOAD；不要把 API Key 或其他凭据放进 Root 命令行、Shell 历史或普通日志。
- Root 授权可由系统管理器随时撤销。应用不得将“曾经授权”视为永久授权；每次新建 Root 会话前重新核验。
- 未来 Root Tool Gateway 必须逐工具、逐资源审批；默认 ask，拒绝优先，明确目标包名 / PID / 文件路径 / 动作范围。不得提供 root:* 这类一键全开权限。
- 系统 UID 0 不保证绕过 SELinux、厂商策略或内核限制；进程附加、内存读取、Hook、注入及系统写入必须单独测试和审批。
- 逆向 / 动态调试只面向用户拥有或明确获得授权的设备和软件。

## 风险控制

- 特权操作失败时必须明确报告，不能静默退化为普通身份继续执行。
- 用户拒绝或撤销授权后，等待中的特权请求应取消；Root 子进程和进程组要能停止并清理。
- 审计记录仅保留必要元数据和脱敏摘要；敏感输出不得自动上传。
