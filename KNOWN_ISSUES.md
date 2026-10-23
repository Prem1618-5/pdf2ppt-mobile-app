# Known issues and limits (v0.1.0)

Honest list. "Unverified" means it was built and reasoned about, but not run on a real phone.

## Not verified on a real phone yet
- **Everything Android-specific** was compiled, linted and run in CI on an Android 14 x86_64 emulator by the
  instrumented self-test (9/9 pass, peak memory ~70 MB), but **not on a real phone**. Please run
  **Settings → Self-test** and send the report.
- **OCR quality (S2)** on the emulator: `scan_pages.pdf` 30/30 numbers found (pass rule ≥ 90 %), `photo_exif_rot.jpg`
  upright with 18 numbered questions, 0 API calls. Real phone cameras and Hindi pages are untested.
- **Speed/heat**: imaging is hand-written Kotlin (no OpenCV). The 300 ms per half-page target and the thermal
  governor are unmeasured on a mid-range phone.
- **Real papers**: Hindi, bilingual two-page spreads, photos of printed pages, PDFs with `/Rotate 90` or a CropBox
  different from the MediaBox (A7), HEIC files (A8), 200-page PDFs (A10). Synthetic fixtures cover the logic only.
- **Real Gemini quota**: the scheduler and client are tested with fake keys and fake responses only.

## 0.2 limits (templates, dark theme, editable text)
- Editable text is rebuilt from the PDF text layer or the phone's OCR. Complex maths (fractions, matrices,
  integrals with limits), chemical structures and most Hindi PDFs (their text layers are often garbled) become
  **pictures** in AUTO mode. Gemini's transcription is used as text only for pages Gemini read.
- Font measurement is an estimate checked against LibreOffice with Arial-metric fonts. PowerPoint uses Arial too,
  but a different Hindi font on another computer can make Hindi lines a little longer.
- Superscripts/subscripts come from the PDF text layer; OCR does not report them (OCR text with them is plain).
- The template designer works best in landscape on small phones; each element is a rectangle (no rotation).
- Tab-separated option grids are rendered by PowerPoint/LibreOffice; Google Slides import may space them a little
  differently.
- The Review screen previews text with the phone's font, so line breaks can differ slightly from PowerPoint.

## Not done (P1 / P2)
- Re-crop of a slide, add a missing question, relabel, undo (P1/P2). MVP has preview, move up/down, delete, per-slide blank toggle, layout switch.
- `refine.py` box snapping for Gemini boxes (P1). Gemini boxes are padded and trimmed by ink only.
- PDF-level watermark removal (P2). Raster removal only: pale pixels on digital pages, paper normalisation on photos.
- Dark-frame text rebuild, speaker notes, dark theme decks (P2).
- Column-split continuations are left-aligned under the question (legacy behaviour); the indent of option lines that
  moved to the next column is not preserved.
- Network loss during Gemini: requests wait up to 2 minutes for the connection, then those pages fall back to the
  best on-device result (flagged). There is no "resume Gemini later" button yet.
- TIFF is not supported (Android cannot decode it); the app says so.

## Behaviour to know
- A job resumes after the app is killed (WorkManager restarts it); page images, OCR and Gemini results are cached, so
  no page is sent to Gemini twice. Pass 1 (small page images) is recomputed.
- The debug APK is debuggable (normal for a sideloaded MVP). It is signed with the public debug key in the repo.
