#!/usr/bin/env python3
"""Apply the Android linker/current_exe fix to the pinned Termux Codex recipe."""
from __future__ import annotations
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCH = ROOT / "runtime/patches/codex-android-current-exe.patch"
RECIPE_RELATIVE = Path("packages/codex/build.sh")
INSERTION = """\t# Keep Rust's current_exe aligned with the linker-launched app-private Codex ELF.
\tif grep -Fq 'fn current_executable_path()' install-context/src/lib.rs; then
\t  grep -Fq 'code_mode_host_program_from_exe(current_executable_path().as_deref())' install-context/src/lib.rs
\telse
\t  patch --silent -p1 < "$TERMUX_PKG_BUILDER_DIR/codex-android-current-exe.patch"
\tfi
"""

def validate_patch() -> str:
    if not PATCH.is_file():
        raise SystemExit(f"Missing Android launcher patch: {PATCH}")
    content = PATCH.read_text(encoding="utf-8")
    required = (
        "fn current_executable_path() -> Option<PathBuf>",
        'std::env::var_os("TERMUX_EXEC__PROC_SELF_EXE")',
        "let current_exe = current_executable_path();",
        "code_mode_host_program_from_exe(current_executable_path().as_deref())",
    )
    for token in required:
        if token not in content:
            raise SystemExit(f"Android launcher patch is incomplete: missing {token}")
    if not content.endswith("\n"):
        raise SystemExit("Android launcher patch must end with a newline")
    return content

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("termux_packages", type=Path, nargs="?")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    validate_patch()
    if args.check:
        print("Android Codex linker/current_exe patch is present and complete.")
        return
    if args.termux_packages is None:
        parser.error("termux_packages is required unless --check is used")
    source = args.termux_packages.resolve()
    recipe = source / RECIPE_RELATIVE
    if not recipe.is_file():
        raise SystemExit(f"Pinned Termux Codex recipe not found: {recipe}")
    text = recipe.read_text(encoding="utf-8")
    if 'TERMUX_PKG_VERSION="0.169.0"' not in text:
        raise SystemExit("Termux Codex recipe version differs from the locked 0.169.0 source")
    patch_copy = recipe.parent / PATCH.name
    patch_content = PATCH.read_text(encoding="utf-8")
    if patch_copy.exists() and patch_copy.read_text(encoding="utf-8") != patch_content:
        raise SystemExit("A different Android launcher patch already exists in the Termux recipe directory")
    patch_copy.write_text(patch_content, encoding="utf-8")
    if INSERTION.strip() in text:
        print("Termux Codex recipe already contains the Android launcher patch hook.")
        return
    needle = "\tcd codex-rs\n"
    if text.count(needle) != 1:
        raise SystemExit(f"Expected one 'cd codex-rs' in Termux Codex recipe, found {text.count(needle)}")
    text = text.replace(needle, needle + INSERTION, 1)
    recipe.write_text(text, encoding="utf-8")
    print("Patched pinned Termux Codex recipe; both codex and code-mode host will use the same Android path fix.")

if __name__ == "__main__":
    main()
