#!/usr/bin/env python3
"""Prints the tables of app/src/main/java/org/cortex/terminal/emulator/CharWidth.kt.

The wide ranges are the code points that Unicode's East Asian Width table (from Python's unicodedata)
gives W (wide) or F (fullwidth): Chinese, Japanese and Korean text, the fullwidth forms and the emoji
that are drawn two cells wide. The zero-width ones are the invisible format characters and the variation
selectors. This is what glibc's wcwidth() answers too, which the programs in the terminal use to count
columns, so the terminal must count the same way.

  python3 tests/scripts/gen_char_width.py            # print the tables
  python3 tests/scripts/gen_char_width.py --check    # exit 1 when CharWidth.kt has other tables

Use a Python with Unicode 15.1 (3.13); an older one has older tables and --check will differ.
"""
import re
import sys
import unicodedata
from pathlib import Path

KOTLIN = Path(__file__).resolve().parents[2] / "app/src/main/java/org/cortex/terminal/emulator/CharWidth.kt"

# Invisible characters that take no cell: zero width space and joiners, the direction marks, the word
# joiner and invisible operators, the byte order mark, the variation selectors
ZERO = [
    (0x200B, 0x200F), (0x202A, 0x202E), (0x2060, 0x2064), (0x2066, 0x206F),
    (0xFE00, 0xFE0F), (0xFEFF, 0xFEFF), (0xE0100, 0xE01EF),
]


def wide_ranges():
    out, start = [], None
    for cp in range(0x110000):
        ok = not (0xD800 <= cp <= 0xDFFF) and unicodedata.east_asian_width(chr(cp)) in ("W", "F")
        if ok and start is None:
            start = cp
        if not ok and start is not None:
            out.append((start, cp - 1))
            start = None
    if start is not None:
        out.append((start, 0x10FFFF))
    return out


def table(name, ranges):
    lines, row = [], []
    for lo, hi in ranges:
        row.append(f"0x{lo:X}, 0x{hi:X}")
        if len(row) == 4:
            lines.append("        " + ", ".join(row) + ",")
            row = []
    if row:
        lines.append("        " + ", ".join(row) + ",")
    lines[-1] = lines[-1].rstrip(",")
    return f"    private val {name} = intArrayOf(\n" + "\n".join(lines) + "\n    )\n"


def tables():
    return table("WIDE", wide_ranges()) + "\n" + table("ZERO", ZERO)


if __name__ == "__main__":
    text = tables()
    if "--check" in sys.argv:
        src = KOTLIN.read_text()
        sys.exit(0 if text in src else 1)
    print(f"// Unicode {unicodedata.unidata_version}")
    print(text)
