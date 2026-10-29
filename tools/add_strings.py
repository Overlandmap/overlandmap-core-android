#!/usr/bin/env python3
"""Appends hand-written strings to the eight Android locales: add_strings.py file.py
where file.py defines S = {name: {lang: text}}; names already present are skipped."""
import os, sys, runpy
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/res")
LANGS = ["en", "fr", "de", "es", "it", "nl", "pt", "ru"]
S = runpy.run_path(sys.argv[1])["S"]
def esc(v):
    v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'").replace('"', '\\"')
    return "\\" + v if v[:1] in "@?" else v
for lang in LANGS:
    path = os.path.join(RES, "values" if lang == "en" else f"values-{lang}", "strings.xml")
    text = open(path, encoding="utf-8").read()
    add = []
    for name, tr in S.items():
        if f'name="{name}"' in text:
            print("skip", name); continue
        add.append(f'    <string name="{name}">{esc(tr.get(lang) or tr["en"])}</string>\n')
    if add:
        text = text.replace("</resources>", "".join(add) + "</resources>")
        open(path, "w", encoding="utf-8").write(text)
print("done")
