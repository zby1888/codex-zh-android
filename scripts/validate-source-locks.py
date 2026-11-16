#!/usr/bin/env python3
"""Reject floating source refs in the Android runtime lock file."""
from pathlib import Path
import json
import re
import sys

path = Path(__file__).resolve().parents[1] / "runtime" / "sources.lock.yml"
content = path.read_text(encoding="utf-8")
entries = re.findall(r"(?ms)^  - name: ([^\n]+)\n(.*?)(?=^  - name: |\Z)", content)
if not entries:
    print("No source entries found", file=sys.stderr)
    raise SystemExit(1)
for name, body in entries:
    repository = re.search(r"(?m)^    repository: (.+)$", body)
    ref = re.search(r"(?m)^    ref: (.+)$", body)
    if not repository or not ref:
        raise SystemExit(f"Incomplete source lock: {name}")
    value = ref.group(1).strip().strip("'\"")
    if value.lower() in {"main", "master", "latest", "head"}:
        raise SystemExit(f"Floating ref is not allowed: {name} -> {value}")
    if not repository.group(1).startswith("https://github.com/"):
        raise SystemExit(f"Unexpected repository URL: {name}")
config = json.loads((path.parent / "private-runtime-config.json").read_text(encoding="utf-8"))
base = config.get("packages", [])
profiles = config.get("optionalPackageProfiles", {})
if not base or len(base) != len(set(base)):
    raise SystemExit("Base runtime package list is empty or contains duplicates")
if config.get("codexPackage") not in base:
    raise SystemExit("Pinned Codex package must remain in the base runtime")
if "ripgrep" not in base:
    raise SystemExit("ripgrep must remain in the base runtime for code search")

base_set = set(base)

required_base = {
    "bash", "coreutils", "git", "openssl", "ca-certificates",
    "ripgrep", "termux-exec", config.get("codexPackage"),
}
missing_base = sorted(required_base.difference(base_set))
if missing_base:
    raise SystemExit(f"Missing required base runtime packages: {missing_base}")

app_gradle = (path.parent.parent / "app" / "build.gradle.kts").read_text(encoding="utf-8")
application_id = re.search(r'applicationId\s*=\s*"([^"]+)"', app_gradle)
if not application_id or application_id.group(1) != config.get("appPackageName"):
    raise SystemExit("Android applicationId does not match private runtime prefix configuration")

runtime_manager = (path.parent.parent / "app" / "src" / "main" / "java" / "io" / "github" / "zby1888" / "codexzh" / "RuntimeManager.kt").read_text(encoding="utf-8")
installer = (path.parent.parent / "app" / "src" / "main" / "java" / "io" / "github" / "zby1888" / "codexzh" / "RuntimeBootstrapInstaller.kt").read_text(encoding="utf-8")
archive_validator = (path.parent.parent / "scripts" / "validate-private-runtime-bootstrap.py").read_text(encoding="utf-8")
for limit_name in ("MAX_ENTRY_BYTES", "MAX_TOTAL_BYTES"):
    kotlin_limit = re.search(
        rf"{limit_name}\s*=\s*(\d+)L\s*\*\s*1024L\s*\*\s*1024L",
        installer,
    )
    python_limit = re.search(
        rf"^{limit_name}\s*=\s*(\d+)\s*\*\s*1024\s*\*\s*1024\s*$",
        archive_validator,
        re.MULTILINE,
    )
    if not kotlin_limit or not python_limit or kotlin_limit.group(1) != python_limit.group(1):
        raise SystemExit(f"{limit_name} differs between Android installer and archive validator")
version = re.search(r'EXPECTED_CODEX_VERSION\s*=\s*"([^"]+)"', runtime_manager)
if not version or version.group(1) != config.get("codexVersion"):
    raise SystemExit("Android runtime version check does not match the pinned Codex package version")

if "jq" not in base_set and ("paths.jq.exists()" in runtime_manager or "paths.jq" in installer):
    raise SystemExit("Optional jq package is incorrectly treated as a base runtime requirement")

