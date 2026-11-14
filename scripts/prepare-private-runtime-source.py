#!/usr/bin/env python3
"""Patch the pinned Termux package recipes for this app's private ARM64 prefix."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / "runtime/private-runtime-config.json").read_text(encoding="utf-8"))


def replace_once(content: str, old: str, new: str, label: str) -> str:
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return content.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: prepare-private-runtime-source.py <termux-packages-checkout>")

    source = Path(sys.argv[1]).resolve()
    # APR-util 1.6.5 is an archived Apache release. The pinned Termux
    # recipe still points at downloads.apache.org, which no longer serves this
    # old tarball after newer APR-util releases are published. Keep the pinned
    # version and checksum; only switch to the official Apache archive.
    apr_util_path = source / "packages/apr-util/build.sh"
    if not apr_util_path.is_file():
        raise SystemExit(f"Missing pinned APR-util recipe: {apr_util_path}")
    apr_util = apr_util_path.read_text(encoding="utf-8")
    apr_util = replace_once(
        apr_util,
        'TERMUX_PKG_SRCURL="https://downloads.apache.org/apr/apr-util-${TERMUX_PKG_VERSION}.tar.gz"',
        'TERMUX_PKG_SRCURL="https://archive.apache.org/dist/apr/apr-util-${TERMUX_PKG_VERSION}.tar.gz"',
        "use official Apache archive for APR-util 1.6.5",
    )
    if "TERMUX_PKG_SHA256=f43a1c8c79eef497a022ec6c99dddbdf57e42001da6ccbfae259631ed5aa2805" not in apr_util:
        raise SystemExit("Pinned APR-util SHA-256 changed unexpectedly; review the recipe before building")
    apr_util_path.write_text(apr_util, encoding="utf-8")

    properties_path = source / "scripts/properties.sh"
    bootstrap_path = source / "scripts/build-bootstraps.sh"
    docker_runner_path = source / "scripts/run-docker.sh"

    if not properties_path.is_file() or not bootstrap_path.is_file() or not docker_runner_path.is_file():
        raise SystemExit(f"Not a supported termux-packages checkout: {source}")

    properties = properties_path.read_text(encoding="utf-8")
    properties = replace_once(
        properties,
        '__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="true"',
        '__TERMUX_BUILD_PROPS__VALIDATE_TERMUX_PREFIX_USR_MERGE_FORMAT="false"',
        "disable usr-merge prefix validation for the custom prefix",
    )
    properties = replace_once(
        properties,
        'TERMUX_APP__PACKAGE_NAME="com.termux"',
        f'TERMUX_APP__PACKAGE_NAME="{CONFIG["appPackageName"]}"',
        "set the application package id",
    )
    properties = replace_once(
        properties,
        'TERMUX__ROOTFS_SUBDIR="files"',
        f'TERMUX__ROOTFS_SUBDIR="{CONFIG["rootfsSubdir"]}"',
        "set the app-private rootfs directory",
    )
    properties = replace_once(
        properties,
        'TERMUX__HOME_SUBDIR="home"',
        f'TERMUX__HOME_SUBDIR="{CONFIG["homeSubdir"]}"',
        "set the isolated HOME directory",
    )
    properties = replace_once(
        properties,
        'TERMUX__PREFIX_SUBDIR="usr"',
        f'TERMUX__PREFIX_SUBDIR="{CONFIG["prefixSubdir"]}"',
        "set the isolated runtime prefix",
    )
    properties_path.write_text(properties, encoding="utf-8")

    bootstrap = bootstrap_path.read_text(encoding="utf-8")
    start_marker = "\t\tPACKAGES=()\n\t\tEXTRACTED_PACKAGES=()\n"
    end_marker = "\t\t# Handle additional packages.\n"
    if bootstrap.count(start_marker) != 1 or bootstrap.count(end_marker) != 1:
        raise SystemExit("Could not locate the pinned bootstrap package list exactly once")
    start = bootstrap.index(start_marker)
    end = bootstrap.index(end_marker, start)
    package_lines = "".join(f'\t\tPACKAGES+=("{name}")\n' for name in CONFIG["packages"])
    replacement = (
        "\t\tPACKAGES=()\n"
        "\t\tEXTRACTED_PACKAGES=()\n\n"
        "\t\t# Minimal Codex Android runtime package set; all package paths are compiled for this app.\n"
        + package_lines
        + "\n"
    )
    bootstrap = bootstrap[:start] + replacement + bootstrap[end:]
    # Write the generated archive into output/, which is already writable for
    # package artifacts under the Termux builder's restricted container profile.\n    bootstrap = replace_once(\n        bootstrap,\n        'mv -f "${BOOTSTRAP_TMPDIR}/bootstrap-${1}.zip" "$TERMUX_PACKAGES_DIRECTORY/"',\n        'mv -f "${BOOTSTRAP_TMPDIR}/bootstrap-${1}.zip" "$TERMUX_BUILT_DEBS_DIRECTORY/" || return $?',\n        "write bootstrap archive into the writable package output directory",\n    )\n    bootstrap_path.write_text(bootstrap, encoding="utf-8")

    # GitHub-hosted runners do not always expose AppArmor. Keep the upstream
    # seccomp profile and CAP_SYS_ADMIN/FUSE settings, but omit only the
    # optional AppArmor profile when the host cannot load it.
    docker_runner = docker_runner_path.read_text(encoding="utf-8")
    pattern = re.compile(
        r'(if \[ -z "\$APPARMOR_PARSER" \] \|\| ! \$SUDO aa-status --enabled; then\n'
        r'.*?'
        r'\tAPPARMOR_PARSER=""\n'
        r'fi)',
        re.DOTALL,
    )
    matches = list(pattern.finditer(docker_runner))
    if len(matches) != 1:
        raise SystemExit("Could not locate the AppArmor fallback block exactly once")
    block = matches[0].group(1)
    block = block.replace(
        '\tAPPARMOR_PARSER=""\n',
        '\tAPPARMOR_PARSER=""\n'
        '\tSEC_OPT="$(printf \'%s\' "$SEC_OPT" | sed -E \'s/ --security-opt apparmor=[^ ]+//\')"\n',
    )
    docker_runner = docker_runner[:matches[0].start()] + block + docker_runner[matches[0].end():]
    docker_runner_path.write_text(docker_runner, encoding="utf-8")

    prefix = f'/data/data/{CONFIG["appPackageName"]}/{CONFIG["rootfsSubdir"]}/{CONFIG["prefixSubdir"]}'
    home = f'/data/data/{CONFIG["appPackageName"]}/{CONFIG["rootfsSubdir"]}/{CONFIG["homeSubdir"]}'
    print(f"Configured Android package id: {CONFIG['appPackageName']}")
    print(f"Configured runtime prefix: {prefix}")
    print(f"Configured HOME: {home}")
    print(f"Configured packages ({len(CONFIG['packages'])}): {', '.join(CONFIG['packages'])}")


if __name__ == "__main__":
    main()
