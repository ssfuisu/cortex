#!/usr/bin/env python3
"""compat.sh's results.txt as the JSON the website's compatibility page reads (docs/compat.json).

    compat-json.py RESULTS VERSION DATE > docs/compat.json
"""
import json
import re
import sys

results, version, date = sys.argv[1], sys.argv[2], sys.argv[3]
packages = []
with open(results, encoding="utf-8", errors="replace") as f:
    for line in f:
        m = re.match(r"^(PASS|FAIL) (.+?) \((\d+) s\)(?:: (.*))?$", line.rstrip("\n"))
        if not m:
            continue
        state, name, seconds, why = m.groups()
        packages.append({
            "name": name,
            "ok": state == "PASS",
            "seconds": int(seconds),
            "why": " ".join((why or "").split())[:300],
        })
if not packages:
    sys.exit("no results in " + results)
json.dump({
    "version": version,
    "date": date,
    "where": "Android 14 emulator (API 34), arm64-v8a APK",
    "packages": packages,
}, sys.stdout, indent=1, ensure_ascii=False)
sys.stdout.write("\n")
