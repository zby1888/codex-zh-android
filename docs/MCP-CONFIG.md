# MCP 配置与交互

## 传输
- STDIO：由应用私有 Runtime 启动命令，配置 args/env/cwd；限制在应用沙盒内。
- Streamable HTTP：远程 MCP 服务，支持 HTTPS、Bearer Token；OAuth 按服务端能力实现。

## 策略
每个服务/工具可设置 `allow`、`deny`、`ask`。文件写入、命令执行、网络访问、删除等有副作用的操作默认 `ask`。界面显示服务名、工具名、关键参数、调用进度和结果，并提供停止入口。

## 导入
支持 Codex TOML 的 `mcp_servers`、OpenCode JSON 的 `mcp.servers`、Operit JSON 的 `mcpServers`。RikkaHub 适配器待确认目标版本导出格式。导入前预览差异，不自动执行配置中的命令。
