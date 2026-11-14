#!/usr/bin/env python3
"""Reject floating source refs in the Android runtime lock file."""
from pathlib import Path
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
print(f"Validated {len(entries)} pinned source entries.")
