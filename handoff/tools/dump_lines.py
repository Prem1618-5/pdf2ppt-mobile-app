"""Dump per-page text lines + figure boxes of digital PDFs to JSON (legacy code, read-only).

    python dump_lines.py <legacy_repo_dir> <fixtures_dir> <out_dir>

Output  <out_dir>/<name>.lines.json  =
  {"pages": [{"w_in":..,"h_in":..,"kind":"pdf-digital"|"pdf-scan",
              "lines": [[x0,y0,x1,y1,"text",size], ...],      # fractions of the page, as in ingest.py
              "figs":  [[x0,y0,x1,y1], ...]}]}

Purpose: JVM unit tests of the Kotlin layout parser + assembler can start from these lines, so
porting bugs in `layout_classical` / `assemble` / `reading_order` are found WITHOUT needing a PDF
library on the test JVM. (On the phone the same structure comes from PDF text extraction or, for
scans and photos, from on-device OCR.)
"""
import json
import os
import sys

sys.dont_write_bytecode = True   # never write __pycache__ into the read-only legacy repo

legacy, fixtures, out = (os.path.abspath(a) for a in sys.argv[1:4])
sys.path.insert(0, legacy)
os.makedirs(out, exist_ok=True)

from qdeck.ingest import iter_pages     # noqa: E402

for name in sorted(os.listdir(fixtures)):
    if not name.lower().endswith(".pdf") or name in ("password.pdf", "corrupt.pdf"):
        continue
    pages = []
    for p in iter_pages([os.path.join(fixtures, name)]):
        pages.append({"w_in": round(p.width_in, 3), "h_in": round(p.height_in, 3), "kind": p.kind,
                      "lines": [[round(v, 5) for v in l[:4]] + [l[4], round(l[5], 2)] for l in p.text_lines()],
                      "figs": [[round(v, 5) for v in f] for f in p.figure_boxes()]})
    with open(os.path.join(out, name.replace(".pdf", ".lines.json")), "w", encoding="utf-8") as f:
        json.dump({"pages": pages}, f, ensure_ascii=False)
    print(name, [(len(p["lines"]), p["kind"]) for p in pages])
