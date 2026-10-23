"""Edge-case question papers with known correct answers (synthetic, copyright-free).

    python scripts/make_edge_fixtures.py testdata [path/to/NotoSansDevanagari.ttf]

edge_cases.pdf : superscripts/subscripts, figure, inline and 2x2 options, statements, assertion-reason,
                 match-the-following table, passage + 5 dependent questions, figure options, a long question,
                 numeric question without options, (1)-(4) options with (i)/(ii) statements, A-D markers.
hindi_bilingual.pdf : each question printed in English and in Hindi (twins), optional (needs the font).
edge_expected.json : the correct structure of every question.
"""
import json
import os
import sys

import pymupdf as fitz

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
W, H = 595, 842
DV = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
DVB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONTS = {"helv": fitz.Font(fontfile=DV), "hebo": fitz.Font(fontfile=DVB)}
FILES = {"helv": DV, "hebo": DVB}


def tlen(text, fontname="helv", fontsize=11):
    return FONTS[fontname].text_length(text, fontsize=fontsize)


def put(page, pt, text, fontname="helv", fontsize=11):
    """Unicode text (π, ², ×) with an embedded DejaVu font."""
    page.insert_text(pt, text, fontname=fontname + "dv", fontfile=FILES[fontname], fontsize=fontsize)

L, R = 60, 535
FS = 11
LH = 15


class Pen:
    def __init__(self, doc):
        self.doc = doc
        self.new_page()

    def new_page(self):
        self.page = self.doc.new_page(width=W, height=H)
        self.y = 70
        put(self.page, (L, 40), "Edge Case Practice Test - Series 7", fontname="helv", fontsize=9)
        put(self.page, (W / 2 - 15, H - 30), f"Page {self.doc.page_count}", fontname="helv", fontsize=9)

    def need(self, h):
        if self.y + h > H - 70:
            self.new_page()

    def line(self, segs, x=L, size=FS, gap=LH):
        """segs: list of (text, kind) with kind in n, sup, sub, b."""
        self.need(gap)
        cx = x
        for text, kind in segs:
            if kind == "sup":
                s = size * 0.65
                put(self.page, (cx, self.y - size * 0.38), text, fontname="helv", fontsize=s)
            elif kind == "sub":
                s = size * 0.65
                put(self.page, (cx, self.y + size * 0.22), text, fontname="helv", fontsize=s)
            else:
                s = size
                put(self.page, (cx, self.y), text, fontname="hebo" if kind == "b" else "helv", fontsize=s)
            cx += tlen(text, fontname="hebo" if kind == "b" else "helv", fontsize=s)
        self.y += gap

    def text(self, t, x=L, width=R - L, size=FS):
        """Plain wrapped text."""
        words, cur = t.split(), ""
        for w in words:
            trial = (cur + " " + w).strip()
            if tlen(trial, fontname="helv", fontsize=size) > width - (x - L):
                self.line([(cur, "n")], x=x, size=size)
                cur = w
            else:
                cur = trial
        if cur:
            self.line([(cur, "n")], x=x, size=size)

    def opts_inline(self, opts, cols=4):
        per = (R - L - 20) / cols
        for r in range(0, len(opts), cols):
            self.need(LH)
            for k, o in enumerate(opts[r:r + cols]):
                put(self.page, (L + 20 + k * per, self.y), o, fontname="helv", fontsize=FS)
            self.y += LH

    def opts_rows(self, opts):
        for o in opts:
            self.text(o, x=L + 20)

    def space(self, h=10):
        self.y += h


doc = fitz.open()
p = Pen(doc)
exp = {}

# Q1 superscripts, inline options
p.line([("1. If x", "n"), ("2", "sup"), (" + y", "n"), ("2", "sup"), (" = 25 and x = 3, find the positive value of y.", "n")])
p.opts_inline(["(a) 2", "(b) 4", "(c) 5", "(d) 6"])
exp[1] = {"options": ["2", "4", "5", "6"], "sup": ["2", "2"], "figure": False, "layout": "row"}
p.space()

# Q2 figure + 2x2 options
p.text("2. In the figure below, ABCD is a square of side 4 cm and a circle is drawn inside it. Find the area of the shaded region.")
p.need(120)
top = p.y
sq = fitz.Rect(L + 140, top, L + 240, top + 100)
p.page.draw_rect(sq, color=(0, 0, 0), width=1.2)
p.page.draw_circle(sq.tl + (50, 50), 50, color=(0, 0, 0), fill=(0.75, 0.75, 0.75), width=1)
for lbl, pt in (("A", sq.tl + (-12, 0)), ("B", sq.tr + (4, 0)), ("C", sq.br + (4, 8)), ("D", sq.bl + (-12, 8))):
    put(p.page, pt, lbl, fontname="helv", fontsize=10)
