#!/usr/bin/env python3
"""Copies strings from the iOS app's Localization/*.strings into the Android resources.

usage: import_ios_strings.py KEY[=android_name] ...
       import_ios_strings.py -f keys.txt

For each iOS key it writes `<string name="snake_case_key">` into core/src/main/res/values*/strings.xml
for the eight languages (en is `values/`). Names already present on Android are left alone (reported as
skipped); keys missing in English are reported. `%@` becomes `%s` (positional when there are several),
`%lld` becomes `%d`. A language missing a key falls back to English (the Android default resource), so
nothing is written for it. Safe to re-run.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IOS = "/Users/laurent/dev/OverlandMapIos/Packages/OverlandMapCore/Sources/OverlandMapKit/Resources/Localization"
RES = os.path.join(ROOT, "src/main/res")
LANGS = ["en", "fr", "de", "es", "it", "nl", "pt", "ru"]

LINE = re.compile(r'^"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)"\s*;\s*$')


def load_ios(lang):
    out = {}
    path = os.path.join(IOS, f"{lang}.strings")
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = LINE.match(line.strip())
            if m:
                out[m.group(1)] = m.group(2)
    return out


def snake(key):
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", key)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def to_android(value):
    # Unescape the .strings escapes first, then escape for Android XML.
    v = value.replace('\\"', '"').replace("\\\\", "\\")
    placeholders = len(re.findall(r"%(?:\d+\$)?(?:@|lld|ld|d|s)", v))
    counter = [0]

    def fmt(m):
        spec = m.group(0)
        if "$" in spec:
            return spec.replace("@", "s").replace("lld", "d").replace("ld", "d")
        counter[0] += 1
        kind = "s" if spec == "%@" else "d"
        return f"%{counter[0]}${kind}" if placeholders > 1 else f"%{kind}"

    v = re.sub(r"%(?:\d+\$)?(?:@|lld|ld|d|s)", fmt, v)
    v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    v = v.replace("'", "\\'").replace('"', '\\"')
    if v.startswith("@") or v.startswith("?"):
        v = "\\" + v
    return v


def existing_names(path):
    if not os.path.exists(path):
        return set()
    with open(path, encoding="utf-8") as f:
        return set(re.findall(r'<string name="([^"]+)"', f.read()))


def main(argv):
    pairs = []
    if argv[:1] == ["-f"]:
        with open(argv[1]) as f:
            argv = [l.strip() for l in f if l.strip() and not l.startswith("#")]
    for a in argv:
        key, _, name = a.partition("=")
        pairs.append((key, name or snake(key)))

    ios = {lang: load_ios(lang) for lang in LANGS}
    all_names = set()
    for lang in LANGS:
        d = "values" if lang == "en" else f"values-{lang}"
        all_names |= existing_names(os.path.join(RES, d, "strings.xml"))

    added, skipped, missing = [], [], []
    for key, name in pairs:
        if key not in ios["en"]:
            missing.append(key)
            continue
        if name in all_names:
            skipped.append(f"{key} -> {name}")
            continue
        added.append(f"{key} -> {name}")
        for lang in LANGS:
            value = ios[lang].get(key)
            if value is None:
                continue
            d = "values" if lang == "en" else f"values-{lang}"
            path = os.path.join(RES, d, "strings.xml")
            with open(path, encoding="utf-8") as f:
                xml = f.read()
            entry = f'    <string name="{name}">{to_android(value)}</string>\n'
            xml = xml.replace("</resources>", entry + "</resources>")
            with open(path, "w", encoding="utf-8") as f:
                f.write(xml)

    print("added  :", len(added))
    for a in added:
        print("   +", a)
    print("skipped (name exists):", len(skipped))
    for s in skipped:
        print("   =", s)
    print("missing in iOS en.strings:", missing)


if __name__ == "__main__":
    main(sys.argv[1:])