runtime_paths = (path.parent.parent / "app" / "src" / "main" / "java" / "io" / "github" / "zby1888" / "codexzh" / "RuntimePaths.kt").read_text(encoding="utf-8")
app_server = (path.parent.parent / "app" / "src" / "main" / "java" / "io" / "github" / "zby1888" / "codexzh" / "CodexAppServerClient.kt").read_text(encoding="utf-8")
pty_session = (path.parent.parent / "app" / "src" / "main" / "java" / "io" / "github" / "zby1888" / "codexzh" / "PtyTerminalSession.kt").read_text(encoding="utf-8")
native_pty = (path.parent.parent / "app" / "src" / "main" / "cpp" / "native_pty_terminal.cpp").read_text(encoding="utf-8")
if "std::unordered_map<jlong,std::shared_ptr<Terminal>> gTerminals" not in native_pty:
    raise SystemExit("Native PTY handles must be held by the lifetime-safe registry")
if "reinterpret_cast<Terminal*>(handle)" in native_pty:
    raise SystemExit("Raw native PTY pointers must never cross the JNI handle boundary")
if "removeTerminal(handle)" not in native_pty or "term->closing=true" not in native_pty:
    raise SystemExit("Native PTY close must revoke handles before freeing PTY resources")
target_sdk = re.search(r"targetSdk\s*=\s*(\d+)", app_gradle)
if target_sdk and int(target_sdk.group(1)) >= 29:
    for required in (
        'File("/system/bin/linker64")',
        "fun commandFor(",
        '"TERMUX_APP__DATA_DIR"',
        '"TERMUX_APP__LEGACY_DATA_DIR"',
        '"TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "enable"',
    ):
        if required not in runtime_paths:
            raise SystemExit(f"Android W^X linker launcher contract missing: {required}")
    if 'paths.commandFor(codex, listOf("--version"))' not in runtime_manager:
        raise SystemExit("Codex version check must use the Android system-linker launcher")
    if "TERMUX_EXEC__PROC_SELF_EXE" not in runtime_manager:
        raise SystemExit("Codex version check must identify the linker-launched ELF")
    if 'paths.commandFor(codex, listOf("app-server", "--listen", "stdio://"))' not in app_server:
        raise SystemExit("Codex App Server must use the Android system-linker launcher")
    if "TERMUX_EXEC__PROC_SELF_EXE" not in app_server:
        raise SystemExit("Codex App Server must identify the linker-launched ELF")
    if 'paths.commandFor(paths.bash, listOf("-i"))' not in pty_session:
        raise SystemExit("Interactive Bash must use the Android system-linker launcher")

prepare_script = (path.parent.parent / "scripts" / "prepare-private-runtime-source.py").read_text(encoding="utf-8")
runtime_workflow = (path.parent.parent / ".github" / "workflows" / "build-private-runtime.yml").read_text(encoding="utf-8")
if '"${TERMUX_BUILT_DEBS_DIRECTORY}/bootstrap-dist/"' not in prepare_script:
    raise SystemExit("Bootstrap archive must be written under the AppArmor-writable Termux output directory")
if "vendor/termux-packages/output/bootstrap-dist/bootstrap-aarch64.zip" not in runtime_workflow:
    raise SystemExit("Runtime workflow must collect the bootstrap from the Termux output directory")
if "test -w /home/builder/termux-packages/output/bootstrap-dist" not in runtime_workflow:
    raise SystemExit("Runtime workflow must preflight the bootstrap output directory inside the builder")
for profile_name, packages in profiles.items():
    if len(packages) != len(set(packages)):
        raise SystemExit(f"Duplicate package in optional profile: {profile_name}")
    overlap = base_set.intersection(packages)
    if overlap:
        raise SystemExit(f"Optional profile {profile_name} duplicates base packages: {sorted(overlap)}")
    if any(not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", package) for package in packages):
        raise SystemExit(f"Invalid Termux package name in profile: {profile_name}")
print(f"Validated {len(entries)} pinned source entries, {len(base)} base packages and {len(profiles)} optional profiles.")