p.y = top + 118
p.opts_inline(["(a) 16 - 4π cm²", "(b) 4π cm²", "(c) 16 cm²", "(d) 8 cm²"], cols=2)
exp[2] = {"options": ["16 - 4π cm²", "4π cm²", "16 cm²", "8 cm²"], "figure": True, "layout": "grid"}
p.space()

# Q3 statements
p.text("3. Consider the following statements:")
p.text("1. The Sun is a star.", x=L + 20)
p.text("2. The Moon is a planet.", x=L + 20)
p.text("3. Mercury is the planet closest to the Sun.", x=L + 20)
p.text("Which of the statements given above is/are correct?")
p.opts_rows(["(a) 1 and 2 only", "(b) 1 and 3 only", "(c) 2 and 3 only", "(d) 1, 2 and 3"])
exp[3] = {"options": ["1 and 2 only", "1 and 3 only", "2 and 3 only", "1, 2 and 3"], "statements": 3, "figure": False}
p.space()

# Q4 assertion reason
p.text("4. Assertion (A): Ice floats on water.")
p.text("Reason (R): Ice is less dense than liquid water.", x=L + 14)
p.opts_rows(["(a) Both A and R are true and R is the correct explanation of A.",
             "(b) Both A and R are true but R is not the correct explanation of A.",
             "(c) A is true but R is false.", "(d) A is false but R is true."])
exp[4] = {"options": 4, "paragraphs_min": 2, "figure": False, "layout": "rows"}
p.space()

# Q5 match the following (table)
p.text("5. Match List-I with List-II and select the correct answer.")
p.need(110)
t0 = p.y - 4
rows = [("List-I", "List-II"), ("A. Newton", "1. Relativity"), ("B. Einstein", "2. Gravitation"),
        ("C. Bohr", "3. Atomic model"), ("D. Darwin", "4. Evolution")]
for i, (a, b) in enumerate(rows):
    yy = t0 + 6 + i * 18
    put(p.page, (L + 30, yy + 8), a, fontname="hebo" if i == 0 else "helv", fontsize=10)
    put(p.page, (L + 230, yy + 8), b, fontname="hebo" if i == 0 else "helv", fontsize=10)
tb = fitz.Rect(L + 20, t0 - 6, L + 400, t0 + 6 + len(rows) * 18)
p.page.draw_rect(tb, color=(0, 0, 0), width=0.8)
p.page.draw_line((L + 215, tb.y0), (L + 215, tb.y1), color=(0, 0, 0), width=0.8)
for i in range(1, len(rows)):
    yy = t0 + i * 18 - 2
    p.page.draw_line((tb.x0, yy), (tb.x1, yy), color=(0, 0, 0), width=0.5)
p.y = tb.y1 + 16
p.opts_inline(["(a) A-2, B-1, C-3, D-4", "(b) A-1, B-2, C-3, D-4", "(c) A-2, B-1, C-4, D-3", "(d) A-3, B-4, C-1, D-2"], cols=2)
exp[5] = {"options": ["A-2, B-1, C-3, D-4", "A-1, B-2, C-3, D-4", "A-2, B-1, C-4, D-3", "A-3, B-4, C-1, D-2"], "figure": True, "table": True}

# Passage with 5 dependent questions (on a new page)
p.new_page()
p.text("Directions (Q. 6-10): Read the following passage carefully and answer the questions that follow.")
p.space(4)
passage = ("Honey bees live in large colonies that may contain tens of thousands of workers. A single queen lays the eggs, "
           "while the workers build the comb, collect nectar and pollen, and defend the hive. When a forager finds a rich "
           "source of food, she returns and performs a waggle dance. The angle of the dance tells the other bees the direction "
           "of the flowers relative to the sun, and the length of the waggle run tells them the distance. Scientists have "
           "shown that bees can learn colours, shapes and even simple patterns, which helps them to find the same flowers again.")
p.text(passage)
p.space(6)
dep = [("6. Who lays the eggs in a honey bee colony?", ["The workers", "The queen", "The drones", "The foragers"]),
       ("7. What does the angle of the waggle dance indicate?", ["Distance", "Direction", "Quantity", "Colour"]),
       ("8. Which of these is NOT a task of the workers?", ["Building comb", "Collecting nectar", "Laying eggs", "Defending the hive"]),
       ("9. The length of the waggle run tells the other bees the", ["direction", "distance", "colour", "season"]),
       ("10. According to the passage, bees can learn", ["colours only", "shapes only", "colours, shapes and patterns", "nothing"])]
for q, opts in dep:
    p.text(q)
    p.opts_inline([f"({c}) {o}" for c, o in zip("abcd", opts)], cols=2)
    p.space(4)
for n in range(6, 11):
    exp[n] = {"passage": "6-10", "options": 4}

