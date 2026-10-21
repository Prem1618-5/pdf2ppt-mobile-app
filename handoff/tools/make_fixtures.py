"""Generate synthetic, copyright-free test inputs for the Android port.

    python make_fixtures.py ../fixtures

Every fixture is built BY CONSTRUCTION, so the expected result is known exactly (see
fixtures/expected.json, written at the end). Requires: pip install pymupdf pillow

Fixtures
  digital_1col.pdf        1 column, 8 questions, 2 pages          -> 8 questions, 0 API calls
  digital_2col_split.pdf  2 columns, 30 questions, strict line flow so questions are split
                          across columns and across pages, running URL header, page-number
                          footer, ANSWER KEY page last             -> 30 questions
  scan_pages.pdf          the same paper rasterised (image-only PDF) -> needs OCR tier
  photo_exif_rot.jpg      page 1 as a phone photo: slight skew, noise, EXIF orientation 6
  garbage_textlayer.pdf   page image + an invisible, meaningless text layer (>200 chars)
                          -> classical parser must FAIL the integrity check and escalate
  password.pdf            AES-256 encrypted (user password 1234) -> graceful error
  corrupt.pdf             truncated file                          -> graceful error
"""
import io
import json
import os
import random
import sys

import pymupdf as fitz
from PIL import Image, ImageFilter

OUT = sys.argv[1] if len(sys.argv) > 1 else "fixtures"
os.makedirs(OUT, exist_ok=True)
random.seed(7)

STEMS = [
    "What is the capital of France?", "Solve: 12 x 8 = ?", "Which planet is known as the Red Planet?",
    "Water boils at what temperature at sea level in degrees Celsius?", "Who wrote the play Romeo and Juliet?",
    "The chemical symbol for gold is?", "A train 100 m long crosses a pole in 10 s. Its speed in km/h is?",
    "The largest ocean on Earth is the?", "Which gas do plants absorb for photosynthesis?",
    "The SI unit of force is?", "How many sides does a hexagon have?", "Which is the smallest prime number?",
    "The process of cell division in somatic cells is called?", "Which metal is liquid at room temperature?",
    "The square root of 169 is?", "Who is known as the Father of the Nation in India?",
    "Which organ pumps blood through the human body?", "The speed of light is approximately?",
    "What is 15 percent of 200?", "Which layer of the atmosphere contains the ozone layer?",
    "A fair coin is tossed twice. The probability of two heads is?", "The currency of Japan is?",
    "Which vitamin is produced when the skin is exposed to sunlight?", "The longest river in India is?",
    "What is the value of pi up to two decimal places?", "Which is the hardest natural substance?",
    "The capital of Australia is?", "How many bones are there in an adult human body?",
    "Which element has the atomic number 1?", "The first month of the Gregorian calendar is?",
]
OPTS = [["Berlin", "Paris", "Rome", "Madrid"], ["86", "96", "106", "98"], ["Venus", "Jupiter", "Mars", "Saturn"],
        ["90", "100", "110", "120"], ["Dickens", "Austen", "Shakespeare", "Twain"], ["Ag", "Au", "Gd", "Go"]]


def question_lines(n):
    """Lines of question n (number printed only on the first line)."""
    stem = STEMS[(n - 1) % len(STEMS)]
    words = stem.split()
    lines, cur = [], ""
    for w in words:                                   # wrap the stem at ~48 chars
        if len(cur) + len(w) + 1 > 48:
            lines.append(cur)
            cur = w
        else:
            cur = (cur + " " + w).strip()
    lines.append(cur)
    lines[0] = f"{n}. {lines[0]}"
    for i, o in enumerate(OPTS[(n - 1) % len(OPTS)]):
        lines.append(f"    ({'abcd'[i]}) {o}")
    return lines


