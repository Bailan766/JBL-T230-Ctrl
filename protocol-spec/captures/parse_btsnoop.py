#!/usr/bin/env python3
"""Best-effort, dependency-free btsnoop -> AA-frame CSV extractor.

The machine has no tshark/Wireshark, so this parses the standard btsnoop HCI
file format with the Python standard library only. It keeps *only* ATT payloads
that look like Harman `AA <cmd> <len> <payload>` frames. Everything else (HCI
headers, addresses, pairing material, random values) is dropped, which is the
first and most important privacy boundary.

Usage:
    python protocol-spec/captures/parse_btsnoop.py \
        --in protocol-spec/captures/raw/btsnoop_hci.log \
        --out protocol-spec/captures \
        --notes protocol-spec/captures/CAPTURE_NOTES.md

Output: one `frames_all.csv` plus per-operation `diff_*.csv` files when a notes
file with `HH:MM:SS | operation` lines is supplied.
"""

from __future__ import annotations

import argparse
import csv
import re
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

ATT_CID = 0x0004
ATT_WRITE_CMD = 0x52
ATT_WRITE_REQ = 0x12
ATT_NOTIFY = 0x1B
AA = 0xAA

MAC_RE = re.compile(r"(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")


def redact(text: str) -> str:
    return MAC_RE.sub("<MAC>", text)


def read_records(path: Path):
    raw = path.read_bytes()
    if len(raw) < 16 or raw[:8] != b"btsnoop\x00":
        raise SystemExit("Not a btsnoop file (bad magic header)")
    version, datalink = struct.unpack(">II", raw[8:16])
    print(f"btsnoop version={version} datalink=0x{datalink:X}")
    pos = 16
    base_ts = None
    while pos + 24 <= len(raw):
        orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIQ", raw[pos:pos + 24])
        pos += 24
        packet = raw[pos:pos + incl_len]
        pos += incl_len
        if base_ts is None:
            base_ts = ts
        # btsnoop timestamps are microseconds since 1970 (with a 0x00DCBBA0 epoch
        # offset on some writers); relative time is all we need for diffing.
        rel = (ts - base_ts) / 1_000_000.0
        yield rel, packet, flags


def att_frames_from_packet(packet: bytes):
    """Yield (direction, handle, frame_hex) for AA protocol frames in one HCI packet."""
    # H4: packet type byte 0x02 = ACL data; some captures prepend a 1-byte
    # direction pseudo-header, so try both offsets.
    for offset in (0, 1):
        if len(packet) <= offset + 1 or packet[offset] != 0x02:
            continue
        p = packet[offset:]
        if len(p) < 5:
            continue
        acl_len = p[3] | (p[4] << 8)
        body = p[5:5 + acl_len]
        if len(body) < 4:
            continue
        l2cap_len = body[0] | (body[1] << 8)
        cid = body[2] | (body[3] << 8)
        if cid != ATT_CID:
            continue
        att = body[4:4 + l2cap_len]
        if len(att) < 3:
            continue
        opcode = att[0]
        handle = att[1] | (att[2] << 8)
        value = att[3:]
        if opcode in (ATT_WRITE_CMD, ATT_WRITE_REQ):
            direction = "W"
        elif opcode == ATT_NOTIFY:
            direction = "N"
        else:
            continue
        yield from split_aa_frames(direction, handle, value)
        return


def split_aa_frames(direction: str, handle: int, value: bytes):
    """Split a byte buffer on AA frame boundaries using the length field."""
    i = 0
    while i + 3 <= len(value):
        if (value[i] & 0xFF) != AA:
            i += 1
            continue
        cmd = value[i + 1] & 0xFF
        two_byte_len = cmd == 0x06
        header = 4 if two_byte_len else 3
        if i + header > len(value):
            return
        length = (value[i + 2] & 0xFF)
        if two_byte_len:
            length |= (value[i + 3] & 0xFF) << 8
        end = i + header + length
        if end > len(value) or length > 512:
            i += 1
            continue
        frame = value[i:end]
        yield direction, handle, frame
        i = end


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", required=True, type=Path)
    ap.add_argument("--out", dest="out", required=True, type=Path)
    ap.add_argument("--notes", dest="notes", required=False, type=Path)
    args = ap.parse_args()

    if not args.src.is_file():
        print(f"Input not found: {args.src}", file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)

    rows = []
    for rel, packet, _flags in read_records(args.src):
        for direction, handle, frame in att_frames_from_packet(packet):
            rows.append({
                "ts_relative": f"{rel:.6f}",
                "direction": direction,
                "handle": f"ATT_0x{handle:04X}",
                "frame_hex": frame.hex().upper(),
                "command": f"{frame[1]:02X}",
                "length": str(frame[2]),
                "payload": frame[3:].hex().upper(),
                "note": "",
            })

    if not rows:
        print("No AA frames found. Check that HCI snoop was enabled before the "
              "official app was operated, and that the file is a btsnoop log.")
        return 2

    # Parse optional "HH:MM:SS | operation" notes and annotate by nearest time.
    if args.notes and args.notes.is_file():
        marks = []
        for line in args.notes.read_text(encoding="utf-8").splitlines():
            m = re.match(r"\s*(\d{2}):(\d{2}):(\d{2})\s*\|\s*(.+)", line)
            if m:
                secs = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
                marks.append((secs, m.group(4).strip()))
        if marks:
            base = min(r["ts_relative"] for r in rows)
            for r in rows:
                t = float(r["ts_relative"]) - float(base)
                closest = min(marks, key=lambda x: abs(x[0] - t)) if marks else None
                if closest:
                    r["note"] = redact(closest[1])

    fields = ["ts_relative", "direction", "handle", "frame_hex",
              "command", "length", "payload", "note"]
    all_path = args.out / "frames_all.csv"
    with all_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    # Group into per-feature diffs based on the operation note.
    groups: dict[str, list[dict]] = {}
    for r in rows:
        key = "battery"
        n = r["note"].lower()
        if "eq" in n:
            key = "eq"
        elif "ambient" in n or "talkthru" in n or "voiceaware" in n:
            key = "ambient"
        elif "anc" in n or "降噪" in n:
            key = "anc"
        elif "gesture" in n or "手势" in n:
            key = "gesture"
        elif "find" in n or "查找" in n:
            key = "find"
        groups.setdefault(key, []).append(r)
    for key, group_rows in groups.items():
        p = args.out / f"diff_{key}.csv"
        with p.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            w.writerows(group_rows)
        print(f"  {p.name}: {len(group_rows)} frames")

    print(f"Extracted {len(rows)} AA frames -> {all_path}")
    print("Only ATT payloads were written; HCI addresses and pairing material were dropped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
