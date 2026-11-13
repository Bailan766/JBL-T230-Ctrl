#!/usr/bin/env python3
"""Offline validation of the machine-readable command table.

Checks, without touching any device:
  * every frame is valid even-length hex and starts with 0xAA
  * the declared length field is consistent with the payload (1-byte, or
    2-byte little-endian for 0x06 / long EQ writes)
  * no frame uses a blacklisted / dangerous command byte
  * every command carries confidence and source metadata
  * every writable command declares how it is read back

Usage:  python protocol-spec/verify/check_frames.py
Exit code 0 = all checks passed.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMANDS = ROOT / "app" / "src" / "main" / "assets" / "commands.json"
TWO_BYTE_LEN_CMDS = {"06"}
FIXED_THREE_BYTE_CMDS = {"04", "05"}
MULTI_PART_HEADER_CMDS = {"41", "43"}
VALID_CONFIDENCE = {"RECORDED", "APK_STATIC", "PRIOR_DOC", "INFERRED"}


def hex_bytes(frame: str) -> bytes:
    clean = frame.replace(" ", "").replace("-", "").strip()
    if not clean or len(clean) % 2:
        raise ValueError(f"odd-length or empty hex: {frame!r}")
    return bytes.fromhex(clean)


def main() -> int:
    if not COMMANDS.is_file():
        print(f"FAIL: command table not found: {COMMANDS}")
        return 1

    data = json.loads(COMMANDS.read_text(encoding="utf-8"))
    blacklist = {b.upper() for b in data.get("blacklist", [])}
    errors: list[str] = []
    checked = 0

    for entry in data.get("commands", []):
        name = entry.get("name", "<unnamed>")
        frame = entry.get("frame")
        cmd = str(entry.get("cmd", "")).upper()
        unresolved = bool(entry.get("requires_resolution")) and not entry.get("enabled")

        if entry.get("confidence") not in VALID_CONFIDENCE:
            errors.append(f"[{name}] invalid/missing confidence")
        if not entry.get("source"):
            errors.append(f"[{name}] missing source")
        if entry.get("direction") == "write" and not entry.get("readback") and not entry.get("no_readback_reason"):
            errors.append(f"[{name}] writable command has no readback command")

        # Unresolved command families have no frame at all: that is intentional
        # and is exactly what keeps them from being sent to the earbuds.
        if unresolved:
            if frame:
                errors.append(f"[{name}] unresolved command must not carry a frame")
            if entry.get("enabled"):
                errors.append(f"[{name}] unresolved command must stay disabled")
            checked += 1
            continue

        if not frame:
            errors.append(f"[{name}] missing frame")
            continue
        try:
            raw = hex_bytes(frame)
        except ValueError as exc:
            errors.append(f"[{name}] {exc}")
            continue

        if raw[0] != 0xAA:
            errors.append(f"[{name}] frame does not start with 0xAA: {frame}")

        if len(raw) >= 2 and cmd and int(cmd, 16) != raw[1]:
            errors.append(f"[{name}] cmd byte {raw[1]:02X} != declared 0x{cmd}")

        if cmd in blacklist:
            errors.append(f"[{name}] uses blacklisted command 0x{cmd}")

        if entry.get("dangerous") and entry.get("enabled"):
            errors.append(f"[{name}] dangerous command must stay disabled")

        # Length field consistency.
        if len(raw) >= 3:
            if cmd in FIXED_THREE_BYTE_CMDS:
                if len(raw) != 3:
                    errors.append(f"[{name}] fixed fin frame must be 3 bytes ({frame})")
            elif cmd in MULTI_PART_HEADER_CMDS:
                if len(raw) != 4 or raw[2] == 0 or raw[3] == 0:
                    errors.append(f"[{name}] multi-part header must be 4 non-zero bytes ({frame})")
            elif cmd == "A2":
                if len(raw) >= 5 and raw[2] == 2 and raw[3] == 1:
                    declared = raw[2]
                    actual = len(raw) - 3
                else:
                    declared = raw[2] | (raw[3] << 8) if len(raw) >= 4 else -1
                    actual = len(raw) - 4
                if declared != actual:
                    errors.append(
                        f"[{name}] length field {declared} != actual payload {actual} ({frame})"
                    )
            elif cmd in TWO_BYTE_LEN_CMDS:
                declared = raw[2] | (raw[3] << 8) if len(raw) >= 4 else -1
                actual = len(raw) - 4
                if declared != actual:
                    errors.append(
                        f"[{name}] length field {declared} != actual payload {actual} ({frame})"
                    )
            else:
                declared = raw[2]
                actual = len(raw) - 3
                if declared != actual:
                    errors.append(
                        f"[{name}] length field {declared} != actual payload {actual} ({frame})"
                    )

        # Never ship a write command whose protocol family is unresolved.
        if entry.get("direction") == "write" and entry.get("requires_resolution") and entry.get("enabled"):
            errors.append(f"[{name}] unresolved EQ command family must stay disabled")

        checked += 1

    if not blacklist:
        errors.append("blacklist is empty")
    for required in ("95", "97", "F0", "AA", "13", "15"):
        if required not in blacklist:
            errors.append(f"blacklist missing required entry 0x{required}")

    print(f"Checked {checked} commands, {len(blacklist)} blacklist entries.")
    if errors:
        print(f"FAIL: {len(errors)} problem(s):")
        for e in errors:
            print("  -", e)
        return 1
    print("PASS: frame lengths, blacklist and metadata are consistent.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
