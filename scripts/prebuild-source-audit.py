#!/usr/bin/env python3
"""Repository-wide safety checks that must pass before every Android build."""
from pathlib import Path
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TEXT_SUFFIXES = {
    ".kt", ".kts", ".java", ".cpp", ".c", ".h", ".hpp", ".xml",
    ".py", ".sh", ".yml", ".yaml", ".json", ".properties",
    ".gradle", ".toml"
}
# Third-party repositories checked out for builds are pinned and validated separately.
# Do not fail first-party source hygiene checks because of upstream formatting choices.
SKIP_PARTS = {".git", ".gradle", "build", ".idea", ".kotlin", "__pycache__", "vendor"}
errors = []
checked = 0

for path in sorted(ROOT.rglob("*")):
    if not path.is_file() or any(part in SKIP_PARTS for part in path.parts):
        continue
    if path.suffix.lower() not in TEXT_SUFFIXES:
        continue
    try:
        raw = path.read_bytes()
        source = raw.decode("utf-8")
    except Exception as exc:
        errors.append(f"{path.relative_to(ROOT)}: cannot read UTF-8 source: {exc}")
        continue
    checked += 1
    if b"\x00" in raw:
        errors.append(f"{path.relative_to(ROOT)}: contains a NUL byte")
    if raw and not raw.endswith(b"\n"):
        errors.append(f"{path.relative_to(ROOT)}: missing final newline")
    if re.search(r"(?m)^<<<<<<< (?:HEAD|.+)$|^>>>>>>> (?:.+)$", source):
        errors.append(f"{path.relative_to(ROOT)}: contains a merge-conflict marker")
    if path.suffix.lower() == ".xml":
        try:
            ET.fromstring(source)
        except ET.ParseError as exc:
            errors.append(f"{path.relative_to(ROOT)}: invalid XML: {exc}")
    elif path.suffix.lower() == ".json":
        try:
            json.loads(source)
        except json.JSONDecodeError as exc:
            errors.append(f"{path.relative_to(ROOT)}: invalid JSON: {exc}")
    elif path.suffix.lower() == ".py":
        try:
            compile(source, str(path), "exec")
        except SyntaxError as exc:
            errors.append(f"{path.relative_to(ROOT)}: Python syntax error: {exc}")
    elif path.suffix.lower() == ".sh":
        result = subprocess.run(
            ["bash", "-n", str(path)], cwd=ROOT,
            capture_output=True, text=True
        )
        if result.returncode:
            errors.append(f"{path.relative_to(ROOT)}: shell syntax error: {result.stderr.strip()}")

# Cross-file regression guards for the terminal, composer and native callback lifetime.
chat = (ROOT / "app/src/main/java/io/github/zby1888/codexzh/CodexChatScreen.kt").read_text()
main = (ROOT / "app/src/main/java/io/github/zby1888/codexzh/MainActivity.kt").read_text()
installer = (ROOT / "app/src/main/java/io/github/zby1888/codexzh/RuntimeBootstrapInstaller.kt").read_text()
native = (ROOT / "app/src/main/cpp/native_pty_terminal.cpp").read_text()
if "enter_sends_message" not in chat or "ImeAction.Default" not in chat or "ImeAction.Send" not in chat:
    errors.append("CodexChatScreen.kt: Enter-send/newline preference is missing")
if "state = messageListState" not in chat or "snapshotFlow" not in chat:
    errors.append("CodexChatScreen.kt: message list follow-latest behavior is missing")
if "fun monitorTurn(" not in chat or "agent.listTurns(" not in chat:
    errors.append("CodexChatScreen.kt: server-backed turn status monitoring is missing")
if "requestFinalItems(thread, turnId)" not in chat or "appendListedTurnItems(response)" not in chat:
    errors.append("CodexChatScreen.kt: final conversation item recovery is missing")
if "Button(\n                        onClick = { submitPrompt() }" not in chat:
    errors.append("CodexChatScreen.kt: send button is not in the composer input row")
if "terminalFontScale" not in main or "10.5f * density * terminalFontScale" not in main:
    errors.append("MainActivity.kt: adjustable terminal font/grid geometry is missing")
if "codex_terminal_ui" not in main or 'putFloat("font_scale"' not in main:
    errors.append("MainActivity.kt: terminal font size preference must persist")
if 'terminal.changeDirectory(currentWorkspace)' not in main:
    errors.append("MainActivity.kt: running terminal must retain the switch-to-current-workspace action")
if "showRootDetails" not in main:
    errors.append("MainActivity.kt: Root controls must be collapsible")
if "appendCompletedTurnItems(turn)" not in chat or "fun monitorTurn(" not in chat or "requestFinalItems(thread, turnId)" not in chat:
    errors.append("CodexChatScreen.kt: completed turn reconciliation, item sync and history polling are missing")
if "responseContainsAssistantMessage(response)" not in chat or "agent.listItems(" not in chat:
    errors.append("CodexChatScreen.kt: final text must be verified against the current turn's server items")
if "codexErrorInfo" not in chat or "additionalDetails" not in chat:
    errors.append("CodexChatScreen.kt: structured Codex turn errors are not exposed")
if "activityRevision" in chat or "消息已提交，但连续 60 秒" in chat:
    errors.append("CodexChatScreen.kt: obsolete delta-only timeout logic must stay removed")
if "TerminalSurface(terminal.frame) { terminal.loadScrollback() }" not in main:
    errors.append("MainActivity.kt: terminal view must request native scrollback")
