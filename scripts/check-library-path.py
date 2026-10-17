#!/usr/bin/env python3
"""Checks that every place that spells out the rootfs library search path uses the same
directories in the same order (stdlib only, run by tests/scripts/test_shell_scripts.sh):

  * LibraryPath.kt        the app's session environment and /etc/profile.d/00-env.sh
  * cortex-hook.c         k_rootfs_lib_dirs, used for the programs the hook starts
  * su.sh                 CORTEX_LD
  * setup-extra-rootfs.sh CORTEX_LD and the LD_LIBRARY_PATH exported into extra roots

The order matters for speed: ld.so tries every directory for every library, so the multiarch
directories that hold nearly all libraries must come first.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read(rel):
    with open(os.path.join(ROOT, rel), encoding='utf-8') as f:
        return f.read()


def dirs_from_shell_value(value):
    return [p.split('}', 1)[-1] if '}' in p else p.replace('$CORTEX_ROOT', '', 1) for p in value.split(':')]


errors = []
sources = {}

kt = read('app/src/main/java/org/cortex/terminal/runtime/LibraryPath.kt')
m = re.search(r'val DIRS = listOf\((.*?)\)', kt, re.S)
sources['LibraryPath.kt'] = re.findall(r'"([^"]+)"', m.group(1)) if m else []

c = read('app/src/main/cpp/cortex-hook.c')
m = re.search(r'k_rootfs_lib_dirs\[\]\s*=\s*\{(.*?)\};', c, re.S)
sources['cortex-hook.c'] = re.findall(r'"([^"]+)"', m.group(1)) if m else []

su = read('app/src/main/assets/scripts/su.sh')
m = re.search(r'^CORTEX_LD="([^"]+)"', su, re.M)
sources['su.sh'] = dirs_from_shell_value(m.group(1)) if m else []

extra = read('scripts/setup-extra-rootfs.sh')
for n, m in enumerate(re.finditer(r'(?:^CORTEX_LD="|LD_LIBRARY_PATH=[\'"])(\$CORTEX_ROOT/[^"\']+)', extra, re.M), 1):
    sources[f'setup-extra-rootfs.sh #{n}'] = dirs_from_shell_value(m.group(1))

reference = sources.get('LibraryPath.kt', [])
if len(reference) != 13:
    errors.append(f'LibraryPath.kt lists {len(reference)} directories, expected 13')
if not reference or not reference[0].endswith('/aarch64-linux-gnu'):
    errors.append('the aarch64 multiarch directory must come first')
if len(sources) < 5:
    errors.append(f'found only {len(sources)} places that define the library path: {sorted(sources)}')
for name, dirs in sources.items():
    if dirs != reference:
        errors.append(f'{name} differs from LibraryPath.kt:\n    {dirs}\n    {reference}')

print(f'checked {len(sources)} library path definitions ({len(reference)} directories each)')
if errors:
    for e in errors:
        print('ERROR:', e)
    sys.exit(1)
print('library paths OK')
