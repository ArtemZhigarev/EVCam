"""
English UI for the AppsForMyCar EVCam fork.

Upstream EVCam (suyunkai/EVCam) is Chinese-only with the text hard-coded in layouts and Java. This
script swaps that text for English using zh-en.json, in place, then applies the small source
fix-ups in fixups.json. Rerun it after every `git subtree pull` of upstream; it prints any Chinese
UI text it has no translation for (add those to zh-en.json and rerun).

What it translates (when zh-en.json has the text):
- layout attributes (text, hint, contentDescription, title, summary),
- <string>/<item> text in res/values,
- Java string literals,
- exception messages (`throw new ...("...")`): they reach the screen through e.getMessage().
  Ones without a translation are left alone and not reported.

Never translated, on purpose:
- log messages (developer-only),
- comments, including comments inside embedded shader/JavaScript code,
- strings used for matching: equals/contains/startsWith/endsWith/matches/indexOf/split/replace,
  Pattern.compile and switch `case` labels — translating those would break command parsing
  (bot commands) and status checks. Where the app matches against text it produces itself (now
  English), the match is fixed by an entry in fixups.json instead.
- text whose translation in zh-en.json is the text itself (people's names).

fixups.json: [{"file": path under app/src/main, "find": exact text, "replace": new text,
"why": ...}], applied after the translation, so "find" is the text as it is once translated.
For things a word-for-word swap can't do: matching against translated text, Chinese date
formats, and phrases glued together from pieces. An entry whose "find" and "replace" are both
missing from its file is reported as stale (upstream changed that line — check it by hand).

Usage: python android/evcam/afmc-i18n/apply.py [--dry-run]
"""
import glob
import io
import json
import os
import re
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')  # Windows consoles can't print Chinese

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(HERE, '..', 'app', 'src', 'main')
DRY = '--dry-run' in sys.argv

# Chinese characters, Chinese punctuation and full-width forms (）「」、－＋ ...).
HAN = re.compile('[一-鿿　-〿＀-￯]')
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
LOGGY = re.compile(r'AppLog\.|Log\.[dewiv]\(|logger\.|\.log\(')
EXCEPTION = re.compile(r'Exception\(|throw new')
EMBEDDED_COMMENT = re.compile(r'(?:^|;)\s*//')  # a comment line inside shader/JavaScript source
MATCH_BEFORE = re.compile(r'(?:\b(?:equals|equalsIgnoreCase|contains|startsWith|endsWith|matches|compile|indexOf|'
                          r'lastIndexOf|split|replace|replaceAll|replaceFirst)\s*\(\s*|\bcase\s+)$')
MATCH_AFTER = re.compile(r'^\s*\.\s*(?:equals|equalsIgnoreCase|contains|startsWith|endsWith)\s*\(')
LAYOUT_ATTR = re.compile(r'(android:(?:text|hint|contentDescription|title|summary)=")([^"@][^"]*)(")')
VALUES_TEXT = re.compile(r'(<(string|item)\b[^>]*>)([^<]*)(</\2>)')

zh_en = json.load(io.open(os.path.join(HERE, 'zh-en.json'), encoding='utf-8'))
fixups = json.load(io.open(os.path.join(HERE, 'fixups.json'), encoding='utf-8'))
missing = {}
changed_files = 0
replaced = 0


def xml_value(en: str) -> str:
    # Layout attribute values go through aapt's string processing: escape apostrophes.
    return en.replace("\\'", "'").replace("'", "\\'")


for f in sorted(glob.glob(os.path.join(MAIN, 'res', '**', '*.xml'), recursive=True)):
    s = io.open(f, encoding='utf-8', newline='').read()

    def sub(m):
        global replaced
        val = m.group(m.lastindex - 1)
        if not HAN.search(val):
            return m.group(0)
        if val in zh_en:
            if zh_en[val] == val:
                return m.group(0)
            replaced += 1
            return m.group(1) + xml_value(zh_en[val]) + m.group(m.lastindex)
        missing.setdefault(val, f)
        return m.group(0)

    t = (VALUES_TEXT if os.sep + 'values' in f else LAYOUT_ATTR).sub(sub, s)
    if t != s:
        changed_files += 1
        if not DRY:
            io.open(f, 'w', encoding='utf-8', newline='\n').write(t)

for f in sorted(glob.glob(os.path.join(MAIN, 'java', '**', '*.java'), recursive=True)):
    lines = io.open(f, encoding='utf-8', newline='').read().split('\n')
    out = []
    file_changed = False
    in_block_comment = False
    for line in lines:
        t = line.strip()
        if in_block_comment:
            out.append(line)
            if '*/' in t:
                in_block_comment = False
            continue
        if t.startswith('/*') and '*/' not in t:
            in_block_comment = True
            out.append(line)
            continue
        if t.startswith(('//', '*', '/*')) or LOGGY.search(t):
            out.append(line)
            continue
        is_exception = bool(EXCEPTION.search(t))
        code_end = line.find('//') if '//' in line and '"' not in line[line.find('//'):] else len(line)
        new = []
        pos = 0
        for m in LIT.finditer(line):
            if m.start() >= code_end:
                break
            lit = m.group(1)
            if not HAN.search(lit) or EMBEDDED_COMMENT.search(lit):
                continue
            before = line[:m.start()]
            after = line[m.end():]
            if MATCH_BEFORE.search(before) or MATCH_AFTER.search(after):
                continue
            if lit in zh_en:
                if zh_en[lit] == lit:
                    continue
                new.append(line[pos:m.start()] + '"' + zh_en[lit] + '"')
                pos = m.end()
                replaced += 1
            elif not is_exception:
                missing.setdefault(lit, f)
        if new:
            line = ''.join(new) + line[pos:]
            file_changed = True
        out.append(line)
    if file_changed:
        changed_files += 1
        if not DRY:
            io.open(f, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))

print(f"{'would replace' if DRY else 'replaced'} {replaced} strings in {changed_files} files")

fixed = 0
stale = []
for fx in fixups:
    if fx['find'] in fx['replace']:
        sys.exit(f"fixups.json: \"find\" must not be part of \"replace\": {fx['find']}")
    path = os.path.join(MAIN, *fx['file'].split('/'))
    s = io.open(path, encoding='utf-8', newline='').read() if os.path.exists(path) else ''
    if fx['find'] in s:
        fixed += s.count(fx['find'])
        if not DRY:
            io.open(path, 'w', encoding='utf-8', newline='\n').write(s.replace(fx['find'], fx['replace']))
    elif fx['replace'] not in s:
        stale.append(fx)
print(f"{'would apply' if DRY else 'applied'} {fixed} fix-ups")
if stale:
    print(f"{len(stale)} fix-ups no longer match their file (upstream changed the line? check by hand):")
    for fx in stale:
        print('  ', fx['file'], ':', fx['find'])

if missing:
    print(f"{len(missing)} Chinese UI strings have no translation yet (add to zh-en.json):")
    for k, f in list(missing.items())[:40]:
        print('  ', k, '  <-', os.path.relpath(f, MAIN))