# Q11 figure options
p.new_page()
p.text("11. Which of the following figures has exactly one line of symmetry?")
p.need(80)
top = p.y
for k, lbl in enumerate("abcd"):
    x0 = L + 30 + k * 110
    r = fitz.Rect(x0, top, x0 + 50, top + 50)
    if k == 0:
        p.page.draw_rect(r, color=(0, 0, 0), width=1)
    elif k == 1:
        p.page.draw_polyline([r.bl, (r.x0 + 25, r.y0), r.br, r.bl], color=(0, 0, 0), width=1)
    elif k == 2:
        p.page.draw_circle(r.tl + (25, 25), 25, color=(0, 0, 0), width=1)
    else:
        p.page.draw_polyline([r.bl, r.tl, (r.x1, r.y0 + 15), r.bl], color=(0, 0, 0), width=1)
    put(p.page, (x0 + 18, top + 66), f"({lbl})", fontname="helv", fontsize=10)
p.y = top + 90
exp[11] = {"figure": True, "options_as_picture": True}

# Q12 long question
long_q = ("12. A shopkeeper buys 120 notebooks at Rs 25 each and 80 pens at Rs 12 each. He sells three quarters of the "
          "notebooks at a profit of 20 percent and the remaining notebooks at a loss of 10 percent. He sells half of the pens "
          "at a profit of 25 percent and the other half at cost price. He also pays Rs 150 for transport and Rs 90 for packing "
          "material, which he treats as part of his cost. If he wants his overall profit on the whole transaction, including "
          "all expenses, to be exactly 15 percent, at what price must he sell a set of 10 extra notebooks that a school orders "
          "at the end of the month, given that the extra notebooks were bought at the same price as the others?")
p.text(long_q)
p.opts_rows(["(a) Rs 412", "(b) Rs 398", "(c) Rs 420", "(d) Rs 405"])
exp[12] = {"options": 4, "long": True}
p.space()

# Q13 subscripts, A-D markers inline
p.line([("13. The formula of water is H", "n"), ("2", "sub"), ("O. What is the molar mass of CO", "n"), ("2", "sub"), ("?", "n")])
p.opts_inline(["(A) 44 g", "(B) 28 g", "(C) 32 g", "(D) 18 g"])
exp[13] = {"options": ["44 g", "28 g", "32 g", "18 g"], "sub": ["2", "2"]}
p.space()

# Q14 numeric, no options
p.text("14. Find the value of 125 x 16. Write your answer as a whole number.")
exp[14] = {"options": 0}
p.space()

# Q15 (i)/(ii) statements with (1)-(4) options
p.text("15. Which of the following numbers are prime?")
p.text("(i) 2      (ii) 9      (iii) 11", x=L + 20)
p.opts_inline(["(1) (i) only", "(2) (i) and (iii)", "(3) (ii) only", "(4) all of these"], cols=2)
exp[15] = {"options": ["(i) only", "(i) and (iii)", "(ii) only", "all of these"]}

doc.subset_fonts()
doc.save(os.path.join(out, "edge_cases.pdf"), garbage=4, deflate=True)
with open(os.path.join(out, "edge_expected.json"), "w", encoding="utf-8") as f:
    json.dump({"edge_cases.pdf": {"numbers": list(range(1, 16)), "questions": exp}}, f, ensure_ascii=False, indent=1)
print("edge_cases.pdf", doc.page_count, "pages")

# ---- bilingual Hindi / English (shaped with HarfBuzz through insert_htmlbox) ----
if len(sys.argv) > 2:
    font = sys.argv[2]
    d2 = fitz.open()
    pg = d2.new_page(width=W, height=H)
    css = f"@font-face {{font-family: deva; src: url({os.path.basename(font)});}} body {{font-family: sans-serif; font-size: 11pt;}} .hi {{font-family: deva;}}"
    arch = fitz.Archive(os.path.dirname(font))
    qs = [
        ("1. What is the capital of India?", "1. भारत की राजधानी क्या है?", ["New Delhi", "Mumbai", "Kolkata", "Chennai"], ["नई दिल्ली", "मुंबई", "कोलकाता", "चेन्नई"]),
        ("2. How many days are there in a week?", "2. एक सप्ताह में कितने दिन होते हैं?", ["5", "6", "7", "8"], ["5", "6", "7", "8"]),
        ("3. Which is the largest planet?", "3. सबसे बड़ा ग्रह कौन सा है?", ["Earth", "Mars", "Jupiter", "Venus"], ["पृथ्वी", "मंगल", "बृहस्पति", "शुक्र"]),
    ]
    y = 60
    for en, hi, oe, oh in qs:
        html = f"<p>{en}</p><p>" + " &nbsp; ".join(f"({c}) {o}" for c, o in zip("abcd", oe)) + "</p>"
        html += f"<p class='hi'>{hi}</p><p class='hi'>" + " &nbsp; ".join(f"({c}) {o}" for c, o in zip("abcd", oh)) + "</p>"
        r = fitz.Rect(L, y, R, y + 120)
        pg.insert_htmlbox(r, html, css=css, archive=arch)
        y += 130
    d2.subset_fonts()
    d2.save(os.path.join(out, "hindi_bilingual.pdf"), garbage=4, deflate=True)
    print("hindi_bilingual.pdf")
