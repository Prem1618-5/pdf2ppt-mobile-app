# Decisions

Each entry: the choice, why, and the evidence. Dates 2026-10-21.

## Platform and build

| Decision | Why / evidence |
|---|---|
| Kotlin + Jetpack Compose, minSdk 26, compile/target SDK 37, AGP 9.4.1, Gradle 9.8.2, Kotlin 2.4.21, Compose BOM 2026.09.00 | Current stable versions read from Google Maven / Maven Central / Gradle at build time, not from memory |
| **Two modules: `core` (plain Kotlin/JVM) + `app` (Android)** instead of one `app` module with packages | Deviation from the build prompt. A separate JVM module *enforces* "domain code is Android-free" at compile time and lets the domain + fixture tests run in seconds without the Android SDK. The prompt's goal (Android-free, JVM-tested domain) is met more strictly |
| No Hilt, no Room, kotlinx.serialization JSON files, OkHttp, WorkManager, Navigation Compose | As decided in the prompt |
| Debug-signed APK, minify off; a **committed public debug keystore** (`app/papertoslides-debug.keystore`, password `android`) | Every CI build has the same signature, so updates install over the old app. It is not a secret |
| ABIs `arm64-v8a` + `armeabi-v7a` (x86_64 only with `-PwithX86` for the emulator) | APK 48 MB instead of ~75 MB |
| Gemini model names are **never hard-coded**: user list (Settings) checked against `models.list`; empty = newest Flash models first, lite last | Legacy defect #6. `models.list` is free (no generation quota) and cached for 24 h |
| `maxOutputTokens` = the model's `outputTokenLimit` from `models.list` (cap 65 536), else 32 768 | Legacy defect #7 |

## Spikes

