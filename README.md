# Paper to Slides (Android)

Turns a question paper (PDF, or photos / screenshots of pages) into a PowerPoint deck: **one question per slide**,
in reading order, with an **empty working slide after each question**. The question picture goes in the
**top half** (`Top`) or the **top-right quarter** (`Top right`) of the slide, and you can switch later without
re-reading the paper.

Everything runs on the phone. No server, no login, no tracking. An AI service (with your own key: Google Gemini, Groq, NVIDIA NIM, OpenRouter, Mistral, or any OpenAI-compatible
service with vision) is used only for pages the phone cannot read by itself.

Install guide for non-developers: **[HOW_TO_INSTALL.md](HOW_TO_INSTALL.md)**.

## How it works

```
pick files ─► copy to app storage ─► pass 1: small page images, duplicate check, PDF text lines
          ─► layout ladder (per page, cheapest first, escalate only pages that fail the numbering check)
               T0 PDF text layer  →  T1 on-device OCR (ML Kit)  →  T2 AI vision (all keys, all services, in parallel)  →  T3 heuristic
          ─► assemble questions (stitch column/page splits, passages, bilingual twins, duplicates, sections)
          ─► re-render each question from the original at high resolution, clean, trim
          ─► .pptx (hand-written OOXML) + QA report
```

| Folder | What |
|---|---|
| `core/` | Pure Kotlin, no Android: parser, assembler, integrity check, ladder, key lanes, slice scheduler, Gemini + OpenAI-compatible protocols (`provider/`), imaging, project model, PPTX writer. Unit-tested on a plain JVM with the fixtures |
| `app/` | Android: Compose UI, PdfRenderer + PdfBox-Android, ML Kit OCR, Keystore vault, OkHttp, WorkManager job, self-test |
| `handoff/` | The handoff pack: fixtures with correct answers, reference decks, Python tools (the deck validator) |
| `scripts/` | `security_sweep.sh` (no keys in the APK, safe manifest flags) |

## Build

Needs JDK 17+ (21 recommended) and the Android SDK (platform 37, build-tools 37).

```bash
./gradlew :core:test                 # domain tests, incl. fixture PDFs end to end + validate_pptx.py (needs python-pptx)
./gradlew assembleDebug              # app/build/outputs/apk/debug/PaperToSlides-v0.2.0-debug.apk
./gradlew connectedDebugAndroidTest -PwithX86   # on-device self-test on an x86_64 emulator
scripts/security_sweep.sh app/build/outputs/apk/debug/PaperToSlides-v*-debug.apk
```

CI (`.github/workflows/android.yml`) runs all of this on every push and uploads the APK as the
`PaperToSlides-debug-apk` artifact.

See [DECISIONS.md](DECISIONS.md) for the choices made and their evidence, and [KNOWN_ISSUES.md](KNOWN_ISSUES.md) for
what is not done or not yet verified on a real phone.