def build_two_col(path, n_questions=30, answer_key=True):
    doc = fitz.open()
    cols = [50, 315]
    top, bottom, lh = 70, 790, 14.0
    flow = []                                          # (text, is_gap)
    for n in range(1, n_questions + 1):
        flow += [(t, False) for t in question_lines(n)] + [("", True)]
    page = doc.new_page()
    pno, col, y = 1, 0, top

    def furniture(pg, k):
        pg.insert_text((190, 30), "www.mockexam.example.com", fontsize=9)
        pg.insert_text((280, 820), f"Page {k}", fontsize=9)

    furniture(page, pno)
    for text, gap in flow:
        if y > bottom:
            col += 1
            y = top
            if col == 2:
                page = doc.new_page()
                pno += 1
                col, y = 0, top
                furniture(page, pno)
        if not gap:
            page.insert_text((cols[col], y), text, fontsize=10.5)
        y += lh * (0.6 if gap else 1.0)
    if answer_key:
        page = doc.new_page()
        pno += 1
        furniture(page, pno)
        page.insert_text((240, 70), "ANSWER KEY", fontsize=13)
        for i in range(n_questions):
            page.insert_text((60 + (i // 15) * 200, 110 + (i % 15) * 16), f"{i + 1}  {'ABCD'[i % 4]}", fontsize=10)
    doc.save(path)
    doc.close()


def rasterise(src, dpi=150, noise=True):
    """PDF -> list of PIL pages (white paper, mild noise like a scanner)."""
    out = []
    with fitz.open(src) as d:
        for p in d:
            pix = p.get_pixmap(matrix=fitz.Matrix(dpi / 72, dpi / 72), alpha=False)
            im = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
            if noise:
                im = im.filter(ImageFilter.GaussianBlur(0.6))
                px = im.load()
                for _ in range(int(im.width * im.height * 0.0008)):
                    x, y = random.randrange(im.width), random.randrange(im.height)
                    v = random.randrange(120, 230)
                    px[x, y] = (v, v, v)
            out.append(im)
    return out


def images_to_pdf(images, path, text_layer=None):
    doc = fitz.open()
    for im in images:
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=80)
        pg = doc.new_page(width=595, height=842)
        pg.insert_image(pg.rect, stream=buf.getvalue())
        if text_layer:                                  # invisible (render mode 3), wrapped inside the page
            words = text_layer.split()
            for k in range(0, len(words), 10):
                pg.insert_text((50, 100 + 12 * (k // 10)), " ".join(words[k:k + 10]), fontsize=9, render_mode=3)
    doc.save(path)
    doc.close()


def main():
    p1 = os.path.join(OUT, "digital_1col.pdf")
    doc = fitz.open()
    y, page = 70, doc.new_page()
    for n in range(1, 9):
        ls = question_lines(n)
        if y + 14 * (len(ls) + 1) > 780:
            page, y = doc.new_page(), 70
        for t in ls:
            page.insert_text((60, y), t, fontsize=11)
            y += 15
        y += 14
    doc.save(p1)
    doc.close()

    p2 = os.path.join(OUT, "digital_2col_split.pdf")
    build_two_col(p2)

    scans = rasterise(p2)
    images_to_pdf(scans, os.path.join(OUT, "scan_pages.pdf"))

    photo = scans[0].rotate(2.5, expand=True, fillcolor=(235, 232, 225)).convert("RGB")
    photo = photo.rotate(90, expand=True)              # stored sideways ...
    exif = Image.Exif()
    exif[0x0112] = 6                                   # ... EXIF says "rotate 90 CW to view"
    photo.save(os.path.join(OUT, "photo_exif_rot.jpg"), "JPEG", quality=78, exif=exif.tobytes())

    junk = " ".join(random.choice(["qzx", "vkj", "wpl", "mnb", "ghd", "xcv"]) + str(random.randrange(99)) for _ in range(80))
    images_to_pdf(scans[:2], os.path.join(OUT, "garbage_textlayer.pdf"), text_layer=junk)

    d = fitz.open(p1)
    d.save(os.path.join(OUT, "password.pdf"), encryption=fitz.PDF_ENCRYPT_AES_256, user_pw="1234", owner_pw="owner")
    d.close()
    with open(p1, "rb") as f:
        b = f.read()
    with open(os.path.join(OUT, "corrupt.pdf"), "wb") as f:
        f.write(b[: len(b) // 3])

    expected = {
        "digital_1col.pdf": {"questions": 8, "numbers": list(range(1, 9)), "api_calls": 0, "tier": "text-layer",
                             "legacy_python_result": "8 slides but Q8 has NO number (flags no_number, low_confidence)",
                             "legacy_defect": "page 2 holds only Q8 (<200 chars of text) so ingest.py classifies it "
                                              "'pdf-scan' and sends it down the image path. The port must classify a "
                                              "short page as digital when it has real text lines (or when its file "
                                              "neighbours are digital) and read Q8 as number 8."},
        "digital_2col_split.pdf": {"questions": 30, "numbers": list(range(1, 31)), "api_calls": 0,
                                   "tier": "text-layer", "excluded": ["answer key page", "header url", "page numbers"],
                                   "legacy_python_result": "45 slides: numbers 1..30 then 1..15 (the answer-key page "
                                                           "was parsed as questions; integrity_ok still said True)",
                                   "legacy_defect": "layout_classical._looks_like_answer_key needs >60% of lines to be "
                                                    "<=4 chars; keys such as '10  C' are longer. Also integrity_check "
                                                    "treats a numbering restart as a new section, so it never objects. "
                                                    "The port must detect the key page (header 'ANSWER KEY' + >=70% of "
                                                    "lines like '<n> <letter>') and expect exactly 30 questions.",
                                   "note": "questions are split across columns/pages by construction; stitched"},
        "scan_pages.pdf": {"questions": 30, "numbers": list(range(1, 31)), "tier": "ocr (needs on-device OCR)",
                           "legacy_python_result": "38 unnumbered low-confidence blocks (no OCR); integrity_ok True is a FALSE pass",
                           "note": "image-only PDF; legacy Python without OCR gives low-confidence blocks"},
        "photo_exif_rot.jpg": {"note": "page 1 only; must be EXIF-transposed upright before OCR",
                               "numbers_on_page": "first ~15 questions",
                               "legacy_python_result": "4 unnumbered blocks (no OCR)"},
        "garbage_textlayer.pdf": {"note": "text layer is nonsense -> integrity check fails -> escalate to OCR/Gemini",
                                  "must_not": "emit 0 questions silently",
                                  "legacy_python_result": "0 questions, no flags, integrity_ok True (FALSE pass, silent "
                                                          "failure): the junk text layer is >200 chars so the page is "
                                                          "'digital', no anchors match, nothing is emitted",
                                  "port_rule": "a page that has ink/text but yields 0 anchors, in a file whose "
                                               "other pages do have anchors or are expected to, is NOT ok; and a "
                                               "whole file with 0 questions must escalate (OCR, then Gemini), "
                                               "never finish silently"},
        "password.pdf": {"error": "password required, friendly message, no crash",
                         "legacy_python_result": "ValueError: document closed or encrypted"},
        "corrupt.pdf": {"error": "unreadable file, friendly message, no crash",
                        "legacy_python_result": "FzErrorFormat: cycle in page tree"},
    }
    with open(os.path.join(OUT, "expected.json"), "w", encoding="utf-8") as f:
        json.dump(expected, f, indent=2)
    print("wrote", sorted(os.listdir(OUT)))


if __name__ == "__main__":
    main()