| Spike | Result | Choice |
|---|---|---|
| **S1 PDF engine** | Option A. `PdfRenderer` (pixels, clip render via a scale+translate matrix, ≤ 4096 px, one page open at a time behind a mutex) + **PdfBox-Android 2.0.27** (text). The glyph→line grouping lives in `core` (`LineBuilder`) and is tested on the JVM with desktop PDFBox 2.0.27 (same API): on `digital_2col_split.pdf` every golden line is found with y0/x1/y1 within 0.01. Our x0 starts at the first *visible* glyph; PyMuPDF's includes leading spaces of indented option lines (≤ 0.03 difference), which is better for cropping and does not affect anchors. `password.pdf` throws a catchable `InvalidPasswordException`; desktop PDFBox *repairs* `corrupt.pdf` (no crash either way) | A, no AGPL. On-phone confirmation: Self-test A1/A2/A6 |
| **S2 OCR** | ML Kit Text Recognition v2, bundled models (Latin; Devanagari model when the paper language is Hindi/Both; it also reads Latin). Lines → `text_lines()` shape → the same classical parser with OCR-tolerant anchors (`l.`/`I.` → 1, `O` → 0, accepted only when that is the expected next number). Skew from the median line angle; photos rotated by −skew when 0.7° ≤ \|skew\| ≤ 15° and re-read | Shipped. CI emulator (Android 14): `scan_pages.pdf` **30/30** numbers, photo upright with 18 numbered questions, 0 API calls. The junk text layer of `garbage_textlayer.pdf` is rejected and OCR reads the real questions |
| **S3 Imaging** | **Hand-written Kotlin** (`core/imaging`) instead of OpenCV: adaptive threshold via integral image, line-kernel opening computed exactly from run lengths, connected components, paper normalisation, dHash. Pure JVM, so it is unit/end-to-end tested here; adds 0 MB | Hand-written (prompt's fallback, chosen first for size and testability). The 300 ms target on a mid phone is unverified (KNOWN_ISSUES) |
| **S4 PPTX** | Static parts copied from `reference_top.pptx` (theme, master, 11 layouts, props); presentation, slides, rels, media and content types generated with `ZipOutputStream`. `validate_pptx.py` passes for `top` and `top-right`, with and without blank slides, on 30-question decks built from the real fixture PDF; LibreOffice 7 headless converts the deck to 60 PDF pages without errors | Done |

## Algorithms (new vs legacy)

- **Integrity check** replaces `integrity_check().ok`: ≥ 3 questions, all numbered, consecutive per section; a restart
  to 1 opens a new section only after ≥ 3 questions; zero questions is never ok; every page with ink must have an
  anchor or be a known non-question page (answer key, cover/instructions before the first question, duplicate).
  It returns the *failing pages*, and only those go to the next tier.
- **Answer key**: header *and* ≥ 70 % of lines like `<n> <letter>`, or a dense grid of such tokens (defect #1).
- **Digital vs scan**: by real text lines, not "> 200 characters" (defect #2): `digital_1col` page 2 now gives Q8.
- **Ladder fallback**: when a page has no key and no OCR, it keeps the best on-device result; the T3 heuristic only
  runs for pages that found nothing, and T3 output is always flagged `low_confidence` + `no_number` (defect #5).
- **Slice scheduler**: one coroutine per key, 1 request in flight per key, shared channel, work stealing; outcome
  table ported from `keypool.py`; a cooling key's wait ends the moment all work is done (no worker keeps the job open);
  recovery ladder retry → split to single pages → next tier; skipped / suspiciously empty pages re-run alone.
  4 fake keys × 2 s finish 11 slices in ~6 s of virtual time (B15).
- **Network**: required only at T2, awaited with a `ConnectivityManager` callback (2 min, then pages fall to the next
  tier), never a job-wide constraint.
- **Box refinement (`refine.py`)** is *not* ported: it only applies to Gemini boxes and the constants were tuned on few
  papers (defect #8). Gemini boxes are padded and trimmed by ink instead. See KNOWN_ISSUES.

## Security (B14)

Keystore AES-256-GCM (StrongBox when present, else TEE), sealed key list in `filesDir/vault`, unreadable vault →
treated as empty (no crash). `allowBackup=false`, data-extraction rules exclude everything, cleartext off, the
transport refuses any host but `generativelanguage.googleapis.com`, key only in `x-goog-api-key`, no HTTP logging,
`FLAG_SECURE` on the Keys screen, clipboard cleared after paste, UI shows only `…last4`, health stored by key hash.
`bcpkix`/`bcutil` (pulled in by PdfBox) are excluded: they are only for certificate-encrypted PDFs and contained a
trust-all X509 manager that Lint flagged. `scripts/security_sweep.sh` checks the APK in CI.

## 0.2: templates, dark theme, editable text, passages (2026-10-23)

| Decision | Why / evidence |
|---|---|
| **Template = question area + elements**, drawn on the PowerPoint *slide layout* (not on each slide) | Every slide (also the blank working slides) gets the same strips/logo; the teacher can still edit the branding once in PowerPoint's slide master view. Each deck stores a copy of its template, so later edits never change old decks. The plain template reproduces 0.1 exactly (handoff validator still passes) |
| The two layouts are computed **inside the question area**: TOP = top half, TOP_RIGHT = quadrant I; "expanded" = full height (TOP: whole area, TOP_RIGHT: quadrants I + IV) | Owner: comprehension may take Q1 + Q4. Used for passages (repeat mode) and for long questions when "long questions may use the full height" is on |
| **Editable text** questions in an invisible **group shape** (text boxes + figure pictures) | "Invisible rectangle containing the text and figures"; a group moves as one unit in PowerPoint. Text is real PowerPoint text (Arial; Devanagari runs tagged hi-IN with Nirmala UI) with real super/subscripts |
| **AUTO style: text only when it can be trusted**, otherwise an exact picture (flag "Picture") | Wrong text on a slide is worse than a picture. Refused: glyphs without Unicode (U+FFFD from the PDF), garbled Devanagari (Latin-extended letters inside Hindi words, words starting with a vowel sign, lone consonants), maths laid out on several lines (fractions), OCR confidence < 0.75, no text. The Hindi fixture shows real-world behaviour: its text layer is garbled, so it becomes pictures, pixel-exact |
| Font fitting without a font engine: Helvetica/Arial advance widths + Devanagari estimate, 4 % safety margin | Checked by **rendering every test deck with LibreOffice** (Liberation Sans has Arial's metrics) and asserting that no rendered glyph leaves its box or overlaps a figure (`scripts/validate_deck.py --render`) |
| Font size: largest from 28 pt; comfortable (≥ 20 pt) in the normal box first, then the expanded box, then down to the minimum (16 pt, setting) | Readable from the back of a classroom; never silently tiny |
| Options: one row of 4, a 2 × 2 grid (tab stops) or one per line with a hanging indent, by measured width | Matches how papers print them; consistent across slides |
| Figures: ink that is not text (text-line boxes blanked, connected components merged); labels inside a figure stay with it; a row of figures with (a)-(d) labels becomes one "figure options" picture; "List-I / Column-I" blocks and ruled tables are kept as pictures | Diagrams, tables and match-the-following must stay exact; figures are scaled like the text and never below their paper size |
| Dark theme pictures: black-on-white line art is recoloured to the theme (paper → background, ink → text colour); colourful pictures stay as they are | Inverted colour photos look wrong |
| Passages: "Directions / Read the passage" blocks with their question range (6-10) are a shared passage. Default **repeat** it with each dependent question (full height); if it does not fit, automatically **once** on its own slide(s) (split by paragraph) and the questions say "Read the passage on slide N" | Teachers need the passage visible while solving; very long passages cannot share a slide |
| Parser fixes found by the new fixtures | Numbered statements inside a question ("1. The Sun is a star.") are indented and are no longer taken as new questions (the old integrity check even passed that as a new section); passages no longer glue onto the previous question; a repeated number in the other script is a bilingual twin |
