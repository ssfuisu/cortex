#!/usr/bin/env python3
"""Makes the README's pictures (docs/screenshots/*.jpg) from the screenshots of an emulator run.

The emulator test (tests/e2e/run-emulator-test.sh) takes the screens in each run; this picks the ones
the README shows, cuts the two that are only a part of the screen, and saves them as JPEG. Use the
run of the APK that is released (the API 34 job, whose files are the artifact emulator-test-results-api34):

    gh run download RUN_ID -n emulator-test-results-api34 -D e2e-out
    python3 tests/e2e/make-readme-screenshots.py e2e-out/screens docs/screenshots

Needs Pillow (pip install pillow). Look at the pictures before they are committed: the places are
found by position, which moves when a screen changes.
"""
import os
import sys

from PIL import Image

# README name, the screen of the run, the part of it (left, top, right, bottom) or None for all of it
PICTURES = [
    ("cortex-neofetch", "readme-neofetch", None),
    ("cortex-keyboard", "05-test-results", None),
    ("cortex-api", "10-cortex-api", None),
    ("cortex-sessions", "12-sessions", None),
    ("cortex-themes", "13a-theme-picker", None),
    # The quick settings and the Cortex notification, without the emulator's own notifications below
    ("cortex-notification", "11-notifications", (0, 0, 1080, 960)),
    # The "Root" heading and the Root mode switch, at the end of the settings
    ("cortex-root-mode", "15z-settings-bottom", (0, 1320, 1080, 1750)),
]


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    screens, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    missing = 0
    for name, screen, box in PICTURES:
        path = os.path.join(screens, screen + ".png")
        if not os.path.exists(path):
            print("missing:", path)
            missing += 1
            continue
        image = Image.open(path).convert("RGB")
        if box:
            image = image.crop(box)
        target = os.path.join(out, name + ".jpg")
        image.save(target, "JPEG", quality=85, optimize=True, progressive=True)
        print(f"{target}: {image.width}x{image.height}, {os.path.getsize(target) // 1024} KB")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
