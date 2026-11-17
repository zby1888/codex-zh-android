#!/usr/bin/env python3
"""Add an authenticated build marker for the Android Codex launcher patch."""
from __future__ import annotations
import json, sys, zipfile
from pathlib import Path

MARKER_PATH = "share/codex-zh-android/runtime-patches.json"
MARKER = {"codexAndroidCurrentExe": "termux-proc-self-exe-v1"}
PAYLOAD = (json.dumps(MARKER, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")

def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: mark-private-runtime-bootstrap.py <bootstrap-aarch64.zip>")
    archive = Path(sys.argv[1])
    if not archive.is_file():
        raise SystemExit(f"Runtime archive not found: {archive}")
    with zipfile.ZipFile(archive, "r") as source:
        matches = [info for info in source.infolist() if info.filename.rstrip("/") == MARKER_PATH]
        if len(matches) > 1:
            raise SystemExit("Runtime archive contains duplicate launcher patch markers")
        if matches:
            try:
                existing = json.loads(source.read(matches[0]).decode("utf-8"))
            except Exception as error:
                raise SystemExit(f"Existing runtime patch marker is invalid: {error}") from error
            if existing != MARKER:
                raise SystemExit("Runtime archive contains a conflicting Android launcher patch marker")
            print("Android launcher patch marker already matches.")
            return
    info = zipfile.ZipInfo(MARKER_PATH)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = (0o100600 << 16)
    with zipfile.ZipFile(archive, "a", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        output.writestr(info, PAYLOAD)
    print(f"Added required Android launcher patch marker: {MARKER_PATH}")

if __name__ == "__main__":
    main()
