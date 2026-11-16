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

if errors:
    print("PREBUILD SOURCE AUDIT FAILED")
    for item in errors:
        print(" - " + item)
    sys.exit(1)
print(f"Prebuild source audit passed: {checked} source and config files checked; XML, JSON, Python and shell syntax checks completed.")
