#!/usr/bin/env python3
"""Consistency checks for the app's translations (stdlib only, run by tests/scripts/test_shell_scripts.sh).

  * every language offered in Settings (res/xml/locales_config.xml, res/values/arrays.xml) has a
    res/values-<qualifier>/strings.xml, and nothing else does;
  * each translation defines exactly the translatable keys of res/values/strings.xml;
  * format placeholders (%1$s, %2$d, %%) and newlines match the English text, so a translation can
    never crash String.format or break a dialog layout.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'

# BCP-47 tag -> resource qualifier (Android still uses the legacy codes for these two)
QUALIFIER = {'zh-CN': 'zh-rCN', 'zh-TW': 'zh-rTW', 'pt-BR': 'pt-rBR', 'id': 'in', 'he': 'iw'}
PLACEHOLDER = re.compile(r'%(?:\d\$[sd]|%)')

errors = []


def fail(msg):
    errors.append(msg)


def strings(path):
    out = {}
    for e in ET.parse(path).getroot().findall('string'):
        out[e.get('name')] = (e.text or '', e.get('translatable') != 'false')
    return out


def array_items(path, name):
    for e in ET.parse(path).getroot().findall('string-array'):
        if e.get('name') == name:
            return [(i.text or '') for i in e.findall('item')]
    return []


english = {k: v for k, (v, translatable) in strings(os.path.join(RES, 'values', 'strings.xml')).items() if translatable}

locales = [e.get(ANDROID_NS + 'name') for e in ET.parse(os.path.join(RES, 'xml', 'locales_config.xml')).getroot().findall('locale')]
if len(locales) != len(set(locales)):
    fail('locales_config.xml lists a language twice')

values = array_items(os.path.join(RES, 'values', 'arrays.xml'), 'language_values')
entries = array_items(os.path.join(RES, 'values', 'arrays.xml'), 'language_entries')
if values[:1] != ['system']:
    fail("language_values must start with 'system'")
if values[1:] != locales:
    fail('language_values (arrays.xml) and locales_config.xml list different languages or a different order')
if len(entries) != len(values):
    fail(f'language_entries has {len(entries)} items, language_values has {len(values)}')

expected_folders = {'values-' + QUALIFIER.get(tag, tag) for tag in locales if tag != 'en'}
actual_folders = {d for d in os.listdir(RES) if d.startswith('values-') and os.path.isfile(os.path.join(RES, d, 'strings.xml'))}
for missing in sorted(expected_folders - actual_folders):
    fail(f'{missing}/strings.xml is missing')
for extra in sorted(actual_folders - expected_folders):
    fail(f'{extra}/strings.xml exists but the language is not offered in locales_config.xml')

for folder in sorted(actual_folders & expected_folders):
    path = os.path.join(RES, folder, 'strings.xml')
    translated = strings(path)
    for key in sorted(set(english) - set(translated)):
        fail(f'{folder}: missing string {key}')
    for key in sorted(set(translated) - set(english)):
        fail(f'{folder}: unknown or non-translatable string {key}')
    for key, (text, _) in translated.items():
        if key not in english:
            continue
        en = english[key].replace('\\n', '\n')
        tr = text.replace('\\n', '\n')
        if sorted(PLACEHOLDER.findall(en)) != sorted(PLACEHOLDER.findall(tr)):
            fail(f'{folder}: {key}: placeholders differ from English ({PLACEHOLDER.findall(en)} vs {PLACEHOLDER.findall(tr)})')
        if en.count('\n') != tr.count('\n'):
            fail(f'{folder}: {key}: newline count differs from English')
        if not tr.strip():
            fail(f'{folder}: {key}: empty translation')

print(f'checked {len(english)} strings in {len(actual_folders & expected_folders)} translations, {len(locales)} languages offered')
if errors:
    for e in errors:
        print('ERROR:', e)
    sys.exit(1)
print('translations OK')
