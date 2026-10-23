"""Validate a deck made with templates / text mode against the manifest the exporter wrote.

    python scripts/validate_deck.py deck.pptx manifest.json [--render]

Checks (python-pptx): 16:9 size; slide kinds in order (question / blank / passage); every shape of a question
slide (also inside the invisible group) lies inside the allowed box; text >= min font size; the template elements
and the background colour are on the slide layout.
--render: converts the deck with LibreOffice (soffice) and checks with PyMuPDF that the RENDERED text of each
question slide stays inside its box (catches overflow the size estimate missed) and that no glyph overlaps a figure.
"""
import json
import os
import subprocess
import sys
import tempfile

from pptx import Presentation
from pptx.util import Emu

TOL = 0.03


def shapes_flat(shapes):
    for s in shapes:
        if s.shape_type == 6:  # group
            yield from shapes_flat(s.shapes)
        else:
            yield s


def main():
    deck, manifest = sys.argv[1], sys.argv[2]
    render = "--render" in sys.argv
    m = json.load(open(manifest))
    prs = Presentation(deck)
    errors = []
    W, H = Emu(prs.slide_width).inches, Emu(prs.slide_height).inches
    if abs(W - 13.333) > 0.01 or abs(H - 7.5) > 0.01:
        errors.append(f"slide size {W:.3f} x {H:.3f}")
    recs = m["slides"]
    if len(recs) != len(prs.slides):
        errors.append(f"{len(prs.slides)} slides, manifest has {len(recs)}")
    layout = prs.slide_layouts[6]
    lay_texts = [s.text_frame.text for s in layout.shapes if s.has_text_frame and s.text_frame.text.strip() and not s.is_placeholder]
    for want in m.get("template_texts", []):
        if want not in lay_texts:
            errors.append(f"template text {want!r} not on the slide layout (found {lay_texts})")
    bg = layout.background.fill
    if m.get("bg") and (bg.type != 1 or str(bg.fore_color.rgb) != m["bg"].lstrip("#").upper()):
        errors.append(f"layout background is not {m['bg']}")
    min_pt = m.get("min_pt", 0)
    for i, (slide, r) in enumerate(zip(prs.slides, recs), 1):
        flat = list(shapes_flat(slide.shapes))
        content = [s for s in flat if s.shape_type == 13 or (s.has_text_frame and s.text_frame.text.strip())]
        if r["kind"] == "blank":
            if content:
                errors.append(f"slide {i}: should be blank")
            continue
        if not content:
            errors.append(f"slide {i}: {r['kind']} slide is empty")
        x, y, w, h = r["box"]
        for s in content:
            l, t = Emu(s.left).inches, Emu(s.top).inches
            rr, b = l + Emu(s.width).inches, t + Emu(s.height).inches
            if l < x - TOL or t < y - TOL or rr > x + w + TOL or b > y + h + TOL:
                errors.append(f"slide {i}: shape {s.name!r} ({l:.2f},{t:.2f})-({rr:.2f},{b:.2f}) outside box ({x:.2f},{y:.2f})-({x + w:.2f},{y + h:.2f})")
            if s.has_text_frame and r["kind"] == "question":
                for p in s.text_frame.paragraphs:
                    for run in p.runs:
                        if run.font.size is not None and run.text.strip() and run.font.size.pt < min_pt - 0.01 and not run.font.italic and run.font._rPr.get("baseline") is None:
                            errors.append(f"slide {i}: text {run.font.size.pt}pt < {min_pt}pt")
                            break
    if render and not errors:
        errors += rendered_check(deck, recs)
    print(f"{deck}: {len(prs.slides)} slides; {sum(1 for r in recs if r['kind'] == 'question')} questions, "
          f"{sum(1 for r in recs if r['kind'] == 'question' and r['text'])} as text, {sum(1 for r in recs if r['kind'] == 'passage')} passage slides")
    for e in errors:
        print("  FAIL", e)
    print("OK" if not errors else f"{len(errors)} problem(s)")
    sys.exit(1 if errors else 0)


def rendered_check(deck, recs):
    import pymupdf as fitz
    errs = []
    with tempfile.TemporaryDirectory() as d:
        subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", d, deck], check=True, capture_output=True, timeout=600)
        pdf = fitz.open(os.path.join(d, os.path.splitext(os.path.basename(deck))[0] + ".pdf"))
        if pdf.page_count != len(recs):
            return [f"LibreOffice rendered {pdf.page_count} pages, expected {len(recs)}"]
        for i, (page, r) in enumerate(zip(pdf, recs), 1):
            if r["kind"] == "blank" or not r["text"]:
                continue
            k = page.rect.width / 13.333
            x, y, w, h = [v * k for v in r["box"]]
            imgs = [fitz.Rect(b["bbox"]) for b in page.get_text("dict")["blocks"] if b["type"] == 1]
            for b in page.get_text("rawdict")["blocks"]:
                if b["type"] != 0:
                    continue
                for ln in b["lines"]:
                    for sp in ln["spans"]:
                        chars = [c for c in sp["chars"] if c["c"].strip()]
                        if not chars:
                            continue
                        sp["text"] = "".join(c["c"] for c in sp["chars"])
                        bx = fitz.Rect(chars[0]["bbox"])
                        for c in chars[1:]:
                            bx |= fitz.Rect(c["bbox"])
                        inside_any_template = any(bx.intersects(fitz.Rect(tb[0] * k, tb[1] * k, (tb[0] + tb[2]) * k, (tb[1] + tb[3]) * k)) for tb in r.get("template_boxes", []))
                        if inside_any_template:
                            continue
                        if bx.x0 < x - TOL * k or bx.y0 < y - TOL * k or bx.x1 > x + w + TOL * k or bx.y1 > y + h + TOL * k:
                            errs.append(f"slide {i}: rendered text {sp['text'][:30]!r} at {tuple(round(v / k, 2) for v in bx)} outside the box")
                            break
                        for im in imgs:
                            ov = bx & im
                            if not ov.is_empty and ov.width * ov.height > 0.3 * bx.width * bx.height:
                                errs.append(f"slide {i}: text {sp['text'][:30]!r} overlaps a figure")
                                break
    return errs


if __name__ == "__main__":
    main()
