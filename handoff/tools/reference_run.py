"""Run the LEGACY Python pipeline (read-only) on the fixtures and print what it finds.

    python reference_run.py <legacy_repo_dir> <fixtures_dir> <scratch_dir>

It never writes inside the legacy repo: all output goes to <scratch_dir>. Use the numbers
as the *golden* result the Android port must match (same question numbers, same flags)
for the text-layer tier. LLM is switched off, so this also shows what the no-API path does.
"""
import json
import os
import sys

sys.dont_write_bytecode = True   # never write __pycache__ into the read-only legacy repo

legacy, fixtures, scratch = (os.path.abspath(a) for a in sys.argv[1:4])
os.makedirs(scratch, exist_ok=True)
os.environ["CACHE_DIR"] = os.path.join(scratch, ".cache")
os.environ["USE_LLM"] = "0"
sys.path.insert(0, legacy)
os.chdir(scratch)

from qdeck.config import Settings          # noqa: E402
from qdeck.pipeline import run_job         # noqa: E402

results = {}
for name in sorted(os.listdir(fixtures)):
    if not name.lower().endswith((".pdf", ".jpg", ".png")):
        continue
    out = os.path.join(scratch, "out_" + os.path.splitext(name)[0])
    try:
        r = run_job([os.path.join(fixtures, name)], out, Settings(), options={"layout": "top-right"},
                    log=lambda *a: print(*a, file=sys.stderr))
        proj = json.load(open(r["project"], encoding="utf-8"))
        results[name] = {"slides": r["slides"], "pptx_slides": r["pptx_slides"],
                         "numbers": [it["number"] for it in proj["items"]],
                         "flags": sorted({f for it in proj["items"] for f in it["flags"]}),
                         "integrity_ok": r["integrity"].get("ok"), "summary": r["summary"]}
    except Exception as e:                                   # the error cases are expected for some fixtures
        results[name] = {"error": f"{type(e).__name__}: {str(e)[:120]}"}
print(json.dumps(results, indent=2, ensure_ascii=False))
