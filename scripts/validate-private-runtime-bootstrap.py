#!/usr/bin/env python3
"""Validate the private ARM64 bootstrap archive and emit a provenance manifest."""
from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / "runtime/private-runtime-config.json").read_text(encoding="utf-8"))


def safe_archive_path(name: str) -> str:
    if not name or name.startswith("/") or "\\" in name:
        raise ValueError(f"Unsafe archive path: {name!r}")
    normalized = posixpath.normpath(name)
    if normalized in {".", ".."} or normalized.startswith("../"):
        raise ValueError(f"Archive path escapes prefix: {name!r}")
    return normalized.rstrip("/")


def validate_symlink_target(link: str, target: str) -> None:
    if not target:
        raise ValueError(f"Empty symlink target for {link}")
    if target.startswith("/"):
        prefix = (
            f'/data/data/{CONFIG["appPackageName"]}/'
            f'{CONFIG["rootfsSubdir"]}/{CONFIG["prefixSubdir"]}'
        )
        if not (target == prefix or target.startswith(prefix + "/")
                or target.startswith("/system/") or target.startswith("/apex/")
                or target.startswith("/vendor/")):
            raise ValueError(f"Absolute symlink target escapes app prefix: {link} -> {target}")
    else:
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(link), target))
        if resolved == ".." or resolved.startswith("../"):
            raise ValueError(f"Relative symlink target escapes prefix: {link} -> {target}")


def locked_ref(name: str) -> str:
    lock = (ROOT / "runtime/sources.lock.yml").read_text(encoding="utf-8")
    entries = re.findall(r"(?ms)^  - name: ([^\n]+)\n(.*?)(?=^  - name: |\Z)", lock)
    for source_name, body in entries:
        if source_name.strip() == name:
            match = re.search(r"(?m)^    ref: ([^\n]+)$", body)
            if match:
                return match.group(1).strip().strip("'\"")
    raise SystemExit(f"Missing pinned source ref in sources.lock.yml: {name}")


def validate_aarch64_elf(archive: zipfile.ZipFile, path: str) -> None:
    data = archive.read(path)
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise SystemExit(f"{path} is not an ELF executable")
    if data[4] != 2 or data[5] != 1:
        raise SystemExit(f"{path} is not a 64-bit little-endian ELF executable")
    machine = int.from_bytes(data[18:20], byteorder="little")
    if machine != 183:
        raise SystemExit(f"{path} has ELF machine {machine}, expected AArch64 (183)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()

    archive = args.archive.resolve()
    if not archive.is_file():
        raise SystemExit(f"Bootstrap archive not found: {archive}")

    with zipfile.ZipFile(archive) as zf:
        names = {safe_archive_path(info.filename) for info in zf.infolist() if not info.is_dir()}
        if "SYMLINKS.txt" not in names:
            raise SystemExit("Bootstrap archive is missing SYMLINKS.txt")
        raw_links = zf.read("SYMLINKS.txt").decode("utf-8")
        links: dict[str, str] = {}
        for line in raw_links.splitlines():
            if not line:
                continue
            if "←" not in line:
                raise SystemExit(f"Malformed symlink record: {line!r}")
            target, raw_link = line.split("←", 1)
            link = safe_archive_path(raw_link.removeprefix("./"))
            validate_symlink_target(link, target)
            if link in links:
                raise SystemExit(f"Duplicate symlink entry: {link}")
            links[link] = target

        required = [
            "bin/bash", "bin/env", "bin/git", "bin/jq",
            "lib/libtermux-exec-ld-preload.so",
            f"bin/{CONFIG['codexPackage']}", f"bin/{CONFIG['codexHelper']}",
        ]
        missing = [path for path in required if path not in names and path not in links]
        if missing:
            raise SystemExit("Bootstrap is missing required runtime paths: " + ", ".join(missing))
        if CONFIG["codexPackage"] not in CONFIG["packages"]:
            raise SystemExit("Codex package is missing from private-runtime-config.json")
        validate_aarch64_elf(zf, f"bin/{CONFIG['codexPackage']}")
        validate_aarch64_elf(zf, f"bin/{CONFIG['codexHelper']}")

        # Reject any absolute or parent-traversing ZIP member, even if it is
        # not part of the executable set.
        for info in zf.infolist():
            safe_archive_path(info.filename)

    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    manifest = {
        "schemaVersion": 1,
        "abi": "arm64-v8a",
        "applicationId": CONFIG["appPackageName"],
        "rootfs": CONFIG["rootfsSubdir"],
        "prefix": CONFIG["prefixSubdir"],
        "home": CONFIG["homeSubdir"],
        "sourceRepository": "https://github.com/termux/termux-packages",
        "sourceRef": locked_ref("termux-packages"),
        "codexVersion": CONFIG["codexVersion"],
        "codexSourceRef": locked_ref("codex"),
        "localizationSourceRef": locked_ref("codex-zh-localization"),
        "localizationPatchCount": CONFIG["codexLocalizationPatchCount"],
        "packages": CONFIG["packages"],
        "archive": archive.name,
        "archiveSize": archive.stat().st_size,
        "sha256": digest,
        "symlinkCount": len(links),
        "requiredPaths": required,
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
