# BUILD PROMPT: "Paper to Slides" Android app (MVP, delivered as an APK)

You are Claude Opus, acting as lead Android engineer, QA engineer and release manager for one person who is **not a mobile developer**. Build the app end to end, test it, package it as an installable **APK**, and explain in plain language how to install and try it. Work autonomously. Make decisions yourself using the defaults below. Stop to ask only when you are blocked by something only the owner can do (§6, Step 0).

Today's date is about 2026-10-22. Android, Gradle, Compose, ML Kit and Gemini model names change often: **look up current stable versions and current API behaviour at build time; do not trust memory.**

---

## 1. Mission in one paragraph

A teacher picks a question paper (a PDF, or photos and screenshots of pages). The app returns a **PowerPoint (.pptx) with one question per slide**, in reading order, with **an empty working slide after each question**. The question appears as a sharp picture in **either the top half of the slide (`TOP`) or the top-right quadrant (`TOP_RIGHT`, "quadrant I")**. Everything runs **on the phone**. Gemini (the owner's own API keys) is a fallback used only for pages the phone cannot read by itself, and it is called **in parallel across all keys**, two pages per request per key by default. Keys are stored encrypted. No login, no backend, no analytics.

This replaces a Python web app (hosted on Render, slow cold starts, free-tier Gemini quota exhausted quickly). That app is the **read-only reference**.

## 2. Read these first (in this order)

The pack folder is `D:\Development Project\Coaching Problem Solution\android-handoff` (copied next to your working folder as `handoff/` if the owner did so; use whichever exists).

1. `01_CONTEXT_AND_SNIPPETS.md`: requirements, the 3 problems, the new algorithms (§5), learnings, dos and don'ts, test plan (§8), the legacy source verbatim (§9) and the legacy defects you must NOT copy (§10). **Read all of it before writing code.**
2. `fixtures/expected.json`: the correct answer for each test input, and where the legacy Python was wrong.
3. `tools/*.py`: fixture generator, golden runner, line dumper, deck validator.
4. `reference/*.pptx`: two known-good decks, the templates for your PPTX writer.
5. The legacy repo `D:\Development Project\Coaching Problem Solution\pdf2ppt` (`qdeck/`, `docs/DESIGN.md`, `docs/TEST_RESULTS.md`, `README.md`) for anything the pack does not cover.

### Hard rule about the legacy repo
**Read only.** Never modify, delete, move, reformat, commit to, or run tools that write inside `pdf2ppt/`. Run Python helpers with output directories **outside** it (the tools already do). Before finishing, run `git status` in `pdf2ppt/`: it must be clean.

## 3. Requirements checklist (the owner's own demands)

- [ ] Native Android app, **APK** delivered, plain-language install guide (the owner knows nothing about app development).
- [ ] **Two layouts only: `TOP` and `TOP_RIGHT`.** Switchable after processing without new API calls.
- [ ] **No server.** No cold start. Works offline for most papers.
- [ ] **Multiple Gemini keys used in parallel.** A large PDF or set of images is cut into **slices of 2 pages** (configurable 1-4), one worker per key, work stealing, no slice lost.
- [ ] **Keys safe and encrypted:** Android Keystore AES-256-GCM, no keys in the APK, logs, backups, screenshots or URLs.
- [ ] **Low power, but the job gets priority:** foreground task with visible progress, wake lock only while running, thermal and battery governor, no polling, idle = nothing running.
- [ ] **Mobile-centric UI**, one-handed, light and dark, accessible.
- [ ] **No auth.** MVP that works.
- [ ] Legacy code **not destroyed**; reusable logic ported from the pack.
- [ ] Fast and frugal with the API: on-device text layer and OCR first (§5.1 of the context file).

## 4. Decisions already made (do not re-litigate; deviate only with evidence, and record it in `DECISIONS.md`)

**Platform and tooling**
- Native **Kotlin + Jetpack Compose**, single `app` Gradle module with packages, Gradle Kotlin DSL, a **version catalog** (`gradle/libs.versions.toml`), KSP if you need an annotation processor, **no Hilt** (manual dependency injection), **no Room** (JSON files like the legacy `project.json`), `kotlinx.serialization`, `kotlinx.coroutines`, OkHttp, WorkManager, Navigation Compose.
- `minSdk 26`, `compileSdk`/`targetSdk` = the latest stable. Release type for the MVP: **debug-signed APK, minify off**.
- App name "Paper to Slides". Package `app.papertoslides` (the owner can rename). Draw the launcher icon as a vector (no external assets).
- Avoid: Apache POI, `androidx.security:security-crypto` (deprecated), MuPDF (AGPL), any server, any analytics SDK, any Firebase.

**Architecture** (details in the context file §4)
- **Domain code is Android-free** (`model`, `layout`, `assemble`, `keypool`, `scheduler`, `pptx`, `geometry`). It runs on a plain JVM so it is unit-tested fast. Android-specific code sits behind interfaces: `PageTextSource`, `PageRenderer`, `OcrEngine`, `HttpTransport`, `Clock`, `KeyStoreVault`.
- Layout ladder **T0 text layer → T1 on-device OCR → T2 Gemini → T3 image heuristic**, escalating only on failed integrity (§5.1). **Fix the legacy integrity false-passes** (§10 of the context file).
- Slice scheduler per §5.2, outcomes per `keypool.py`. Layout geometry per §5.3. Key vault per §5.4. Background execution per §5.5. PPTX writer per §5.6.

**Defaults:** 2 pages per request; 8 requests/minute per key (editable); layout default `TOP`; blank slide after each question on; watermark removal on; language English; page cap 150 (warn, allow override); crop resolution 250 px per displayed inch (cap 600); layout JPEG 1536 px long side, quality 82.

**Confirmed by the owner (2026-10-22):** "top" = the top half of the slide, full width, picture centred horizontally. No need to ask again; the constants are in §5.3.

## 5. Spikes: settle these with evidence first (each has a pass rule and a fallback)

Do these early (milestones M0-M4). Record the result and the choice in `DECISIONS.md`.

| Spike | Question | Pass rule | Fallback |
|---|---|---|---|
| S1 PDF engine | Which library gives page pixels **and** text lines with boxes? Candidates: (A) `android.graphics.pdf.PdfRenderer` for pixels + PdfBox-Android for text; (B) a PDFium wrapper (e.g. `io.legere:pdfiumandroid`) for both | On `digital_2col_split.pdf`: text lines match `fixtures/lines/digital_2col_split.lines.json` within 0.01 (fractions); a clip render of a half page at 250 dpi takes about 1 s or less and stays inside the memory cap; password and corrupt files throw catchable errors | The other candidate. Never AGPL without telling the owner |
| S2 OCR | Does ML Kit Text Recognition (Latin, bundled model; Devanagari added when language is Hindi/Both) give line boxes good enough for the anchor parser? | On rendered `scan_pages.pdf` and on `photo_exif_rot.jpg` (after EXIF + de-skew): at least 90 % of the 30 (page-1: first ~15) numbers found in a consecutive run with tolerant anchors | Ship the ladder anyway (T2 covers the rest); record the measured rate in `KNOWN_ISSUES.md` |
| S3 Imaging | OpenCV Android (`org.opencv:opencv`, `arm64-v8a` only) vs hand-written Kotlin for `adaptiveThreshold` / morphology | APK ≤ about 80 MB; `snap` + `tighten` on a 250-dpi half page ≤ 300 ms on a mid phone | Hand-written for the 3 P0 operations only |
| S4 PPTX | Does the hand-written writer produce decks that pass `tools/validate_pptx.py` and open in LibreOffice? | Passes for `TOP` and `TOP_RIGHT` on the 30-question fixture | Add the missing static part from the reference deck |

Because you cannot run an Android device yourself, **verify Android-only behaviour in one of these ways, in this order:** (1) JVM unit tests with fakes; (2) instrumented tests on a CI emulator (`reactivecircus/android-emulator-runner`, x86_64, API 30-34); (3) the **on-device Diagnostics screen** (§7, M8) that the owner runs once on their phone and screenshots to you.

## 6. Environment, build and delivery

### Step 0: one message to the owner, then continue
Inspect the machine first (Windows 11; PowerShell and Git Bash available): `java -version`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `gradle`, `git --version`, `python --version`, `winget --version`, `adb`. Then send **one** short, plain-language message asking for exactly these, and **keep working on the parts that need no answer** while waiting:

1. **Permission to install the build tools** on their PC if missing: a JDK 17 (e.g. `winget install EclipseAdoptium.Temurin.17.JDK`) and the Android command-line SDK from Google (a few GB; this includes accepting Google's SDK licences). Say what, how big, where. Install nothing without a yes. If the answer is no, use **CI-only mode** (below), which is slower to iterate.
2. **A GitHub repository** for the new app: ask them to create an empty one (private is fine) at github.com/new and send the URL. Git on this machine already pushes to their account (it did for `Prem1618-5/pdf2ppt`). You cannot create the repo yourself without a token. **Do not ask for or accept any password, token or API key in chat.**
3. *(optional)* their phone model and Android version.
4. *(nothing to confirm about the layout: "top" = top half, full width, is already confirmed.)*

### Where to work
- Create the project in a **new empty folder** (the current working directory, or `D:\Development Project\papertoslides-android` if the current folder is not empty). `git init`, `.gitignore` (Gradle, `local.properties`, `*.keystore` except the debug one below, build outputs), first commit.
- Copy the pack into `handoff/` inside the project (so the tests and CI can use `fixtures/` and `tools/`). Keep the original pack folder untouched.

### Build paths
- **Local build (preferred if the owner approves the install):** `./gradlew testDebugUnitTest assembleDebug`. Fast loop, JVM tests run on the PC.
- **CI (always set up, even if you build locally):** `.github/workflows/android.yml` on every push to `main` and on manual dispatch: checkout → `actions/setup-java` (Temurin 17) → `gradle/actions/setup-gradle` → `./gradlew testDebugUnitTest assembleDebug` → `pip install python-pptx pymupdf pillow` → generate the 30-question deck via a JVM test and run `python handoff/tools/validate_pptx.py … --layout top` and `--layout top-right` → `actions/upload-artifact` with the APK (name `PaperToSlides-debug-apk`). Optionally an emulator job for instrumented tests. Use current major versions of each action; verify.
- **Signing:** debug-signed APK is enough for sideloading. **Commit one dedicated debug keystore** (`app/papertoslides-debug.keystore`, with a note that it is a public, non-secret debug key) and point the `debug` signing config at it, so every CI build has the **same signature** and the owner can update the app over the old install instead of uninstalling it. Never commit any other key or secret.
- **Output:** `PaperToSlides-v<version>-debug.apk` (also attached to a GitHub Release if the owner agrees). Version name `0.1.0`, increasing `versionCode`.

### CI-only mode (if the owner declines installs)
Smaller commits, run the workflow after each logical step, read the logs through the Actions page, and keep dependencies minimal. Say plainly that this is slower.

## 7. Milestones and gates

Commit after each milestone with a clear message. **Do not start the next milestone while a gate is red.** Keep `DECISIONS.md` and `KNOWN_ISSUES.md` current as you go.

| M | Deliverable | Gate |
|---|---|---|
| **M0** | Toolchain check, repo, Gradle wrapper, version catalog, "hello" Compose screen builds to an APK in CI, workflow green | APK artifact downloads |
| **M1** | **Pure-Kotlin domain** + unit tests: `model` (Fragment, Question, Item, Project), `keypool` (full outcome table), `scheduler` (§5.2) with a fake API and fake clock, `layout` (classical parser), `assemble` + `reading_order` + the **new integrity rules**, `geometry` (`SlideLayout`, boxes, `target_dpi`), ladder decision logic | Unit tests from §8.3 items 1-6 green. A1, A2, A5 pass from `fixtures/lines/*.json` against `expected.json` (30 questions, 8 questions, escalation instead of silent 0). B15 throughput test passes |
| **M2** | **PPTX writer** from the reference decks, both layouts, blank-slide option, alt text | `validate_pptx.py` passes for both layouts on a 30-question synthetic deck, locally and in CI (S4) |
| **M3** | **Android I/O + imaging:** SAF picking, copy to cache, `PageSource` for PDF (clip render) and images (EXIF, downscale), dHash, crop → clean → trim → encode (OpenCV), PNG/JPEG rule | Instrumented tests on the CI emulator for A1, A2, A7, A8 (S1, S3 decided) |
| **M4** | **OCR tier + ladder:** ML Kit OCR → `text_lines` shape → classical parser; tolerant anchors; de-skew; integrity-driven escalation | `scan_pages.pdf` and `photo_exif_rot.jpg` meet the S2 pass rule or the shortfall is recorded; A3-A5 pass; zero API calls on A1-A3 (S2 decided) |
| **M5** | **Gemini client + KeyVault + Keys screen:** OkHttp with `x-goog-api-key` header, `models.list` key check and model discovery, per-key worker integration, cache by image hash + prompt version, `MockWebServer` tests for 200/429(min)/429(day)/400/404/5xx/truncated JSON | B2-B13 green. Security: no key in logs (B14 scan) |
| **M6** | **Job runner:** WorkManager `CoroutineWorker` + foreground info (`dataSync`), notification with progress and Cancel, wake lock with timeout, thermal and battery governor, persistent progress, resume after process death, cancel | D2, D5, D6 green (instrumented or emulator); no leaked wake lock |
| **M7** | **UI:** Home, Options sheet (two layout cards that draw the slide), Progress (lanes, ETA, Cancel), Review (grid, preview on a to-scale slide, delete, move, switch layout), Export (Save to Downloads, Share, QA report), Keys, empty/error/loading states, light/dark, TalkBack labels, font scale 200 % | Compose UI tests for the main path; C2, C9 green |
| **M8** | **Hardening and Diagnostics:** A6-A12 edge cases, B1, B7, C3-C6, D3, D4; **Diagnostics screen** (Settings → "Self-test") that runs the bundled fixtures through the real pipeline on the phone and shows PASS/FAIL, timings, peak memory, API calls used (0 expected for A1-A3) and lets the owner copy the report text; security sweep (B14: `apkanalyzer`/`strings` for keys, logcat grep, manifest flags) | Self-test passes on the CI emulator; sweep clean |
| **M9** | **Release:** final CI green, APK artifact, `HOW_TO_INSTALL.md`, `DECISIONS.md`, `KNOWN_ISSUES.md`, `README.md`, final report (§9) | §8 definition of done in the context file |

You may use parallel sub-agents for independent pieces (for example the pure-Kotlin domain in M1 and the CI workflow in M0), **never two writers on the same files**, and always review and test what they return before merging it.

## 8. Quality bar and verification protocol

- **Tests first for the domain** (M1): they are your safety net for the port. Port `keypool.py`, `assemble.py`, `layout_classical.py` faithfully, then fix the defects of §10 with new tests that fail first.
- **Golden checks:** the Kotlin parser's question numbers on each fixture must equal `fixtures/expected.json`, not the legacy output. Where the legacy output differs, the legacy is wrong by design (see the table in context §8.1).
- **Every `.pptx` your code produces in tests** must pass `tools/validate_pptx.py` for its layout. Both layouts, every time.
- **Security:** run a sweep (B14) before M9: no keys in the APK, logs, backups, screenshots or URLs; `allowBackup=false`; `FLAG_SECURE` on the Keys screen; cleartext traffic off; HTTP body logging off in release builds.
- **Power:** no busy loops; confirm wake lock release in `finally`; verify one job does not exceed the CPU worker cap and that a thermal `SEVERE` event pauses CPU stages (use a fake `ThermalSource` in unit tests).
- **Honesty over polish:** when something is not verified on a real phone, say so in `KNOWN_ISSUES.md` and in the final report. Never claim a test passed that you did not run.
- Keep the code readable and consistently styled; short comments only where the reason is not obvious. Match the legacy naming where you port logic, so the two stay comparable.

## 9. Final report (your last message)

Short, plain language, no jargon. Include, in this order:

1. **Where the APK is** (exact path or download link) and the **three steps** for the owner to install and try it.
2. What the app does now vs what is **not** done (P1/P2 items).
3. What you **tested and how**, and what you could **not** test (real phone, real Hindi papers, real Gemini quota).
4. The decisions that matter to them (OCR-first, 2 pages per key, `TOP` = top half) and the honest trade-offs: battery and heat on the phone, Gemini free-tier data terms, same-project keys share one quota.
5. Anything they must still do (create the repo, send a screenshot of the Self-test, try a real paper).
6. Confirmation that **the legacy repo is untouched** (`git status` clean).

Also write, in the project: `README.md` (what it is, how to build), `HOW_TO_INSTALL.md` (for a non-developer: how to download the APK from GitHub Actions or the Release, transfer it to the phone, allow "Install unknown apps", handle the Play Protect prompt, get a Gemini key from Google AI Studio, add it, make the first deck), `DECISIONS.md` (each spike and choice, with evidence), `KNOWN_ISSUES.md`.

## 10. Do not

- Do not modify the legacy repo. Do not delete or overwrite the pack.
- Do not ask for, accept, print or store a real API key, password or token in chat, logs, code or CI. Tests use fake keys.
- Do not install software or accept licences on the owner's machine without their yes.
- Do not add accounts, analytics, ads, a backend, or any network call other than `generativelanguage.googleapis.com` (and the free `models.list`).
- Do not hard-code Gemini model names or quota numbers as facts.
- Do not report `integrity ok` for an empty or unnumbered result. Do not silently drop a page.
- Do not stop early with a half-built app: if you are blocked, deliver the highest milestone that is green, explain precisely what is missing, and ask the one question that unblocks it.

Begin with §2, then Step 0 of §6.