terminal_view = (ROOT / "app/src/main/java/io/github/zby1888/codexzh/TerminalSoftwareView.kt").read_text()
if "scrollbackLoader" not in terminal_view or "MotionEvent.ACTION_MOVE" not in terminal_view:
    errors.append("TerminalSoftwareView.kt: vertical scroll gestures are missing")
if "scrollbackPushLine" not in native or "sb_pushline=scrollbackPushLine" not in native or "nativeScrollbackSnapshot" not in native:
    errors.append("native_pty_terminal.cpp: libvterm scrollback callbacks are not connected")
if "VTermScreenCallbacks screenCallbacks{}" not in native or "&terminal->screenCallbacks" not in native:
    errors.append("native_pty_terminal.cpp: libvterm callback lifetime regression")
if "VTermScreenCallbacks callbacks{}" in native:
    errors.append("native_pty_terminal.cpp: stack-local callback struct is forbidden")


if "RuntimeOnboardingScreen(" not in main or "runtimeInstaller.install {" not in main:
    errors.append("MainActivity.kt: first-launch onboarding must install the bundled runtime before entering the app")
if "AnimatedContent(" not in main or "rememberSaveableStateHolder()" not in main:
    errors.append("MainActivity.kt: page transitions must be animated and preserve saveable page state")
if "fun install(onProgress: (RuntimeInstallProgress) -> Unit = {})" not in installer:
    errors.append("RuntimeBootstrapInstaller.kt: onboarding install progress callback is missing")
if "正在校验内置文件" not in installer or "已处理 $entryCount 项文件" not in installer:
    errors.append("RuntimeBootstrapInstaller.kt: install stages must report truthful progress")
if 'appendCommandOutput(itemId, params.optString("delta"))' not in chat:
    errors.append("CodexChatScreen.kt: command output deltas must be attached to their activity row")
if "if (isActivity && text.isBlank()) return" not in chat or '"未知操作"' in chat:
    errors.append("CodexChatScreen.kt: empty/generic execution placeholders must not appear")
if "val preview = lines.firstOrNull()" not in chat or ".clickable { expanded = !expanded }" not in chat:
    errors.append("CodexChatScreen.kt: execution details must be compact and expandable")
if "ClipboardManager" not in chat or 'Text("复制消息")' not in chat:
    errors.append("CodexChatScreen.kt: user, assistant and activity messages need a copy action")
if "SelectionContainer" not in chat:
    errors.append("CodexChatScreen.kt: message bodies must support native text selection")
if "fun editAndResend(" not in chat or "agent.forkThread(sourceThreadId, previousTurnId)" not in chat:
    errors.append("CodexChatScreen.kt: edit-and-resend must fork before the selected turn instead of appending to the source thread")
if 'Text("编辑并重发")' not in chat or "val turnId: String? = null" not in chat:
    errors.append("CodexChatScreen.kt: editable historical user messages need a visible action and stable turn metadata")
if "isPlainTextOnly" not in chat or "userMessagesPerTurn[itemTurnId] == 1" not in chat:
    errors.append("CodexChatScreen.kt: editing must be disabled for attachments and multiple user prompts sharing a turn")

chat_state = chat
mcp_controller = (ROOT / "app/src/main/java/io/github/zby1888/codexzh/CodexMcpController.kt").read_text()
app_server_client = (ROOT / "app/src/main/java/io/github/zby1888/codexzh/CodexAppServerClient.kt").read_text()
if "CodexChatUiState(context)" not in main or "internal class CodexChatUiState(context: Context)" not in chat_state:
    errors.append("CodexChatScreen.kt/MainActivity.kt: chat preferences must survive Activity/process recreation")
if 'selected_model_id' not in chat_state or 'selected_reasoning_effort' not in chat_state or "persistModelSelection()" not in chat_state:
    errors.append("CodexChatScreen.kt: model and reasoning selection must be persisted")
if "LaunchedEffect(autoStartAttempt)" not in chat_state or "agent.listModels(includeHidden = false)" not in chat_state:
    errors.append("CodexChatScreen.kt: Codex App Server and model list must start automatically on chat entry")
if "重新连接 Codex" not in chat_state:
    errors.append("CodexChatScreen.kt: automatic startup failure must offer a retry action")
if 'client.request("config/mcpServer/reload", null)' not in mcp_controller:
    errors.append("CodexMcpController.kt: MCP reload must use the pinned protocol method without params")
if '"mcpServer/refresh"' in mcp_controller:
    errors.append("CodexMcpController.kt: obsolete MCP refresh RPC must not be used")
if "toolsAndAuthOnly" not in mcp_controller or 'if (params != null) requestMessage.put("params", params)' not in app_server_client:
    errors.append("Codex MCP status listing must use lightweight detail and support omitted JSON-RPC params")
if "Icon(item.icon()" not in main or "alwaysShowLabel = false" not in main or "material-icons-extended" not in (ROOT / "app/build.gradle.kts").read_text():
    errors.append("MainActivity.kt: navigation must use compact vector icons rather than text glyphs")
if "Root 权限管理" not in main or "showRootDetails" not in main:
    errors.append("MainActivity.kt: Root permission entry must be visible without scrolling the terminal shortcut row")
if "minLines = 2" not in chat_state or "Button(\n                        onClick = { submitPrompt() }" not in chat_state:
    errors.append("CodexChatScreen.kt: composer must use a full-width multiline editor with a separate send action row")

if errors:
    print("PREBUILD SOURCE AUDIT FAILED")
    for item in errors:
        print(" - " + item)
    sys.exit(1)
print(f"Prebuild source audit passed: {checked} source and config files checked; XML, JSON, Python and shell syntax checks completed.")
