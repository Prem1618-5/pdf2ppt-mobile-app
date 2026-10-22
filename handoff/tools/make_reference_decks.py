"""Build two tiny reference decks (one per layout) with the legacy DeckBuilder (read-only import).

    python make_reference_decks.py <legacy_repo_dir> <out_dir>

Each deck = 2 questions, every question followed by a blank slide. They are KNOWN-GOOD
PowerPoint files: unzip one and copy its XML parts as the templates of the Kotlin PPTX writer
(`unzip` is just a zip). `validate_pptx.py` must accept both.

Layout geometry (inches, slide 13.333 x 7.5):
  top        box (0.15, 0.15, 13.033, 3.45)  centred horizontally   <- NEW in the Android app
  top-right  box (6.8165, 0.15, 6.3665, 3.45) left-aligned in Q-I   <- legacy "top-right"
The legacy DeckBuilder has no "top"; it is emulated here by setting box/centre after construction.
"""
import io
import os
import sys

sys.dont_write_bytecode = True   # never write __pycache__ into the read-only legacy repo

legacy, out = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
sys.path.insert(0, legacy)
os.makedirs(out, exist_ok=True)

from PIL import Image, ImageDraw, ImageFont     # noqa: E402
from qdeck.deck import DeckBuilder, INSET, SLIDE_H, SLIDE_W   # noqa: E402


def question_png(n, w=1500, h=520):
    im = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(im)
    try:
        f = ImageFont.truetype("arial.ttf", 44)
    except Exception:
        f = ImageFont.load_default()
    d.text((20, 20), f"{n}. Sample question number {n}: which option is correct?", fill="black", font=f)
    for i, o in enumerate(["first", "second", "third", "fourth"]):
        d.text((60, 110 + i * 85), f"({'abcd'[i]}) {o} option", fill="black", font=f)
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return buf.getvalue(), im.size


for layout in ("top", "top-right"):
    deck = DeckBuilder(theme="light", layout="top-right" if layout == "top-right" else "fill")
    if layout == "top":
        deck.box = (INSET, INSET, SLIDE_W - 2 * INSET, SLIDE_H / 2 - 2 * INSET)
        deck.centre = True
    for n in (1, 2):
        data, size = question_png(n)
        deck.add_snapshot(data, size, f"Q {n}\nspeaker notes hold the extracted text")
        deck.add_blank()
    path = os.path.join(out, f"reference_{layout.replace('-', '_')}.pptx")
    deck.save(path)
    print("wrote", path)
