# Paper → Slides for Android: context, learnings and reusable code

Written 2026-10-21 for the builder (Claude Opus) and for the owner (not a mobile developer).

**Legacy project (READ-ONLY reference):** `D:\Development Project\Coaching Problem Solution\pdf2ppt`
Python 3.12, FastAPI web app, GitHub `Prem1618-5/pdf2ppt` (branch `claude/magical-fermat-uxp4ot`), live on Render at `https://pdf2ppt-qfb0.onrender.com`.
**Nothing in the legacy folder may be changed, deleted, moved, reformatted or re-committed.** Read it, copy ideas out of it, never write into it. `git status` there must stay clean.

> Facts about Gemini quotas and model names were observed on 2026-10-19 and **will drift**. Treat every number in this pack as a default to verify, never as a constant to hard-code.

---

## 0. What is in this pack

| File | What it is |
|---|---|
| `README.md` | Plain-language guide for the owner: how to start, what to approve, how to install the APK |
| `01_CONTEXT_AND_SNIPPETS.md` | **This file.** Requirements, problems, design, learnings, dos/don'ts, tests, and the legacy code worth porting |
| `02_OPUS_BUILD_PROMPT.md` | The one-shot build prompt. Hand this to Opus |
| `tools/make_fixtures.py` | Builds copyright-free test inputs with known correct answers |
| `tools/reference_run.py` | Runs the legacy pipeline offline on the fixtures and prints what it finds (golden numbers, and its known mistakes) |
| `tools/dump_lines.py` | Dumps per-page text lines to JSON so the Kotlin parser can be unit-tested on a plain JVM |
| `tools/validate_pptx.py` | Checks a generated `.pptx` against the two layouts (the acceptance oracle for the PPTX writer) |
| `tools/make_reference_decks.py` | Builds the two reference decks below |
| `fixtures/` | The generated inputs, `expected.json`, and `lines/*.json` |
| `reference/reference_top.pptx`, `reference/reference_top_right.pptx` | Known-good decks. Unzip one and copy its XML as the templates of the Kotlin PPTX writer |

---

## 1. The product in one page

A teacher gives the app a **question paper** (a PDF, or photos / screenshots of the pages). The app returns a **PowerPoint (.pptx) with one question per slide**, in reading order, plus **an empty slide after each question** for working out the solution. Solutions and answer keys are left out. The question is a sharp picture cut from the original page (so maths, diagrams, tables and Devanagari stay pixel-exact), with watermarks and table borders removed.

How the legacy app does it (see §9 for the code):

1. Page stream: render each page small (1536 px long side) and compute a perceptual hash to skip duplicate pages.
2. **Layout** = for every question, where it sits on the page. Three ways to get it, cheapest first: the PDF **text layer** (0 API calls), then a **vision LLM** (Gemini) for scans/photos, then a no-LLM image heuristic as a last resort.
3. Assemble fragments into questions (stitch a question split across columns or pages, attach shared passages, drop bilingual twins and duplicates, detect section restarts, check numbering integrity).
4. Re-render each question from the **original** source at high resolution, clean it, trim it.
5. Write the deck (one picture per slide) and a QA report.

Users are teachers and coaching-class staff. Papers come as digital PDFs, scanned PDFs, phone photos of printed pages (including two-page spreads), screenshots and video frames. Languages: English, Hindi (Devanagari), and bilingual papers that print each question twice.

---

## 2. Requirements (the owner's own words → rule)

New in this port:

| # | Owner said | Rule for the build | Priority |
|---|---|---|---|
| R1 | "results very slow and hitting api request very fast" | Spend API requests only where on-device methods fail. Parallelise across keys. Cache every page result. See §5.1, §5.2 | P0 |
| R2 | "pictures or text of question should appear on either top or top right of slide, quadrant 1 … give these two layout option" | Exactly **two** layouts: `TOP` (top half, full width) and `TOP_RIGHT` (quadrant I). The old "fill the whole slide" layout is removed. See §5.3 | P0 |
| R3 | "render web service is slow, cold start … bad user experience" | No server. Everything runs on the phone. The only network call is the phone → Gemini, and only when needed | P0 |
| R4 | "transition from website model to application on android" | Native Android app, delivered as an installable **APK** | P0 |
| R5 | "I am not a mobile developer … packaging it to me in apk" | The builder sets up the toolchain (with permission) or a cloud build, produces the APK, and writes click-by-click install steps in plain language | P0 |
| R6 | "do not destroy the code here" | Legacy repo is read-only. The new app lives in a new folder | P0 |
| R7 | "optimize use of multiple api keys … break a large pdf or images into sections … 2 pages per api key" | Slice scheduler with one worker per key, 2 pages per request by default, work stealing, adaptive slice size. See §5.2 | P0 |
| R8 | "api keys safe and encrypted" | Android Keystore (AES-256-GCM), no keys in the APK, none in logs/backups/screenshots. See §5.4 | P0 |
| R9 | "low power consumption app but draws priority for its task" | Idle = nothing running. A job runs as a user-visible foreground task with a bounded CPU/thermal footprint. See §5.5 | P0 |
| R10 | "mobile centric UI" | Phone-first, one-handed, big targets, light and dark. See §6 | P0 |
| R11 | "No need of auth, just a working app for mvp" | No accounts, no login, no backend, no analytics | P0 |
| R12 | "good prompt for opus … in one go" | `02_OPUS_BUILD_PROMPT.md` is complete enough to run unattended | P0 |

Inherited from the legacy app (keep unless noted):

| # | Behaviour | Priority |
|---|---|---|
| I1 | One question per slide, reading order, printed label kept | P0 |
| I2 | Empty working slide after each question (global toggle, per-slide override P1) | P0 |
| I3 | Solutions, answer keys, instructions, headers/footers, logos excluded | P0 |
| I4 | Stitch questions split across columns and pages | P0 |
| I5 | Watermark removal (raster level) and table-rule removal | P1 |
| I6 | Bilingual twin handling (`en` / `hi` / `both`) | P1 |
| I7 | Duplicate-page skip | P1 |
| I8 | Review screen: reorder, delete, relabel, re-crop, add a missing question, undo | MVP = reorder + delete + preview; re-crop P1; the rest P2 |
| I9 | QA report (flags: `low_confidence`, `no_number`, `small_text`, `too_small_to_read`, …) | P1 |
| I10 | Open an existing `.pptx` for editing | Dropped for MVP |

---

## 3. The three problems, their nuances, and the fix

### Problem 1: Gemini on a free tier is slow and runs out fast

What was measured (legacy, 2026-10-19, AI Studio free key):

- **20 requests per day per Flash model.** Pro models had a quota of 0. The scarce resource is **requests per day**, not requests per minute.
- Frequent **503 "high demand"** even with quota left.
- The cheapest models ("flash-lite") return worse boxes, a row-wise reading order, and wrong language labels (§7).
- One request with 3 pages worked well. Larger batches made lite models skip pages.
- Keys from the **same Google Cloud project share one quota**. More keys help only if they come from different projects. Google's terms do not allow creating accounts to get around limits, so the app must say: use your own, your school's, or a paid key.
- Free-tier prompts and images may be used by Google to improve its products. Warn the user once (§6).

Why it feels slow: every scanned page cost a network round trip (10-25 s each) and the legacy server ran them with low parallelism.

Fix, in order of impact:

1. **Do not call the API when the phone can do it.** The layout step only needs *boxes and question numbers*. The pixels always come from the original page. So:
   - Digital PDFs: read the text layer (0 calls).
   - Scans and photos: run **on-device OCR** (ML Kit, offline) to get text lines with boxes, then run the same anchor parser used for text layers (0 calls).
   - Call Gemini only for pages where the integrity check fails (§5.1). Expect most clean papers to need 0 calls.
2. **Parallelise the calls that remain** across all keys with a slice scheduler (§5.2).
3. **Cache** every page layout by image hash + prompt version (legacy does this; keep it). A rerun costs nothing.
4. **Spend quota deliberately**: adaptive slice size, per-key rate limit, retire exhausted key/model lanes until midnight Pacific.

Quota arithmetic to show the owner: a 22-page photo paper at 2 pages per request is 11 requests. One free key with 3 usable Flash models at 20 each can do 60 requests a day, about 120 pages. Four keys from four projects: about 480 pages a day. With the OCR-first ladder most papers use far fewer.

Speed arithmetic: with 4 keys and a 25 s call, 11 slices take about 3 rounds, about 75 s. Sequentially it is 11 × 25 s, about 275 s.

### Problem 2: layout

Exactly two options, chosen on the options screen and switchable later without re-analysing (re-render and re-export only, **0 new API calls**):

- `TOP`: the question sits in the **top half**, full width. The bottom half stays free for the teacher's working.
- `TOP_RIGHT`: the question sits in **quadrant I** (top-right). The other three quadrants stay free.

Geometry and rules are in §5.3. The legacy app had "fill" and "top-right"; the owner now wants "top" and "top-right".

> Confirmed by the owner (2026-10-22): "top" means the *top half of the slide, full width, centred horizontally*.

Nuance: a half-height box makes tall questions small. Keep the legacy estimate of the displayed font size and the flags `small_text` (< 12 pt) and `too_small_to_read` (< 7 pt); never clip, never silently shrink without a flag.

### Problem 3: Render cold start

Removed by design: no server. State the trade-offs honestly in the app and docs: the phone does the work (battery, heat), and the Gemini step needs internet and a user-supplied key.

---

## 4. Target architecture

```
 UI (Jetpack Compose)  ──  Home → Options → Progress → Review → Export;  Settings → API keys
        │  starts a unique WorkManager job (expedited + foreground "dataSync")
        ▼
 JobRunner (one job at a time, resumable, cancellable)
   ├─ Ingest      SAF content:// → app cache copy; PDF/image page stream; EXIF; dedupe (dHash)
   ├─ PageSource  PdfRenderer (clip render) + text lines;  ImageSource (decode, exif, downscale)
   ├─ Layout ladder (§5.1)
   │     T0 text layer ─► T1 on-device OCR ─► T2 Gemini (slice scheduler, §5.2) ─► T3 image heuristic
   ├─ Assemble    stitch · passages · twins · duplicates · sections · integrity   (pure Kotlin)
   ├─ Imaging     crop · clean · trim · encode                                    (OpenCV)
   ├─ Project     project.json (items, parts, settings) = single source of truth
   └─ Pptx        hand-written OOXML writer, two layouts                           (java.util.zip)
 KeyVault (Android Keystore AES-GCM)    ·    ResultCache (per page, by hash)
```

Rules that keep it testable:

- **Domain code is Android-free** (`layout`, `assemble`, `keypool`, `scheduler`, `pptx`, `model`). It takes interfaces (`PageTextSource`, `Clock`, `HttpTransport`) and runs on a plain JVM, so most of it is unit-tested without a phone. Only `PdfRenderer`, ML Kit, OpenCV, Keystore, WorkManager and Compose are Android-specific.
- **Stream pages.** Never hold more than one full-resolution page in memory. Crops are rendered on demand with a clip rectangle, as the legacy code does.
- One `project.json` holds `pages`, `items`, `settings`, `undo`. Reuse the legacy schema (see `project.py`) so the data model is already proven.

---

## 5. New algorithms (these did not exist in the legacy app)

### 5.1 The layout ladder (what runs, in what order, and when to escalate)

Per page, try the cheapest method first and **escalate only when the result is not trustworthy**:

| Tier | Source | Cost | Used for |
|---|---|---|---|
| T0 | PDF text layer → `text_lines()` → classical parser | 0 calls, ~1 s/page | Digital PDFs |
| T1 | **On-device OCR** (ML Kit Text Recognition, Latin; Devanagari when needed) → same `text_lines()` shape → classical parser | 0 calls, ~1-3 s/page | Scanned PDFs, photos, screenshots, and T0 pages whose text layer is junk |
| T2 | Gemini vision layout through the slice scheduler | requests/day | Pages that still fail integrity after T1; two-page spreads; Hindi/bilingual when OCR is weak |
| T3 | Projection-profile image heuristic (`analyse_image_page`) | 0 calls | Last resort when no key works. Output is low confidence and flagged |

**Integrity decision** (this replaces the legacy `integrity_check().ok`, which has false passes, see §10):

A run of pages is **trusted** only if all of these hold:

1. At least 3 questions found, **all numbered**, and the numbers are consecutive per section (gaps and duplicates are failures). A restart to 1 starts a new section only after ≥ 3 questions in the previous one.
2. Every page is accounted for: it has ≥ 1 anchor, or it is recognised as an answer key, a continuation page, a blank/instructions page, or a duplicate.
3. **Zero found questions in a file is never "ok".** Neither is a page with ink but no anchors when its neighbours have anchors.
4. Answer-key pages are removed **before** counting (§10 shows why).

Pages in a failing range go to the next tier **as a group of the failing pages only** (not the whole file). After T2 the same check runs again; anything still failing is emitted with `low_confidence` / `no_number` flags, never silently.

OCR-specific care (new, needs a spike on the fixtures): OCR misreads leading numbers (`1.` → `l.` or `I.`). Make the anchor tolerant (`[lI|]` → 1, `O` → 0) **only** when the sequence expects it. Estimate page skew from OCR line angles and de-skew photos before cropping.

### 5.2 Parallel key scheduler ("slices")

Goal: use every live key at once, never idle a key, never lose a slice, finish fast, spend quota sensibly.

```
pages needing T2  ──►  slices of `slicePages` consecutive pages (default 2, range 1..4)
                         │
                         ▼   shared queue (Channel)
     ┌──────── worker(key A) ──┐   one coroutine PER KEY, 1 request in flight per key
     │         worker(key B)   │   each worker: wait for its own rate limit → take a slice →
     │         worker(key C)   │   call Gemini → on success store the result by page index
     └──────── worker(key D) ──┘   on failure put the slice back so ANOTHER key can take it
```

Rules:

- **One worker per key.** Rate limit is per key (default 8 requests/minute, configurable). A key that is cooling down sleeps; the others keep working. This is the work-stealing that makes it fast.
- **Slice = consecutive pages** so continuations stay together. Results are stored by page index, so completion order does not matter. A question split across two slices is stitched by the assembler (it works on page-level fragments with `continues_previous` / `continues_on_next`).
- **Adaptive slice size.** Start at 2 (owner's choice). If the estimated remaining daily budget is smaller than the number of slices needed, grow the slice up to 4 (fewer requests, slightly lower accuracy). If unknown, keep 2.
- **Outcomes** (port `keypool.py` semantics exactly, §9.1): 200 ok · 429 per-minute → that key+model cools for Google's `retryDelay` · 429 per-day (`PerDay`, `limit: 0`, or retry hint > 10 min) → that key+model retired until midnight Pacific · 400/401/403 bad key → key invalid · 404 → model missing for everyone · 5xx → model busy 20 s for everyone, use the next model.
- **Per-slice recovery ladder:** retry → other key → other model → split the slice into single pages → give the page to T3. A page the model skipped, or that looks empty inside a batch, is re-run alone (legacy does this).
- **Termination:** every slice is either done or handed to the next tier. If every worker is dead, drain the queue and return the leftovers. Never hang.
- **Cancel** stops workers promptly; completed page results stay cached.
- **No hedging** (duplicate requests to cut tail latency) by default: it burns scarce quota.
- Model order comes from config **and** is checked against `GET /v1beta/models` (free, uses no generation quota): drop names that do not exist, keep the user's order otherwise. Never rely on hard-coded names.

Kotlin skeleton (pure Kotlin; **compile it and test it, do not trust it blindly**):

```kotlin
data class ApiResult(val status: Int, val body: String, val pages: Map<Int, PageLayout>)   // pages keyed by page index

class SliceScheduler(
    private val lanes: KeyLanes,                 // port of keypool.py, state per (key, model)
    private val api: GeminiApi,                  // suspend fun layout(key, model, jpegs): ApiResult   (ONE attempt)
    private val jpegOf: suspend (Int) -> ByteArray,
    private val store: suspend (page: Int, layout: PageLayout) -> Unit,
) {
    class Slice(val pages: List<Int>, var tries: Int = 0)

    /** Returns the slices that could NOT be done by any key: hand them to the next tier. */
    suspend fun run(slices: List<Slice>): List<Slice> = coroutineScope {
        val queue = Channel<Slice>(Channel.UNLIMITED)
        val pending = AtomicInteger(slices.size)           // slices neither finished nor given up
        val failed = ConcurrentLinkedQueue<Slice>()        // given up after 3 errors
        slices.forEach { queue.trySend(it) }
        if (slices.isEmpty()) queue.close()
        fun settled() { if (pending.decrementAndGet() == 0) queue.close() }

        lanes.liveKeys().map { key ->
            launch {
                while (isActive) {
                    when (val pick = lanes.pickFor(key)) {           // best model THIS key may use now (does not count a request yet)
                        is Pick.Dead -> return@launch                  // invalid, or exhausted on every model
                        is Pick.Wait -> delay(pick.ms.coerceIn(200, 30_000))
                        is Pick.Go -> {
                            val s = queue.receiveCatching().getOrNull() ?: return@launch
                            lanes.markSent(key, pick.model)            // NOW the request counts against this key's per-minute window
                            val r = api.layout(key, pick.model, s.pages.map { jpegOf(it) })
                            when (lanes.report(key, pick.model, r.status, r.body)) {
                                Outcome.OK -> {
                                    r.pages.forEach { (p, l) -> store(p, l) }
                                    val missing = s.pages.filter { it !in r.pages }   // model skipped a page: re-run it alone
                                    if (missing.isNotEmpty()) {
                                        pending.addAndGet(missing.size)
                                        missing.forEach { queue.send(Slice(listOf(it), s.tries + 1)) }
                                    }
                                    settled()
                                }
                                Outcome.INVALID, Outcome.EXHAUSTED, Outcome.COOLING, Outcome.BUSY, Outcome.MISSING ->
                                    queue.send(s)                      // another key (or this one later) takes it; not the slice's fault
                                Outcome.ERROR ->
                                    if (++s.tries >= 3) { failed += s; settled() } else queue.send(s)
                            }
                        }
                    }
                }
            }
        }.joinAll()

        val left = mutableListOf<Slice>().apply { addAll(failed) }
        while (true) { left += queue.tryReceive().getOrNull() ?: break }   // all workers dead: whatever is still queued
        left
    }
}
```

Notes for the builder: a slice with more than 2 pages that keeps failing should be split into single pages before it is given up (recovery ladder above). `Pick.Wait` must be computed from the key's own window and lane cool-downs. If `queue.send` is called after `close()` it throws: only close when `pending` is 0, which cannot happen while a worker still holds a slice. Cover every branch with the unit tests in §8.3 (fake `GeminiApi` and a fake clock) **before** wiring the real client.

### 5.3 The two layouts

Slide is 16:9, **13.333 × 7.5 in**. Question picture box (inches), `INSET = 0.15`:

```kotlin
const val SLIDE_W = 13.333; const val SLIDE_H = 7.5; const val INSET = 0.15

enum class SlideLayout(val label: String, val centred: Boolean) {
    TOP("Top", centred = true),                         // top half, full width
    TOP_RIGHT("Top right (quadrant 1)", centred = false) // left-aligned inside quadrant I
}
data class RectIn(val x: Double, val y: Double, val w: Double, val h: Double)

fun SlideLayout.box(): RectIn = when (this) {
    SlideLayout.TOP       -> RectIn(INSET, INSET, SLIDE_W - 2 * INSET,        SLIDE_H / 2 - 2 * INSET)  // 13.033 x 3.45
    SlideLayout.TOP_RIGHT -> RectIn(SLIDE_W / 2 + INSET, INSET, SLIDE_W / 2 - 2 * INSET, SLIDE_H / 2 - 2 * INSET) // 6.3665 x 3.45
}
```

Placement (same as legacy `add_snapshot`, §9.9): scale the picture to fit the box keeping aspect ratio; top-aligned; horizontally centred for `TOP`, left edge of the box for `TOP_RIGHT`. **Never cross the horizontal centre line (y = 3.75 in); for `TOP_RIGHT` never cross the vertical centre line (x = 6.6665 in).** Cap short questions at about 36 pt on the slide (`MAX_PT`) instead of blowing them up.

The render resolution depends on the layout (`target_dpi`, §9.10), so the render cache key must include the layout. Switching layout re-renders crops and rewrites the deck; it never calls the API.

`tools/validate_pptx.py` enforces exactly these boxes. The Kotlin writer must pass it for both layouts.

### 5.4 Key vault (Android Keystore, AES-256-GCM)

Threat model: another app or a stolen backup reading the keys; keys leaking through logs, screenshots, crash reports or the APK. Not in scope: a rooted phone with an attacker present.

```kotlin
object KeyVault {
    private const val ALIAS = "papertoslides_vault_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)       // MVP: no biometrics / no lock-screen coupling
            .build()                                    // try setIsStrongBoxBacked(true) first, fall back on failure
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return byteArrayOf(1) + c.iv + c.doFinal(plain)             // [version][iv 12][ciphertext+tag]
    }

    fun open(blob: ByteArray): ByteArray {
        require(blob.isNotEmpty() && blob[0] == 1.toByte())
        val iv = blob.copyOfRange(1, 1 + IV_BYTES)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        return c.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }
}
```

Storage and handling rules:

- Store the sealed JSON list (`[{id, label, addedAt}]` + the secret inside the sealed blob) in a private file under `filesDir`. If `open()` throws (key invalidated, restored phone, `AEADBadTagException`), treat the vault as empty and ask for the keys again. **Do not crash.**
- `android:allowBackup="false"`, and `dataExtractionRules` / `fullBackupContent` exclude everything, so the sealed file cannot be restored on another phone (it could not be decrypted there anyway).
- The key goes **only** in the `x-goog-api-key` HTTP header over HTTPS to `generativelanguage.googleapis.com`. **Never in the URL** (URLs get logged). `network_security_config.xml` with `cleartextTrafficPermitted="false"`.
- Never log keys or request bodies. Release builds: no HTTP body logging. UI shows only `…last4`. The Keys screen sets `FLAG_SECURE` (no screenshots / recents thumbnail). After paste, clear the clipboard (`ClipboardManager.clearPrimaryClip()`, API 28+).
- Do not use `androidx.security:security-crypto` (deprecated); the code above is the supported route.
- Never put a key in `BuildConfig`, resources, assets, CI logs or the repo. Tests use fake keys.
- Validate a key with `GET /v1beta/models?pageSize=1` (free, no generation quota), exactly as the legacy `/api/keys/check` does.
- State of each key (ok / cooling / exhausted until HH:MM / invalid) is kept **in memory** and, as a hash of the key only, in a small cache so a dead key is not retried on the next job (legacy behaviour).

### 5.5 Low power, but first in line (background execution)

Interpretation: while a job runs it must not be killed or starved, and it must still be gentle on battery and temperature; when idle the app does nothing.

- Run the job as **WorkManager `CoroutineWorker` with `setForeground(...)`** (foreground service type `dataSync`, plus the manifest `<service ... tools:node="merge" android:foregroundServiceType="dataSync"/>` entry) and an ongoing notification with progress and a Cancel action. Request `POST_NOTIFICATIONS` on Android 13+. If the user denies it the job must still run.
- Android 15 limits `dataSync` foreground services to about 6 hours in 24: fine for a paper; handle the timeout callback by saving state.
- **Wake lock only while a job runs**, with a timeout, released in `finally`. No polling loops: everything is event-driven coroutines.
- **Bounded CPU:** `cpuWorkers = clamp(cores − 1, 1, 3)`. Heavy image stages take a permit from an adjustable limiter.
- **Thermal governor** (`PowerManager.addThermalStatusListener`, API 29+): `MODERATE` → 1 CPU worker; `SEVERE` or worse → pause CPU stages and wait for cool-down, tell the user. Battery saver on → 1 worker, skip the optional sharpening step. Battery < 15 % and not charging → warn before starting.
- **Memory:** one full page in memory at a time; `BitmapFactory.Options.inSampleSize` / `ImageDecoder` target size for decodes; recycle bitmaps; cap a clip render at about 4096 × 4096 px.
- **Network is only needed for T2.** Use a `NETWORK_CONNECTED` check at that stage, not as a job-wide constraint, so T0/T1 still finish offline. On loss of network: pause T2, keep everything else, resume on reconnect.
- Persist progress after every page so process death, reboot or a killed app resumes from the cache instead of restarting.
- `PdfRenderer` allows **one open page at a time and is not thread-safe**: serialise per document, or open one renderer per worker on separate file descriptors.

### 5.6 PPTX writer (no Apache POI)

Do **not** use Apache POI: heavy, awkward on Android. A deck with one picture per slide is a zip of XML. Plan:

1. Unzip `reference/reference_top.pptx` (see its part list below). Keep every **static** part as an asset: theme, slide master, the 11 slide layouts, `presProps`, `viewProps`, `tableStyles`, `docProps/*`. Drop `ppt/printerSettings/*`, `docProps/thumbnail.jpeg`, the notes parts (speaker notes are P2).
2. Generate the **dynamic** parts with `java.util.zip.ZipOutputStream`: `[Content_Types].xml` (a `Default` for `png`/`jpeg` and one `Override` per slide), `ppt/presentation.xml` (`<p:sldIdLst>`), `ppt/_rels/presentation.xml.rels`, `ppt/slides/slideN.xml` + `_rels/slideN.xml.rels` (layout 7, the Blank layout, plus the image), `ppt/media/imageN.png|jpeg`.
3. A question slide is one `<p:pic>`; a blank slide has an empty `spTree`. Positions are EMU (`inches × 914400`):

```xml
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
 <p:cSld><p:spTree>
  <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
  <p:pic>
   <p:nvPicPr><p:cNvPr id="2" name="Picture 1" descr="Question 7"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>
   <p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
   <p:spPr><a:xfrm><a:off x="1545828" y="137160"/><a:ext cx="9100038" cy="3154680"/></a:xfrm>
           <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
  </p:pic>
 </p:spTree></p:cSld>
 <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>
```

Slide size in `ppt/presentation.xml` must be `cx="12191695" cy="6858000"` (13.333 × 7.5 in), as in the reference decks. Escape XML in any text. Use `alt text` (`descr`) on pictures for accessibility. Encode PNG for text and line art, JPEG q92 only for colour photos (`encode()` rule, §9.5).

Validate every generated deck with `tools/validate_pptx.py` (CI cross-check) and open it in LibreOffice headless where available.

The reference deck's parts: `[Content_Types].xml`, `_rels/.rels`, `docProps/{core,app}.xml`, `ppt/{presentation.xml,presProps.xml,viewProps.xml,tableStyles.xml}`, `ppt/_rels/presentation.xml.rels`, `ppt/theme/theme1.xml`, `ppt/slideMasters/slideMaster1.xml` (+rels), `ppt/slideLayouts/slideLayout1..11.xml` (+rels), `ppt/slides/slide1..4.xml` (+rels), `ppt/media/image1..2.png`, plus notes parts and a thumbnail that you may drop.

---

## 6. Mobile UI brief

Phone first (360 dp wide and up), one-handed, thumb-reachable primary actions at the bottom, touch targets ≥ 48 dp, large text friendly (200 % font scale must not break layouts), light and dark following the system, TalkBack labels on every control, no auth.

Screens:

1. **Home:** one big "Choose paper" button (PDF and images, multiple, in the order picked; `OpenMultipleDocuments`). Below: recent decks (re-open, share, delete), a link to Keys.
2. **Options (bottom sheet):** the two layouts as **two visual cards** that draw the slide with the shaded question area (TOP / TOP RIGHT, default TOP). Toggles: blank slide after each question (default on), remove watermark (default on), language (English / Hindi / Both). One primary button "Make slides". If there are no keys: a calm one-line note "Works offline for most papers. Add a Gemini key for scans that need it." (not a blocker).
3. **Progress:** large stage + page counter, an honest ETA, per-key lane chips (ok / cooling 0:23 / exhausted until 05:30 / invalid) showing only `…last4`, a Cancel button. Survives rotation, app switch and process death. Also in the notification.
4. **Review:** a grid of question cards (thumbnail, label, flag badges). Tap → full preview drawn on a to-scale slide with the active layout; actions: delete, move up/down (long-press drag P1), switch layout (re-renders), "check this" for flagged slides. Re-crop is P1.
5. **Export:** **Save to Downloads** (MediaStore on API 29+) and **Share** (FileProvider intent) the `.pptx`; also the QA report. Filename `<paper-name>-slides.pptx`, never overwrite (add ` (2)`).
6. **Keys (Settings):** paste a key (or several, one per line), "Check" (free `models.list`), mask, delete, state per key, the daily-quota reset time shown in local time, a short honest note about free-tier data use and about same-project keys sharing one quota. `FLAG_SECURE` on.

Empty, loading and error states for every screen. Short, plain wording (the owner and the teachers are not technical).

---

## 7. Learnings from the legacy build

**Quota and models**
- Requests per day is the real limit (20 per Flash model per key observed), so batch pages and cache everything. Lite models are the last resort.
- Detect the kind of 429: a daily 429 (`PerDay` in the text, `limit: 0`, or a retry hint over 10 minutes) retires the key+model until midnight Pacific; a per-minute 429 only cools it for the suggested `retryDelay`.
- A 503 is about the **model**, not the key: pause that model for everyone for about 20 s and use another.
- A 400 that mentions `thinking` means the model does not accept `thinkingConfig`: retry the same request without it.
- Truncated or unparsable JSON happens; retry up to 3 times, then escalate.
- Cache key = `sha256(page jpeg bytes + PROMPT_VERSION)`. **Bump `PROMPT_VERSION` whenever the prompt or schema changes**, or stale results survive.
- Cache lite-model results as "soft" entries that a better model replaces next time.

**Layout and geometry**
- Never trust the model's reading order. Recompute it geometrically (XY-cut, §9.4). Never trust its language label either: derive it from the script ratio of the text (Devanagari vs Latin).
- Model boxes drift by up to a line or more. Snap box tops to hanging-indent block starts (`refine.py`), then trim blank margins by ink (`snap`, `tighten`). Moves are bounded (≤ 6 % of page height) so a wrong snap cannot travel far.
- A question's numbered anchor is only accepted if it **continues the sequence** (expected, expected+1..+3, or a restart to 1). That one rule rejects `(a) 1 (b) 2` option lines and numbers inside stems.
- Text that repeats on many pages (running headers, "Trade Theory" bands, footers) and images repeated at the same place (watermark art) are clutter.
- Table layouts: a "column" with no question numbers beside one that has them is a figure/option block, not a column. A second number cell on the same row is the QID column: erase it, keep the serial number.
- Duplicate photos of the same page: dHash with 16×16 bits, Hamming distance ≤ 12 means duplicate.
- Detect bilingual papers by script ratio; the same number printed twice with a script gap ≥ 0.3 are twins; keep English by default.

**Pixels**
- **Crop from the original source at high resolution**, never from the 1536 px layout image. Digital PDF: clip-render at the needed dpi (about 250 px per displayed inch, up to 600). Photos: use native pixels, upscale gently, sharpen. A blurry photo stays blurry; say so.
- Render with a clip rectangle so a big page is never held at high resolution.
- Keep the printed question number visible: **never trim the left edge** in `tighten`; ignore long table rules and marks columns when measuring ink.
- Dark frames (video, dark mode screenshots): invert to grey; plain-text dark frames can be rebuilt as native slide text (P2).
- EXIF orientation first (`exif_transpose`), always.

**Process**
- Born-digital papers with clean numbering need **zero** API calls; the integrity check is both the routing rule and the teacher-facing QA.
- Editor race conditions showed up in browser testing: serialise edits, bump a `rev` counter on every edit, never save a stale copy (re-read inside the lock).
- Free-tier host limits (sleep, 512 MB) were the reason for streaming and for single-job processing. On a phone the same discipline keeps memory flat.

### Dos

- Do try T0 → T1 before any API call; do use the API only for failing pages.
- Do keep a cache keyed by image hash; do make every stage resumable.
- Do keep domain logic Android-free and unit-test it with the fixtures and golden files.
- Do show what is happening (stage, counts, per-key state) and flag every uncertain slide.
- Do validate every generated `.pptx` with `validate_pptx.py`.
- Do ask before installing software or accepting licences on the owner's machine.
- Do pin dependency versions to what is current at build time (look them up; do not trust memory).
- Do write every file the owner will need in plain language (`HOW_TO_INSTALL.md`).

### Don'ts

- Don't modify, delete or reformat anything in the legacy repo.
- Don't ship, log, screenshot, back up or commit an API key. Don't put a key in a URL.
- Don't hard-code model names or quota numbers as truths.
- Don't use Apache POI, `androidx.security:security-crypto`, or MuPDF (AGPL) without telling the owner.
- Don't hold more than one full page at high resolution; don't render PDFs on the main thread.
- Don't poll; don't leave a wake lock held; don't run a job without a visible notification.
- Don't claim a deck is fine because `integrity_ok` is true (see §10).
- Don't silently drop a page: every page is a question page, a known non-question page, a duplicate, or a flagged failure.
- Don't add accounts, analytics, ads or a backend.

---

## 8. Test plan

### 8.1 Fixtures, golden files and tools

Generate and verify (needs `pip install pymupdf pillow python-pptx`):

```bash
python tools/make_fixtures.py fixtures
python tools/dump_lines.py "D:/Development Project/Coaching Problem Solution/pdf2ppt" fixtures fixtures/lines
python tools/reference_run.py "D:/Development Project/Coaching Problem Solution/pdf2ppt" fixtures <scratch dir>
python tools/validate_pptx.py out.pptx --layout top --expect-questions 30
```

`fixtures/expected.json` holds the **correct** answer for each fixture (by construction) and, where the legacy Python is wrong, its wrong answer and why. **The Android app must match `expected`, not the legacy result.**

| Fixture | Correct result | Legacy Python result (known defect) |
|---|---|---|
| `digital_1col.pdf` (2 pages) | 8 questions, 0 API calls | 8 slides but Q8 has no number: page 2 has < 200 chars, so it is classed as a scan |
| `digital_2col_split.pdf` (3 pages) | exactly 30 questions 1..30, stitched splits, URL header / page numbers / answer-key page excluded, 0 API calls | 45 slides: the answer-key page was parsed as questions; `integrity_ok` still True |
| `scan_pages.pdf` | 30 questions via on-device OCR, 0 API calls | 38 unnumbered blocks, `integrity_ok` True (false pass) |
| `photo_exif_rot.jpg` | upright after EXIF transpose; questions of page 1 found | 4 unnumbered blocks |
| `garbage_textlayer.pdf` | T0 fails the integrity check and escalates; never finishes silently | 0 questions, no flags, `integrity_ok` True |
| `password.pdf` | friendly "This PDF needs a password" message, no crash | `ValueError` |
| `corrupt.pdf` | friendly "Could not read this file", no crash | `FzErrorFormat` |

Real papers (Hindi, bilingual two-page spreads, photos of printed pages) are copyrighted and are **not** in the pack. The owner can test with their own; the synthetic fixtures cover the logic.

### 8.2 Test cases

Legend: **U** = JVM unit test (pure Kotlin), **I** = instrumented / on-device, **M** = manual on a phone, **C** = CI check.

**A. Input**

| ID | Scenario | Why it matters | Expected | Level |
|---|---|---|---|---|
| A1 | `digital_1col.pdf` | Short last page misclassified as scan in legacy | 8 numbered questions, 0 calls | U (from `lines.json`) + I |
| A2 | `digital_2col_split.pdf` | Column and page splits; header/footer; answer key | 30 questions, flags `split_stitched` (+ `page_break_stitched`), key page excluded | U + I |
| A3 | `scan_pages.pdf` | OCR tier replaces the API | 30 questions, 0 API calls | I |
| A4 | `photo_exif_rot.jpg` | EXIF orientation 6, 2.5° skew | Upright, de-skewed, questions found | I |
| A5 | `garbage_textlayer.pdf` | Silent 0-question failure | Escalates to OCR, then Gemini; never finishes with 0 | U + I |
| A6 | `password.pdf`, `corrupt.pdf`, 0-byte file, a `.docx` renamed `.pdf` | Crash paths | Clear message, app stays alive, other files in the same job still processed | I |
| A7 | PDF with `/Rotate 90`, a CropBox ≠ MediaBox, landscape pages | Coordinate transforms | Boxes line up with the rendered crop | I |
| A8 | HEIC, WebP, PNG, JPEG; TIFF (unsupported on Android) | Decoder support differs | Supported ones work; TIFF gets a friendly message | I |
| A9 | 5 files picked in a chosen order, including Drive / Downloads `content://` URIs and unicode / spaced filenames | Order and URI handling | Order kept, files copied to cache, names sanitised | I/M |
| A10 | 200-page PDF, 150 MB | Memory, time, thermal | No OOM, flat memory, page cap warning at the configured limit (default 150), cancel works | M |
| A11 | Two-page booklet spread photo (bilingual) | Reading order across a fold, twins | Needs T2; spread read left page then right; twins dropped per language setting | M (real paper) |
| A12 | Same photo picked twice, or pages 1 and 2 nearly identical | dHash dedupe | Second skipped, no API call spent | U |

**B. Keys and the API**

| ID | Scenario | Expected | Level |
|---|---|---|---|
| B1 | No keys | T0/T1 only; clear banner; flagged low-confidence results where OCR failed; never blocks | I |
| B2 | One invalid key (400 `API_KEY_INVALID`) | Marked invalid, skipped, job continues with the others | U |
| B3 | N keys from the same project | All exhaust together; detect quickly; message explains shared quota | U |
| B4 | 429 per minute with `retryDelay` | That lane cools; its slice is taken by another key; no slice lost | U |
| B5 | 429 per day (`PerDay`, `limit: 0`, retry > 600 s) | Lane retired until midnight Pacific; UI shows the local reset time | U |
| B6 | 503 on one model | Model busy 20 s for all keys; next model used | U |
| B7 | Network lost mid-T2 | T2 pauses, state kept, resumes on reconnect; offline tiers unaffected | I/M |
| B8 | Truncated JSON, or a response missing a page | Retry ≤ 3; split the slice; then T3; page never lost | U |
| B9 | Model skips a page, or looks empty inside a batch | Re-run that page alone | U |
| B10 | Pasted keys with spaces, quotes, commas, duplicates, a trailing newline | Parsed to distinct keys of length ≥ 20 (`split_keys`) | U |
| B11 | A configured model returns 404 | Marked missing; `models.list` discovery fills the gap | U |
| B12 | "User location is not supported" (400) | Clear, calm message; not shown as "invalid key" | U |
| B13 | User deletes a key while a job runs | That worker stops cleanly; slices re-queued | U |
| B14 | Security sweep | No key in logcat, files outside the vault, crash reports, backups, screenshots; none in the APK (`strings`/`apkanalyzer`) | C/M |
| B15 | Throughput | 4 fake keys with a 2 s fake latency finish 11 slices in about 6 s (3 rounds), not 22 s | U |

**C. Layout and output**

| ID | Scenario | Expected | Level |
|---|---|---|---|
| C1 | `TOP` and `TOP_RIGHT` decks of the 30-question fixture | `validate_pptx.py` passes for both (inside box, never crossing the centre lines, alternating blank slides) | C |
| C2 | Switch layout after processing | Re-render and re-export only, 0 API calls, resolution recomputed | U/I |
| C3 | Very tall question (long passage + table) in `TOP` | Flagged `small_text` / `too_small_to_read`, never clipped | U |
| C4 | Very short question | Not enlarged past about 36 pt | U |
| C5 | Blank-after global off, one slide overridden on | Deck matches | U |
| C6 | Shared passage with 3 questions | Passage on the first slide; later slides say "Passage: see slide k" | U |
| C7 | 50-question deck size | Under 15 MB | C |
| C8 | Opens in PowerPoint 2013+, LibreOffice, WPS, Google Slides, PowerPoint mobile | No repair prompt | C (python-pptx + LibreOffice headless) / M |
| C9 | Save to Downloads twice with the same name | `name (2).pptx`; no overwrite; Share works | I |
| C10 | Disk full on export | Friendly error; project kept | I |

**D. Robustness, power, UI**

| ID | Scenario | Expected | Level |
|---|---|---|---|
| D1 | Screen off / app switched mid-job | Job continues (foreground service); notification shows progress | M |
| D2 | Process killed mid-job | Resumes from cache; no page re-sent to the API | I |
| D3 | Rotation, dark mode toggle, font scale 200 %, 320 dp wide phone | No restart of the job; no clipped text | I/M |
| D4 | Battery saver, thermal moderate / severe | Fewer workers, pause on severe, user told | I/M |
| D5 | Notification permission denied (Android 13+) | Job still runs | M |
| D6 | Cancel | Stops within seconds; completed pages stay cached; no half-written deck | I |
| D7 | TalkBack pass on Home → Options → Progress → Review → Export | Every control named; reading order sensible | M |
| D8 | Cold start of the app | Home visible in under 1 s on a mid phone | M |

### 8.3 Unit-test list (write these first, they are the safety net)

1. **KeyLanes** port: the full table of 200 / 429-minute / 429-day / 400 / 404 / 5xx outcomes; `parse_retry` on both hint formats; sliding RPM window; `next_reset` to midnight Pacific; key hashing; `split_keys`.
2. **SliceScheduler**: B4, B5, B6, B8, B13, B15, plus "all keys dead → leftovers returned, no hang" and "cancel mid-run".
3. **Classical parser** on `lines.json`: A1, A2, A5, the `ANCHOR` regex cases (`Q.43`, `(1)`, `12.`), repeated-header detection, column detection with a table layout.
4. **Assemble**: continuation stitching, bilingual twins by script ratio, duplicates, section restarts, integrity (including the new rules of §5.1).
5. **`reading_order`** XY-cut with a full-width block between columns.
6. **Layout geometry**: both boxes, centre-line rule, `target_dpi` per layout.
7. **PPTX writer**: build the A2 deck in both layouts, run `validate_pptx.py` in CI, assert slide counts.
8. **KeyVault** (instrumented): seal/open round trip, wrong blob → graceful empty state.

### 8.4 Definition of done (MVP)

- An APK installs on a real Android phone (Android 8.0 / API 26 and up) and the owner can: add a key, pick a PDF, pick a layout, get a `.pptx`, open it in PowerPoint or Slides.
- All unit tests pass; CI is green; `validate_pptx.py` passes for both layouts on the 30-question fixture.
- `digital_1col` and `digital_2col_split` need 0 API calls; `scan_pages` needs 0 API calls once OCR works.
- Keys: security sweep B14 is clean.
- `HOW_TO_INSTALL.md` exists and is written for a non-developer. `KNOWN_ISSUES.md` and `DECISIONS.md` exist and are honest.
- The legacy repo is untouched (`git status` clean).

---

## 9. Legacy code worth porting (verbatim)

Everything below is copied unchanged from `pdf2ppt/qdeck/`. Each block says what to keep and how to port it. Where the legacy has a defect, §10 lists it and the Kotlin port must **not** reproduce it. Python → Kotlin map: `numpy`/`cv2` → OpenCV Android `Mat`; `PIL.Image` → `Bitmap`; `threading.Lock` → `Mutex`/`synchronized`; `requests` → OkHttp; `dataclass` → `data class`.


### 9.1 `keypool.py`: many keys, lanes, outcome mapping (P0)

**Keep:** the lane model (a lane = key × model, states `ok` / `cooling` / `exhausted`), the outcome table in `report()`, `parse_retry`, `next_reset` (midnight Pacific), `split_keys`, `mask`, and the registry keyed by a **hash** of the key (never the key).
**Change:** one coroutine per key (§5.2), so `acquire()` becomes `pickFor(key)`: the best model *this* key can use now, or a wait, or dead. Inject a `Clock`; no module-level global. `parallelism` is no longer needed (workers = live keys). Persist only the hash-keyed health, never a key.
**Test with:** §8.3 item 1.

```python
# source: pdf2ppt/qdeck/keypool.py
"""Many Gemini API keys behind one call site.

Each (key, model) pair is a "lane" with its own state:
  ok -> cooling (per-minute 429, short retry) -> ok
  ok -> exhausted (daily free-tier quota; until Google's retry hint or next midnight PT)
A key can also be `invalid` (bad / revoked key) and a model can be `missing` (404) or
`busy` (503 for everybody, short pause).

acquire() returns the best model that any key can serve right now, rotating keys
round-robin so load spreads evenly; when every usable lane is only cooling it tells the
caller how long to wait; when nothing can ever succeed today it raises NoLane.

Health is shared across jobs (module-level registry keyed by a hash of the key, never
the key itself), so a key that ran dry in one job is skipped by the next one.
Keys live in memory only.
"""
import datetime as dt
import hashlib
import re
import threading
import time

try:
    from zoneinfo import ZoneInfo
    _PT = ZoneInfo("America/Los_Angeles")
except Exception:  # pragma: no cover
    _PT = None

_REGISTRY = {}          # key hash -> {"invalid": bool, "lanes": {model: {...}}, "calls": int}
_REG_LOCK = threading.Lock()


class NoLane(Exception):
    pass


def mask(key):
    return "…" + key[-4:] if key else "…"


def _khash(key):
    return hashlib.sha256(key.encode()).hexdigest()[:16]


def next_reset():
    """Free-tier daily quotas reset at midnight Pacific time."""
    if _PT is None:
        return time.time() + 6 * 3600
    now = dt.datetime.now(_PT)
    tomorrow = (now + dt.timedelta(days=1)).replace(hour=0, minute=0, second=5, microsecond=0)
    return tomorrow.timestamp()


def parse_retry(msg):
    """Seconds from Google's hints: '"retryDelay": "37s"' or 'retry in 16h41m51.3s'."""
    m = re.search(r'retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"', msg)
    if m:
        return float(m.group(1))
    m = re.search(r"retry in\s+(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+(?:\.\d+)?)s)?", msg)
    if m and any(m.groups()):
        h, mi, s = (float(x) if x else 0.0 for x in m.groups())
        return h * 3600 + mi * 60 + s
    return None


def split_keys(text):
    keys = []
    for k in re.split(r"[\s,;]+", text or ""):
        k = k.strip().strip('"\'')
        if len(k) >= 20 and k not in keys:
            keys.append(k)
    return keys


class KeyPool:
    def __init__(self, keys, models, rpm_per_key=8):
        self.keys = list(dict.fromkeys(k for k in keys if k))
        self.models = list(models)
        self.rpm = max(1, rpm_per_key)
        self.lock = threading.Lock()
        self.recent = {k: [] for k in self.keys}     # request timestamps per key
        self.rr = 0
        self.model_busy_until = {}
        self.model_missing = set()
        with _REG_LOCK:
            for k in self.keys:
                _REGISTRY.setdefault(_khash(k), {"invalid": False, "lanes": {}, "calls": 0, "error": None})

    def __len__(self):
        return len(self.keys)

    # ------------------------------------------------------------------ state
    def _rec(self, key):
        return _REGISTRY[_khash(key)]

    def _lane(self, key, model):
        return self._rec(key)["lanes"].setdefault(model, {"until": 0.0, "kind": "ok"})

    def acquire(self, better_only=False):
        """-> (key, model) or (None, wait_seconds). Raises NoLane if nothing is left."""
        with self.lock, _REG_LOCK:
            now = time.time()
            soonest = None
            any_future = False
            for model in self.models:
                if model in self.model_missing or (better_only and "lite" in model):
                    continue
                busy = self.model_busy_until.get(model, 0)
                n = len(self.keys)
                for j in range(n):
                    key = self.keys[(self.rr + j) % n]
                    rec = self._rec(key)
                    if rec["invalid"]:
                        continue
                    lane = self._lane(key, model)
                    wait = max(lane["until"] - now, busy - now, 0.0)
                    if lane["kind"] == "exhausted" and lane["until"] > now:
                        continue                     # dead for today
                    self.recent[key] = [t for t in self.recent[key] if now - t < 60]
                    if len(self.recent[key]) >= self.rpm:
                        wait = max(wait, 60 - (now - self.recent[key][0]))
                    if wait <= 0:
                        self.rr = (self.rr + j + 1) % n
                        self.recent[key].append(now)
                        rec["calls"] += 1
                        return key, model
                    any_future = True
                    soonest = wait if soonest is None else min(soonest, wait)
            if not any_future:
                raise NoLane("every key is invalid or out of daily quota for every model")
            return None, soonest

    def report(self, key, model, status, body=""):
        """Feed back the HTTP outcome of a request made with (key, model)."""
        with self.lock, _REG_LOCK:
            now = time.time()
            rec = self._rec(key)
            lane = self._lane(key, model)
            low = body.lower()
            if status == 200:
                lane.update(kind="ok", until=0.0)
                rec["error"] = None
                return "ok"
            if status in (400, 401, 403) and ("api key" in low or "api_key" in low or "permission" in low
                                              or "unauthenticated" in low):
                rec["invalid"] = True
                rec["error"] = "invalid key"
                return "invalid"
            if status == 404:
                self.model_missing.add(model)
                return "missing"
            if status == 429:
                retry = parse_retry(body)
                daily = "perday" in low.replace("_", "").replace(" ", "") or "limit: 0" in low or \
                    (retry is not None and retry > 600)
                if daily:
                    lane.update(kind="exhausted", until=now + retry if retry and retry > 600 else next_reset())
                    rec["error"] = f"daily quota used on {model}"
                    return "exhausted"
                lane.update(kind="cooling", until=now + (retry if retry else 20.0) + 1.0)
                rec["error"] = "rate limited (per minute)"
                return "cooling"
            if status in (500, 502, 503, 504):
                self.model_busy_until[model] = now + 20.0
                return "busy"
            lane.update(kind="cooling", until=now + 10.0)
            return "error"

    def stats(self):
        out = []
        with _REG_LOCK:
            now = time.time()
            for k in self.keys:
                rec = self._rec(k)
                lanes = [rec["lanes"].get(m, {"kind": "ok", "until": 0}) for m in self.models
                         if m not in self.model_missing]
                if rec["invalid"]:
                    state = "invalid"
                elif lanes and all(l["kind"] == "exhausted" and l["until"] > now for l in lanes):
                    state = "exhausted"
                elif any(l["kind"] == "ok" or l["until"] <= now for l in lanes):
                    state = "ok"
                else:
                    state = "cooling"
                out.append({"masked": mask(k), "state": state, "calls": rec["calls"], "error": rec["error"]})
        return out

    @property
    def parallelism(self):
        """More keys -> more requests in flight (bounded for a small server)."""
        live = sum(1 for k in self.keys if not self._rec(k)["invalid"])
        return max(1, min(8, live * 2))
```

### 9.2 `layout_gemini.py`: prompt, schema, request, cache (P0)

**Keep verbatim:** `PROMPT`, `SCHEMA`/`PAGE_SCHEMA`, the request body shape, the `x-goog-api-key` header (never a URL parameter), the `thinkingConfig` 400 fallback, the bad-JSON retry (≤ 3), the "incomplete page inside a batch is re-run alone" rule, and the hash-keyed cache with "soft" lite entries. Bump `PROMPT_VERSION` if you touch the prompt or schema.
**Change:** `requests` → OkHttp (`callTimeout` about 420 s). `_call` becomes a **single attempt** `GeminiApi.layout(key, model, jpegs) → status + body + parsed pages`; key and model rotation moved into the scheduler (§5.2). Fixed 3-page batches become **slices** (default 2). Page JPEGs: 1536 px long side, quality 82 (`Bitmap.compress`). Cache under `cacheDir/layout/`. Model names come from config, checked against `models.list` (§10 #6, #7).

```python
# source: pdf2ppt/qdeck/layout_gemini.py
"""Vision-LLM layout pass: one Gemini call per page returns question fragments with
roles, labels, continuation flags and normalised boxes (schema-constrained JSON).

Cost control: downscaled JPEG input, one call per page, on-disk cache keyed by the
image hash + prompt version, client-side RPM throttle, exponential backoff on 429/5xx,
and model fallback. When every model is exhausted the caller falls back to the
classical (no-LLM) layout.
"""
import base64
import hashlib
import json
import os
import random
import threading
import time

import requests

from .keypool import KeyPool, NoLane, mask

PROMPT_VERSION = "v5"
API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

ROLES = ["question", "continuation", "shared_passage", "solution", "answer_key",
         "instructions", "section_header", "clutter"]

PAGE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "page": {"type": "INTEGER"},
        "page_kind": {"type": "STRING", "enum": ["questions", "cover_or_instructions", "answer_key",
                                                 "solutions", "blank_or_ad", "mixed"]},
        "dark_background": {"type": "BOOLEAN"},
        "watermark_present": {"type": "BOOLEAN"},
        "fragments": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "order": {"type": "INTEGER"},
            "role": {"type": "STRING", "enum": ROLES},
            "label": {"type": "STRING", "nullable": True},
            "number": {"type": "INTEGER", "nullable": True},
            "continues_previous": {"type": "BOOLEAN"},
            "continues_on_next": {"type": "BOOLEAN"},
            "box": {"type": "ARRAY", "items": {"type": "INTEGER"}},
            "lang": {"type": "STRING", "enum": ["en", "hi", "bilingual", "other"]},
            "has_figure": {"type": "BOOLEAN"},
            "has_math": {"type": "BOOLEAN"},
            "has_table": {"type": "BOOLEAN"},
            "stem": {"type": "STRING"},
            "options": {"type": "ARRAY", "items": {"type": "STRING"}},
            "confidence": {"type": "NUMBER"},
        }, "required": ["order", "role", "box", "continues_previous", "continues_on_next", "lang",
                        "has_figure", "has_math", "has_table", "confidence"]}},
    },
    "required": ["page", "page_kind", "dark_background", "watermark_present", "fragments"],
}
SCHEMA = {"type": "OBJECT", "properties": {"pages": {"type": "ARRAY", "items": PAGE_SCHEMA}},
          "required": ["pages"]}

PROMPT = """You are a document layout analyser for exam question papers. You receive {n} page image(s), in document order, each preceded by its tag PAGE k. Analyse EVERY page and return one entry per page in "pages" (page = k). Each image is one page (it may be a photo of a two-page booklet spread, a scan, a screenshot or a video frame; it may have 1-4 text columns, a table layout, watermarks, logos, headers and footers).

Return every content region as a fragment, in true human READING ORDER (columns left-to-right, top-to-bottom inside a column; full-width blocks in their natural position; for a two-page spread finish the whole left page before the right page).

Roles:
- question: a region that STARTS a question. It must include the printed question number/label, the stem, any figure/diagram/table/graph belonging to it (also when placed beside the text), statements/lists, and all its options. One question = one fragment unless it is physically split.
- continuation: the rest of a question split by a column break or page break (e.g. options sitting at the top of the next column, or a first block of a page that has no question number / says "contd."). Set continues_previous=true. A line like "(a) 1 (b) 2 (c) 3 (d) 4" at the top of a column is a continuation, never a new question.
- shared_passage: a passage, data table or figure needed by several following questions (comprehension, data interpretation). Short generic directions like "Directions (Q.1-5): choose the correct option" are role=instructions.
- solution: "Ans.", "Sol.", "Answer:", hints, worked solutions or explanations. answer_key: answer tables/lists.
- instructions, section_header (e.g. "PART-A", "SECTION A", "Trade Theory", subject bands), clutter (logos, running headers/footers, URLs, page numbers, brand banners, marks/negative-marks columns, video player UI, subscribe banners, cursors).

Rules:
- box = [ymin, xmin, ymax, xmax] normalised 0-1000 on THAT page's image. Question boxes must be COMPLETE (question number, every option line, every figure) and must not include neighbouring questions, other columns, solutions or clutter. Fragments must not overlap.
- label = the question label exactly as printed ("7.", "Q.43", "(1)", "12"); number = its integer. Option labels (a)/(A)/(1)/A1 are never question labels; numbers inside a stem are not labels. In a table layout the serial-number cell is the label.
- continues_on_next = true if the question is cut at the bottom of its column/page and continues later (possibly on the next page).
- A continuation at the top of PAGE k+1 belongs to the open question at the end of PAGE k; still return it on PAGE k+1 with continues_previous=true.
- lang: language of the question text (bilingual = English and Hindi both inside this fragment).
- stem: the question text in plain Unicode (maths with Unicode symbols, Devanagari as is), without the label. options: each option text without its (a)/(b) label. For a continuation give only the text it contains.
- watermark_present: true if a faint logo/text watermark sits behind the page content.
- dark_background: true if the page has light text on a dark background (e.g. a video frame).
- confidence 0-1.
Skip nothing that is a question. Do not invent questions."""


class QuotaExhausted(Exception):
    pass


class GeminiLayout:
    def __init__(self, settings, log=print, keys=None):
        self.s = settings
        self.log = log
        self.models = list(settings.model_list)
        self.pool = KeyPool(keys if keys else settings.key_list, self.models, settings.gemini_rpm)
        self.lock = threading.Lock()
        self.calls = 0
        self.only_lite = False
        self.cache_hits = 0
        os.makedirs(os.path.join(settings.cache_dir, "layout"), exist_ok=True)

    def _cache_path(self, jpeg):
        h = hashlib.sha256(jpeg + PROMPT_VERSION.encode()).hexdigest()
        return os.path.join(self.s.cache_dir, "layout", h + ".json")

    def analyse(self, jpeg):
        return self.analyse_batch([jpeg])[0]

    def analyse_batch(self, jpegs):
        """One request for several pages; per-page results are cached individually so
        a rerun (or a different batch size) costs nothing."""
        results = [None] * len(jpegs)
        soft = {}      # lite-model results: used only if no better model answers
        todo = []
        for i, j in enumerate(jpegs):
            cp = self._cache_path(j)
            if os.path.exists(cp):
                with open(cp, encoding="utf-8") as f:
                    cached = json.load(f)
                if "lite" in cached.get("_model", "") and not self.only_lite:
                    soft[i] = cached
                    todo.append(i)
                else:
                    self.cache_hits += 1
                    results[i] = cached
            else:
                todo.append(i)
        if not todo:
            return results
        if not len(self.pool):
            raise QuotaExhausted("no Gemini API key configured")
        if soft and len(soft) == len(todo):
            try:
                return self._fresh(jpegs, todo, results, better_only=True)
            except QuotaExhausted:
                self.only_lite = True
                for i, pg in soft.items():
                    self.cache_hits += 1
                    results[i] = pg
                return results
        return self._fresh(jpegs, todo, results)

    def _fresh(self, jpegs, todo, results, better_only=False):
        parts = []
        for k, i in enumerate(todo, 1):
            parts.append({"text": f"PAGE {k}"})
            parts.append({"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(jpegs[i]).decode()}})
        parts.append({"text": PROMPT.replace("{n}", str(len(todo)))})
        out = self._call(parts, better_only=better_only)
        by_page = {}
        for pg in out.get("pages", []):
            by_page.setdefault(pg.get("page"), pg)
        for k, i in enumerate(todo, 1):
            pg = by_page.get(k)
            if pg is None:
                continue  # model skipped a page; caller falls back for it
            pg["_model"] = out["_model"]
            n_q = sum(1 for fr in pg.get("fragments", []) if fr.get("role") == "question")
            if pg.get("page_kind") in ("questions", "mixed") and n_q <= 1 and len(todo) > 1:
                # suspiciously empty page inside a batch: retry it alone, don't cache this
                self.log(f"page result looks incomplete ({n_q} question); retrying it alone")
                try:
                    pg = self.analyse_batch([jpegs[i]])[0] or pg
                except QuotaExhausted:
                    pass
                results[i] = pg
                continue
            # lite results are cached too, but as "soft" entries a better model may replace
            with open(self._cache_path(jpegs[i]), "w", encoding="utf-8") as f:
                json.dump(pg, f, ensure_ascii=False)
            results[i] = pg
        return results

    def _call(self, parts, better_only=False):
        """Send one request through the key pool. Rotates keys/models on 429/503,
        waits when every lane is only cooling, gives up when every lane is dead."""
        gen = {"responseMimeType": "application/json", "responseSchema": SCHEMA,
               "temperature": 0, "maxOutputTokens": 60000, "thinkingConfig": {"thinkingLevel": "low"}}
        no_thinking = set()
        last_err, waited, bad_json = None, 0.0, 0
        deadline = time.time() + (120 if better_only else 1800)
        while time.time() < deadline:
            try:
                key, model = self.pool.acquire(better_only=better_only)
            except NoLane as e:
                raise QuotaExhausted(f"{e}; last error: {last_err}")
            if key is None:                      # everything is cooling: wait it out
                wait = min(max(model, 1.0), 90.0)
                if better_only and wait > 30:
                    raise QuotaExhausted("better models busy")
                self.log(f"all keys busy; waiting {wait:.0f}s")
                time.sleep(wait)
                waited += wait
                continue
            g = {k: v for k, v in gen.items() if not (k == "thinkingConfig" and model in no_thinking)}
            body = {"contents": [{"parts": parts}], "generationConfig": g}
            try:
                r = requests.post(API.format(model=model), json=body, timeout=420,
                                  headers={"x-goog-api-key": key})
            except requests.RequestException as e:
                last_err = f"network: {type(e).__name__}"
                self.pool.report(key, model, 503, "")
                time.sleep(2 + random.random() * 3)
                continue
            if r.status_code == 400 and "thinking" in r.text.lower() and model not in no_thinking:
                no_thinking.add(model)
                continue
            outcome = self.pool.report(key, model, r.status_code, r.text)
            if r.status_code == 200:
                try:
                    j = r.json()
                    out = json.loads(j["candidates"][0]["content"]["parts"][-1]["text"])
                except Exception as e:          # truncated / blocked output
                    bad_json += 1
                    last_err = f"{model}: unreadable response ({type(e).__name__})"
                    if bad_json >= 3:
                        raise QuotaExhausted(last_err)
                    continue
                with self.lock:
                    self.calls += 1
                out["_model"] = model
                return out
            last_err = f"{model}: HTTP {r.status_code}"
            if outcome in ("exhausted", "invalid", "missing"):
                self.log(f"key {mask(key)} / {model}: {outcome}; switching")
            elif outcome == "busy":
                self.log(f"{model} overloaded (503); trying another model")
        raise QuotaExhausted(last_err or "timed out waiting for a free key")
```

### 9.3 `layout_classical.py`: the no-LLM layout parser (P0)

**Keep:** all of `analyse_text_page` and its helpers: `ANCHOR` and the sequence rule, column detection with thin and low-mass strips merged, `_merge_numberless`, repeated-text and repeated-figure clutter, marks cells, the QID-column erase, continuation at the top of a column or page, solution cut-off.
**Input contract:** `page.text_lines()` = `[(x0, y0, x1, y1, text, size)]` as fractions of the page. **Feed it OCR lines for scans and photos** (T1): `size` = line-box height. This is what removes most API calls.
**Change:** replace `_looks_like_answer_key` (§10 #1). Make `ANCHOR` OCR-tolerant only where the sequence expects it (§5.1). `analyse_image_page` stays as **T3** only and must never report "ok" (§10 #5).
**Test with:** `fixtures/lines/*.json` (§8.3 item 3).

```python
# source: pdf2ppt/qdeck/layout_classical.py
"""No-LLM layout fallback (used when the Gemini quota is gone, or USE_LLM=0).

* Pages with a text layer: question anchors are lines that start with a number
  pattern, sit at the left edge of a column, and continue the numbering sequence
  (integrity filter: this rejects numbers inside stems and "(a) 1 (b) 2" lines).
* Image-only pages: whitespace projection profiles -> columns -> blocks. Without OCR
  the question boundaries are guesses, so these fragments carry low confidence.

Output uses the same structure as the Gemini layout (boxes 0-1000, ymin/xmin/ymax/xmax).
"""
import re
from collections import Counter

import cv2
import numpy as np

ANCHOR = re.compile(r"^\s*(?:Q(?:ue(?:stion)?)?\s*\.?\s*(?:No\.?)?\s*)?(\d{1,3})\s*(?:[.):\]]|\s|$)", re.I)
CLUTTER = re.compile(r"(www\.|https?://|\.com\b|\.in\b|page\s*\d+|p\.?\s*t\.?\s*o\b)", re.I)
MARKS = re.compile(r"^\s*[-+]?\d+\.\d+\s*$")
ANSWER_KEY = re.compile(r"answer\s*key|answers?\s*:|^\s*key\s*$", re.I)
PAGE_NO = re.compile(r"^\s*[-\[(]?\s*\d+\s*[-\])]?\s*$")
INSTRUCTION_WORDS = re.compile(r"\b(tick|marks?|negative|candidates?|answer sheet|booklet|carry|"
                               r"instructions?|duration|allowed)\b", re.I)
SOLUTION = re.compile(r"^\s*(ans(wer)?\s*[.:]|sol(ution)?\s*[.:]|explanation\s*[.:]|hint\s*[.:])", re.I)


def _norm(t):
    return re.sub(r"\s+", " ", re.sub(r"\d+", "#", t.strip().lower()))


class ClassicalLayout:
    def __init__(self):
        self.repeated = Counter()
        self.expected_next = None
        self.had_question = False

    def prime(self, lines_by_page, figs_by_page=()):
        """Text that repeats on many pages (running headers, 'Trade Theory' bands,
        footers) is clutter; so are images repeated at the same place (watermark art)."""
        c = Counter()
        for lines in lines_by_page:
            for t in set(_norm(l[4]) for l in lines):
                c[t] += 1
        n = max(1, len(lines_by_page))
        self.repeated = {t for t, k in c.items()
                         if k >= max(3, 0.3 * n) and len(t) < 60 and len(re.sub(r"[^a-z\u0900-\u097f]", "", t)) >= 4}
        fc = Counter()
        for figs in figs_by_page:
            for f in set(tuple(round(v, 2) for v in f) for f in figs):
                fc[f] += 1
        self.repeated_figs = {f for f, k in fc.items() if k >= max(3, 0.3 * n)}

    # ------------------------------------------------------------------ text layer
    def _columns(self, lines):
        bins = np.zeros(400, dtype=int)
        for x0, y0, x1, y1, t, s in lines:
            if x1 - x0 > 0.6:
                continue
            bins[int(x0 * 399):int(x1 * 399) + 1] += 1
        occ = bins > 0
        segs, start = [], None
        for i, o in enumerate(list(occ) + [False]):
            if o and start is None:
                start = i
            elif not o and start is not None:
                segs.append([start / 399, i / 399])
                start = None
        if not segs:
            return [(0.0, 1.0)]
        # merge gaps < 1.5% and narrow segments (table cells such as Sr.No / Marks)
        merged = [segs[0]]
        for s in segs[1:]:
            if s[0] - merged[-1][1] < 0.015:
                merged[-1][1] = s[1]
            else:
                merged.append(s)
        changed = True
        while changed and len(merged) > 1:
            changed = False
            for i, s in enumerate(merged):
                if s[1] - s[0] < 0.15:
                    j = i - 1 if i > 0 and (i == len(merged) - 1 or
                                            s[0] - merged[i - 1][1] < merged[i + 1][0] - s[1]) else i + 1
                    a, b = sorted([i, j])
                    merged[a] = [min(merged[a][0], merged[b][0]), max(merged[a][1], merged[b][1])]
                    del merged[b]
                    changed = True
                    break
        # a real column carries a fair share of the text; thin numeric strips do not
        def mass(seg):
            return sum(len(l[4]) for l in lines if seg[0] <= (l[0] + l[2]) / 2 <= seg[1])
        total = sum(mass(sg) for sg in merged) or 1
        while len(merged) > 1:
            light = [i for i, sg in enumerate(merged) if mass(sg) < 0.12 * total]
            if not light:
                break
            i = light[0]
            j = i - 1 if i > 0 else i + 1
            a, b = sorted([i, j])
            merged[a] = [min(merged[a][0], merged[b][0]), max(merged[a][1], merged[b][1])]
            del merged[b]
        return [(max(0, a - 0.01), min(1, b + 0.01)) for a, b in merged]

    @staticmethod
    def _merge_numberless(cols, content):
        """A 'column' with no question-number lines at all, on a page where another
        column has them, is a figure / option block beside the text (table layouts),
        not a real column: merge it into its left neighbour."""
        if len(cols) < 2:
            return cols

        def has_numbers(c):
            ls = [l for l in content if c[0] <= (l[0] + l[2]) / 2 <= c[1]]
            if not ls:
                return False
            left = min(l[0] for l in ls)
            return any(ANCHOR.match(l[4]) and l[0] <= left + 0.05 for l in ls)
        flags = [has_numbers(c) for c in cols]
        if not any(flags):
            return cols
        out = [list(cols[0])]
        for c, f in zip(cols[1:], flags[1:]):
            if f:
                out.append(list(c))
            else:
                out[-1][1] = max(out[-1][1], c[1])
        return [tuple(c) for c in out]

    def _is_clutter(self, l):
        x0, y0, x1, y1, t, s = l
        if _norm(t) in self.repeated:
            return True
        m = ANCHOR.match(t)
        if m and self.expected_next is not None and 0 <= int(m.group(1)) - self.expected_next <= 6 and x0 < 0.5:
            return False   # the next question number, not a page number
        if (y0 < 0.05 or y1 > 0.95) and (CLUTTER.search(t) or PAGE_NO.match(t)):
            return True
        if MARKS.match(t) and x0 > 0.6:
            return True    # marks / negative-marks cells
        return bool(CLUTTER.search(t)) and len(t) < 40

    def analyse_text_page(self, page):
        lines = page.text_lines()
        if self._looks_like_answer_key(lines):
            return {"page_kind": "answer_key", "dark_background": False, "fragments": [], "_model": "classical-text"}
        figs = [f for f in page.figure_boxes()
                if tuple(round(v, 2) for v in f) not in getattr(self, "repeated_figs", set())]
        content = [l for l in lines if not self._is_clutter(l)]
        cols = self._columns(content)
        cols = self._merge_numberless(cols, content)
        frags = []
        for ci, (cx0, cx1) in enumerate(cols):
            col_lines = sorted([l for l in content if cx0 <= (l[0] + l[2]) / 2 <= cx1], key=lambda l: l[1])
            col_figs = [f for f in figs if cx0 <= (f[0] + f[2]) / 2 <= cx1]
            if not col_lines:
                continue
            left = min(l[0] for l in col_lines)
            anchors = []
            qid_cols = []
            for l in col_lines:
                m = ANCHOR.match(l[4])
                if not m or l[0] > left + 0.05 or INSTRUCTION_WORDS.search(l[4]):
                    continue
                n = int(m.group(1))
                if anchors and abs(anchors[-1][0][1] - l[1]) < 0.004:
                    qid_cols.append((l[0], l[2]))   # second number cell on the row: QID column
                    continue
                exp = self.expected_next
                if exp is None or n == exp or (0 < n - exp <= 3) or n == 1:
                    anchors.append((l, n))
                    self.expected_next = n + 1
            # regions between anchors
            starts = [a[0][1] for a in anchors]
            bounds = starts + [1.01]
            top_lines = [l for l in col_lines if not starts or l[3] <= starts[0] + 0.002]
            if top_lines and (frags or self.had_question):
                y0 = min(l[1] for l in top_lines)
                y1 = max(l[3] for l in top_lines)
                lone_band = (len(top_lines) == 1 and abs((top_lines[0][0] + top_lines[0][2]) / 2 - 0.5) < 0.1
                             and top_lines[0][2] - top_lines[0][0] < 0.35)   # centred section band
                if not lone_band and not any(SOLUTION.match(l[4]) for l in top_lines[:1]):
                    frags.append(self._frag("continuation", None, cx0, y0, cx1, y1, top_lines, col_figs, cont=True))
            for i, (l, n) in enumerate(anchors):
                ys, ye = bounds[i], bounds[i + 1]
                body = [b for b in col_lines if ys - 0.002 <= b[1] < ye - 0.002]
                # stop at an interleaved solution block
                for k, b in enumerate(body):
                    if k and SOLUTION.match(b[4]):
                        body = body[:k]
                        break
                y1 = max(b[3] for b in body)
                for f in col_figs:
                    if ys <= f[1] < ye:
                        y1 = max(y1, f[3])
                fr = self._frag("question", str(n), cx0, l[1], cx1, y1, body, col_figs, number=n)
                if qid_cols:   # hide the duplicate question-ID column, keep the serial number
                    qx0, qx1 = min(q[0] for q in qid_cols), max(q[1] for q in qid_cols)
                    fr["erase"] = [[qx0 - 0.004, l[1] - 0.01, qx1 + 0.004, y1 + 0.01]]
                frags.append(fr)
        self.had_question = self.had_question or any(f["role"] == "question" for f in frags)
        if frags and frags[-1]["role"] == "question":
            frags[-1]["continues_on_next"] = True  # unknown: the next page decides
        for l in lines:
            if l not in content:   # bands, headers, footers: boundaries for crop padding
                frags.append({"role": "clutter", "label": None, "number": None, "continues_previous": False,
                              "continues_on_next": False,
                              "box": [int(l[1] * 1000), int(l[0] * 1000), int(np.ceil(l[3] * 1000)), int(np.ceil(l[2] * 1000))],
                              "lang": "en", "has_figure": False, "has_math": False, "has_table": False,
                              "stem": "", "options": [], "confidence": 1.0})
        for i, f in enumerate(frags):
            f["order"] = i
        return {"page_kind": "questions" if frags else "blank_or_ad", "dark_background": False,
                "watermark_present": bool(getattr(self, "repeated_figs", None)) or any(
                    "watermark" in t or "sample" in t for t in self.repeated),
                "fragments": frags, "_model": "classical-text"}

    @staticmethod
    def _looks_like_answer_key(lines):
        if not lines:
            return False
        head = any(ANSWER_KEY.search(l[4]) for l in lines)
        short = sum(1 for l in lines if len(l[4].strip()) <= 4)
        return head and short > 0.6 * len(lines)

    def _frag(self, role, label, x0, y0, x1, y1, lines, figs, number=None, cont=False):
        text = " ".join(l[4] for l in lines)
        # horizontal extent from the real content (marks cells are clutter, not content)
        ext = [l for l in lines] + [f for f in figs if y0 - 0.002 <= f[1] <= y1]
        if ext:
            x0 = max(x0, min(e[0] for e in ext) - 0.006)
            x1 = min(x1, max(e[2] for e in ext) + 0.008)
        hi = bool(re.search(r"[ऀ-ॿ]", text))
        en = bool(re.search(r"[A-Za-z]{3}", text))
        return {"role": role, "label": label, "number": number, "continues_previous": cont,
                "continues_on_next": False,
                "box": [int(max(0, y0 - 0.004) * 1000), int(x0 * 1000), int(min(1, y1 + 0.004) * 1000), int(x1 * 1000)],
                "lang": "bilingual" if hi and en else ("hi" if hi else "en"),
                "has_figure": any(f for f in figs if y0 <= f[1] <= y1), "has_math": False, "has_table": False,
                "stem": text[:400], "options": [], "confidence": 0.6}

    # ------------------------------------------------------------------ image only
    def analyse_image_page(self, img):
        g = np.asarray(img.convert("L"))
        H, W = g.shape
        bw = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 15)
        bw[: int(0.05 * H)] = 0
        bw[int(0.95 * H):] = 0
        colprof = bw.sum(axis=0) / 255
        occupied = colprof > 0.01 * H
        cols, start = [], None
        gap_min = int(0.015 * W)
        run = 0
        for x, o in enumerate(list(occupied) + [False] * gap_min):
            if o:
                if start is None:
                    start = x
                run = 0
            elif start is not None:
                run += 1
                if run >= gap_min:
                    if x - run - start > 0.12 * W:
                        cols.append((start, x - run))
                    start, run = None, 0
        frags = []
        for cx0, cx1 in cols or [(0, W)]:
            rows = bw[:, cx0:cx1].sum(axis=1) > 2 * 255
            lines, s = [], None
            for y, o in enumerate(list(rows) + [False]):
                if o and s is None:
                    s = y
                elif not o and s is not None:
                    if y - s > 3:
                        lines.append((s, y))
                    s = None
            if not lines:
                continue
            gaps = [lines[i + 1][0] - lines[i][1] for i in range(len(lines) - 1)]
            thr = (np.median(gaps) * 2.2 + 2) if gaps else 1e9
            block = [lines[0]]
            for i, ln in enumerate(lines[1:]):
                if gaps[i] > thr:
                    frags.append(self._img_frag(block, cx0, cx1, W, H))
                    block = []
                block.append(ln)
            frags.append(self._img_frag(block, cx0, cx1, W, H))
        for i, f in enumerate(frags):
            f["order"] = i
        return {"page_kind": "questions" if frags else "blank_or_ad", "dark_background": float(g.mean()) < 100,
                "fragments": frags, "_model": "classical-image"}

    @staticmethod
    def _img_frag(block, cx0, cx1, W, H):
        y0, y1 = block[0][0], block[-1][1]
        return {"role": "question", "label": None, "number": None, "continues_previous": False,
                "continues_on_next": False,
                "box": [int(y0 / H * 1000), int(cx0 / W * 1000), int(np.ceil(y1 / H * 1000)), int(np.ceil(cx1 / W * 1000))],
                "lang": "other", "has_figure": False, "has_math": False, "has_table": False,
                "stem": "", "options": [], "confidence": 0.25}
```

### 9.4 `assemble.py`: fragments → questions, reading order, integrity (P0)

**Keep 1:1:** continuation stitching (same-language open question first), shared passages, bilingual twins by script ratio, duplicate detection with `difflib` (use a Kotlin similarity of your choice at the same 0.75 threshold on the first 400 characters), section restarts, and `reading_order` (recursive XY-cut). `reading_order` is the piece that makes lite-model output usable.
**Change:** `integrity_check` per §5.1 and §10 #3. Everything here is pure logic: port it as plain Kotlin and unit-test it before anything else touches it.

```python
# source: pdf2ppt/qdeck/assemble.py
"""Fragments -> questions: continuation stitching (column and page breaks), shared
passages, bilingual twins, duplicate pages, section restarts and the integrity check."""
import difflib
import re
from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class Fragment:
    page_seq: int
    page_ref: str
    order: int
    role: str
    label: Optional[str]
    number: Optional[int]
    lang: str
    box: tuple                 # fractions x0,y0,x1,y1 on the page
    crop_path: str
    text: str
    options: list
    confidence: float
    has_figure: bool = False
    has_math: bool = False
    has_table: bool = False
    continues_previous: bool = False
    continues_on_next: bool = False
    dark: bool = False
    watermark: bool = False
    source_kind: str = ""
    model: str = ""


@dataclass
class Question:
    root: Fragment
    parts: List[Fragment] = field(default_factory=list)   # continuations, in order
    passage: Optional[Fragment] = None                     # stitched above (first in group)
    passage_slide_of: Optional[int] = None                 # later questions refer to it
    twins: List[Fragment] = field(default_factory=list)    # other-language versions kept
    flags: List[str] = field(default_factory=list)
    section: int = 0
    slide_no: int = 0

    @property
    def number(self):
        return self.root.number

    @property
    def label(self):
        return self.root.label or (str(self.root.number) if self.root.number is not None else "?")

    @property
    def lang(self):
        return self.root.lang

    @property
    def fragments(self):
        return ([self.passage] if self.passage else []) + [self.root] + self.parts + self.twins

    @property
    def confidence(self):
        return min(f.confidence for f in [self.root] + self.parts)

    @property
    def text(self):
        return " ".join(f.text for f in [self.root] + self.parts if f.text).strip()

    @property
    def options(self):
        out = []
        for f in [self.root] + self.parts:
            out += f.options or []
        return out

    def has(self, attr):
        return any(getattr(f, attr) for f in [self.root] + self.parts)


def _dev_ratio(text):
    dev = sum(1 for c in text or "" if "\u0900" <= c <= "\u097f")
    lat = sum(1 for c in text or "" if c.isascii() and c.isalpha())
    return dev / (dev + lat) if dev + lat else 0.0


def _norm(t):
    return re.sub(r"[^\wऀ-ॿ]+", " ", (t or "").lower()).strip()


def _similar(a, b):
    a, b = _norm(a), _norm(b)
    if not a or not b:
        return False
    return difflib.SequenceMatcher(None, a[:400], b[:400]).ratio() > 0.75


def assemble(frags: List[Fragment], lang_pref="en", log=print):
    frags = sorted(frags, key=lambda f: (f.page_seq, f.order))
    questions: List[Question] = []
    dropped = {"solutions": 0, "clutter": 0, "duplicates": 0, "twins": 0, "orphans": 0}
    pending_passage = None
    passage_group_open = False

    for f in frags:
        if f.role in ("solution", "answer_key"):
            dropped["solutions"] += 1
            continue
        if f.role in ("clutter",):
            dropped["clutter"] += 1
            continue
        if f.role in ("instructions", "section_header"):
            passage_group_open = False
            pending_passage = None
            continue
        if f.role == "shared_passage":
            pending_passage = f
            passage_group_open = True
            continue
        # an unnumbered "question" right after a question is its option block / table tail
        is_cont = f.role == "continuation" or (f.role == "question" and f.number is None and bool(questions)
                                                and not f.model.startswith("classical"))
        if is_cont:
            target = None
            # prefer an open question of the same language (bilingual spreads interleave)
            for q in reversed(questions[-8:]):
                last = q.parts[-1] if q.parts else q.root
                if last.continues_on_next and q.lang == f.lang:
                    target = q
                    break
            if target is None:
                for q in reversed(questions[-8:]):
                    if q.lang == f.lang or f.lang in ("other", "bilingual") or q.lang == "bilingual":
                        target = q
                        break
            if target is None and questions:
                target = questions[-1]
            if target is None:
                dropped["orphans"] += 1
                continue
            target.parts.append(f)
            if "split_stitched" not in target.flags:
                target.flags.append("split_stitched")
            if f.page_seq != target.root.page_seq:
                if "page_break_stitched" not in target.flags:
                    target.flags.append("page_break_stitched")
            continue
        if f.role != "question":
            continue
        q = Question(root=f)
        if pending_passage is not None:
            q.passage = pending_passage
            q.flags.append("shared_passage")
            pending_passage = None
        elif passage_group_open:
            # later member of a passage group: refer to the slide that shows the passage
            for prev in reversed(questions):
                if prev.passage is not None:
                    q.passage_slide_of = id(prev)
                    q.flags.append("refers_to_passage")
                    break
        questions.append(q)

    # ---- bilingual twins: same number printed once in Hindi and once in English ----
    by_num = {}
    for q in questions:
        if q.number is not None:
            by_num.setdefault(q.number, []).append(q)
    keep = []
    removed = set()
    if lang_pref in ("en", "hi", "both"):
        for n, qs in by_num.items():
            if len(qs) < 2:
                continue
            ratios = {id(q): _dev_ratio(q.text) for q in qs}
            lo = min(qs, key=lambda q: ratios[id(q)])
            hi = max(qs, key=lambda q: ratios[id(q)])
            # same number printed in two scripts (Hindi page + English page)
            if ratios[id(hi)] - ratios[id(lo)] < 0.3:
                continue
            main = hi if lang_pref == "hi" else lo
            for q in qs:
                if q is main or abs(ratios[id(q)] - ratios[id(main)]) < 0.3:
                    continue
                removed.add(id(q))
                dropped["twins"] += 1
                if lang_pref == "both":
                    main.twins.append(q.root)
                    main.twins += q.parts
            if "bilingual_twin" not in main.flags:
                main.flags.append("bilingual_twin")
    questions = [q for q in questions if id(q) not in removed]

    # ---- duplicate pages / repeated photos: same number + same text --------------------
    for q in questions:
        dup = False
        for k in keep[-60:]:
            if k.number == q.number and k.number is not None and k.lang == q.lang and _similar(k.text, q.text):
                dup = True
                break
        if dup:
            dropped["duplicates"] += 1
            continue
        keep.append(q)
    questions = keep

    # ---- sections (numbering restarts) + integrity -------------------------------------
    section, prev = 0, None
    for q in questions:
        n = q.number
        if n is not None and prev is not None and n < prev and (n <= 2 or prev - n > 20):
            section += 1
        q.section = section
        if n is not None:
            prev = n
    for i, q in enumerate(questions, 1):
        q.slide_no = i
        if q.number is None:
            q.flags.append("no_number")
        if q.confidence < 0.6:
            q.flags.append("low_confidence")
    ids = {id(q): q for q in questions}
    for q in questions:
        if q.passage_slide_of in ids:
            q.passage_slide_of = ids[q.passage_slide_of].slide_no
        elif q.passage_slide_of is not None:
            q.passage_slide_of = None

    integrity = integrity_check(questions)
    return questions, dropped, integrity


def integrity_check(questions):
    secs = {}
    for q in questions:
        secs.setdefault(q.section, []).append(q)
    out = {"sections": [], "detected": len(questions), "expected": 0,
           "gaps": [], "duplicates": [], "out_of_order": []}
    for s, qs in sorted(secs.items()):
        nums = [q.number for q in qs if q.number is not None]
        if not nums:
            out["expected"] += len(qs)
            out["sections"].append({"section": s + 1, "first": None, "last": None, "count": len(qs)})
            continue
        lo, hi = min(nums), max(nums)
        expected = hi - lo + 1 + sum(1 for q in qs if q.number is None)
        out["expected"] += expected
        seen = set()
        for n in nums:
            if n in seen:
                out["duplicates"].append(f"S{s + 1}:{n}")
            seen.add(n)
        out["gaps"] += [f"S{s + 1}:{n}" for n in range(lo, hi + 1) if n not in seen]
        for a, b in zip(nums, nums[1:]):
            if b != a + 1 and b not in seen - {b}:
                if b < a:
                    out["out_of_order"].append(f"S{s + 1}:{a}->{b}")
        out["sections"].append({"section": s + 1, "first": lo, "last": hi, "count": len(qs)})
    out["ok"] = not out["gaps"] and not out["duplicates"] and out["detected"] == out["expected"]
    return out


def reading_order(items, key=lambda it: it["box"]):
    """Recursive XY-cut on fragment boxes (x0,y0,x1,y1 fractions). A column gutter /
    spread fold is cut first. If wide blocks (full-width header, wide table, shared
    passage) bridge the gutters, the group is split into horizontal bands at those wide
    blocks and each band is cut into columns again. Returns items in reading order,
    independent of the order the LLM happened to emit."""
    if len(items) <= 1:
        return list(items)

    def gutters(group):
        spans = sorted((key(it)[0], key(it)[2]) for it in group)
        out, reach = [], spans[0][1]
        for a, b in spans[1:]:
            if a > reach - 0.012:          # tolerate columns whose boxes touch/overlap a hair
                out.append((reach + a) / 2)
            reach = max(reach, b)
        return out

    def split(group):
        for g in gutters(group):
            left = [it for it in group if (key(it)[0] + key(it)[2]) / 2 < g]
            right = [it for it in group if (key(it)[0] + key(it)[2]) / 2 >= g]
            if left and right:
                return left, right
        return None

    sp = split(items)
    if sp:
        return reading_order(sp[0], key) + reading_order(sp[1], key)

    gx0 = min(key(it)[0] for it in items)
    gx1 = max(key(it)[2] for it in items)
    wide = [it for it in items if key(it)[2] - key(it)[0] > 0.5 * (gx1 - gx0)]
    narrow = [it for it in items if it not in wide]
    if wide and narrow and split(narrow):
        out, rest = [], sorted(narrow, key=lambda it: key(it)[1])
        for w in sorted(wide, key=lambda it: key(it)[1]):
            above = [it for it in rest if (key(it)[1] + key(it)[3]) / 2 < (key(w)[1] + key(w)[3]) / 2]
            rest = [it for it in rest if it not in above]
            out += reading_order(above, key) + [w]
        return out + reading_order(rest, key)

    return sorted(items, key=lambda it: (key(it)[1], key(it)[0]))
```

### 9.5 `imaging.py`: crop, clean, trim, encode (P0 core, P1 the rest)

**Port with OpenCV Android** (`org.opencv:opencv`, restrict `abiFilters` to `arm64-v8a` to keep the APK small). `Mat` operations map almost 1:1 (`adaptiveThreshold`, `morphologyEx` with line kernels, `dilate`, `GaussianBlur`, `resize`, `connectedComponentsWithStats`).
**Priority:** P0 = `padded_box`, `snap`, `tighten`, `content_mask`, `ink_mask`, `to_light` (digital fast path), `encode`, `text_height_pt`. P1 = `strip_rules`, `compress_gaps`, `stack`, `enhance` (photos), `erase`. P2 = dark-frame inversion.
**Do not change** the left-edge rule in `tighten`: the printed question number must stay visible.

```python
# source: pdf2ppt/qdeck/imaging.py
"""CPU-side image work: padded crop + ink snap, light-theme normalisation, watermark
keep/remove, dark-frame inversion, legibility estimate and compact encoding."""
import io

import cv2
import numpy as np
from PIL import Image

PAD_Y, PAD_X = 0.018, 0.012


def padded_box(box, neighbours):
    """Grow `box` (fractions x0,y0,x1,y1) by a margin, but never past the midpoint of
    the gap to a neighbouring fragment that shares the same rows/columns."""
    x0, y0, x1, y1 = box
    lo_y, hi_y, lo_x, hi_x = y0 - PAD_Y, y1 + PAD_Y, x0 - PAD_X, x1 + PAD_X
    for nx0, ny0, nx1, ny1 in neighbours:
        h_overlap = min(x1, nx1) - max(x0, nx0) > 0.2 * min(x1 - x0, nx1 - nx0)
        v_overlap = min(y1, ny1) - max(y0, ny0) > 0.2 * min(y1 - y0, ny1 - ny0)
        if h_overlap:
            if ny1 <= y0 + 0.004:
                lo_y = max(lo_y, (ny1 + y0) / 2)
            if ny0 >= y1 - 0.004:
                hi_y = min(hi_y, (y1 + ny0) / 2)
        if v_overlap:
            if nx1 <= x0 + 0.004:
                lo_x = max(lo_x, (nx1 + x0) / 2)
            if nx0 >= x1 - 0.004:
                hi_x = min(hi_x, (x1 + nx0) / 2)
    return (max(0.0, min(lo_x, x0)), max(0.0, min(lo_y, y0)),
            min(1.0, max(hi_x, x1)), min(1.0, max(hi_y, y1)))


def ink_mask(img):
    g = np.asarray(img.convert("L"))
    bw = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 35, 18)
    return bw > 0


def snap(img, core):
    """`core` = pixel rect (x0,y0,x1,y1) of the LLM box inside the padded crop. Keep the
    core, extend into the padding only through rows/columns that touch the core with ink
    (completes clipped glyphs), stop at the first blank line, then trim blank margins."""
    m = content_mask(img)
    H, W = m.shape
    cx0, cy0, cx1, cy1 = [int(v) for v in core]
    rows = m[:, max(0, cx0):max(cx0 + 1, cx1)].sum(axis=1) > 1
    cols = m[max(0, cy0):max(cy0 + 1, cy1), :].sum(axis=0) > 1
    cy0, cy1 = max(0, min(H - 1, cy0)), max(1, min(H, cy1))
    # a text line cut by the box edge: complete it if most of it is inside the box,
    # otherwise it belongs to the neighbour -> trim back to the blank row
    top = cy0
    if rows[cy0]:
        a = cy0
        while a > 0 and rows[a - 1]:
            a -= 1
        b = cy0
        while b < H and rows[b]:
            b += 1
        top = a if (b - cy0) >= (cy0 - a) else min(b, cy1 - 1)
    bot = cy1
    if rows[cy1 - 1]:
        a = cy1 - 1
        while a > 0 and rows[a - 1]:
            a -= 1
        b = cy1
        while b < H and rows[b]:
            b += 1
        bot = b if (cy1 - a) >= (b - cy1) else max(a, top + 1)
    left = cx0
    while left > 0 and cols[left - 1]:
        left -= 1
    right = cx1
    while right < W and cols[right]:
        right += 1
    sub = m[top:bot, left:right]
    if sub.any():
        ys, xs = np.where(sub)
        margin = 6
        t = max(0, top + ys.min() - margin)
        b = min(H, top + ys.max() + 1 + margin)
        l = max(0, left + xs.min() - margin)
        r = min(W, left + xs.max() + 1 + margin)
        return img.crop((l, t, r, b)), (l, t, r, b)
    return img.crop((left, top, right, bot)), (left, top, right, bot)


def is_dark(img):
    g = np.asarray(img.convert("L"))
    return float(np.median(g)) < 110


def to_light(img, watermark="keep", photo=True, screenshot=False):
    """Return a light-theme version: dark frames are inverted, tinted paper becomes
    white. Watermarks are kept at their original faint tone unless watermark=remove."""
    if not photo and not screenshot:
        # born-digital render: already white paper, black text. Cheap uint8 path; keep
        # colour only if the crop really has colour (coloured figures).
        small = img.convert("RGB").resize((max(1, img.width // 8), max(1, img.height // 8)))
        sa = np.asarray(small).astype(np.int16)
        colour = float((sa.max(axis=2) - sa.min(axis=2)).mean()) > 4
        out = img.convert("RGB") if colour else img.convert("L")
        if watermark == "remove":
            if out.mode == "L":
                out = out.point(lambda v: 255 if v > 238 else v)
            else:
                g = np.asarray(out.convert("L"))
                a = np.asarray(out).copy()
                a[g > 238] = 255
                out = Image.fromarray(a)
        if float(np.median(np.asarray(small.convert("L")))) >= 110:
            return out
        img = out          # dark digital page (rare): fall through to inversion
    a = np.asarray(img.convert("RGB")).astype(np.float32)
    if float(np.median(a.mean(axis=2))) < 110:      # light text on dark background
        a = 255.0 - a
        gray = a.mean(axis=2, keepdims=True)
        a = np.repeat(gray, 3, axis=2)              # inverted colours look odd; use grey
        photo = True
    if screenshot and watermark == "keep" and float(np.percentile(a.mean(axis=2), 60)) > 200:
        # already-light page (screenshot / clean scan): one global white point, so a
        # large pale watermark is not mistaken for paper and flattened away
        white = np.percentile(a.mean(axis=2), 90)
        a = np.clip(a * (255.0 / max(white, 1)), 0, 255)
        gray = a.min(axis=2) * 0.6 + a.mean(axis=2) * 0.4
        a = np.repeat(gray[..., None], 3, axis=2)
        photo = False
    elif photo:
        # background (paper) estimate = large max filter on a downscaled copy
        h, w = a.shape[:2]
        f = max(1, int(max(h, w) / 300))
        small = cv2.resize(a, (max(1, w // f), max(1, h // f)), interpolation=cv2.INTER_AREA)
        bg = cv2.dilate(small, np.ones((9, 9), np.uint8))
        bg = cv2.GaussianBlur(bg, (0, 0), 3)
        bg = cv2.resize(bg, (w, h), interpolation=cv2.INTER_LINEAR)
        a = np.clip(a / np.maximum(bg, 1) * 255.0, 0, 255)
        # levels: paper -> pure white, ink slightly darker
        a = np.clip((a - 25) * 255.0 / (238 - 25), 0, 255)
        # photos/scans: grey ink, not the camera's blue/yellow cast
        gray = a.min(axis=2) * 0.6 + a.mean(axis=2) * 0.4
        a = np.repeat(gray[..., None], 3, axis=2)
    if watermark == "remove":
        lum = a.mean(axis=2)
        sat = a.max(axis=2) - a.min(axis=2)
        # pale or tinted-but-light pixels are watermark/background, real ink is dark.
        # Digital pages have pure black text, so a lower cut also removes grey
        # watermark lettering and its anti-aliased edges.
        # photos/scans: no hard threshold here (it eats faint strokes of photographed
        # text); enhance() removes smooth pale watermarks via local contrast instead.
        if not photo:
            a[lum > 238] = 255
    return Image.fromarray(a.astype(np.uint8))


def text_height_pt(img, px_per_in):
    """Median glyph height in source points (used to estimate displayed font size)."""
    m = ink_mask(img).astype(np.uint8)
    n, _, stats, _ = cv2.connectedComponentsWithStats(m, 8)
    hs = [h for x, y, w, h, area in stats[1:] if 3 <= h <= 0.2 * m.shape[0] and w <= 3 * h and area > 6]
    if len(hs) < 8:
        return None
    return float(np.median(hs)) / px_per_in * 72.0


def stack(images, gap=14, offsets=None):
    """Stack fragments vertically. `offsets` (px) keep parts of a page-split question
    aligned as they were on the page; column-split parts are left-aligned."""
    offs = list(offsets or [0] * len(images))
    base = min(offs)
    offs = [o - base for o in offs]
    W = max(i.width + o for i, o in zip(images, offs))
    H = sum(i.height for i in images) + gap * (len(images) - 1)
    out = Image.new("RGB", (W, H), "white")
    y = 0
    for im, o in zip(images, offs):
        out.paste(im.convert("RGB"), (o, y))
        y += im.height + gap
    return out


def encode(img, max_w_px, max_h_px):
    """Resize to what the slide shows (at the target dpi) and store losslessly when the
    picture is mostly text/line art (PNG, full 8-bit grey: sharp glyph edges); photos of
    figures with lots of colour fall back to high-quality JPEG only when PNG is huge."""
    im = img.convert("RGB")
    s = min(1.0, max_w_px / im.width, max_h_px / im.height)
    if s < 1.0:
        im = im.resize((max(1, int(im.width * s)), max(1, int(im.height * s))), Image.LANCZOS)
    a = np.asarray(im).astype(np.int16)
    colourful = float(np.abs(a[..., 0] - a[..., 1]).mean() + np.abs(a[..., 1] - a[..., 2]).mean())
    if colourful < 6:
        im = im.convert("L")
    png = io.BytesIO()
    im.save(png, "PNG", compress_level=6)
    if im.mode == "L":
        return png.getvalue(), "png", im.size          # text / line art: always lossless
    jpg = io.BytesIO()
    im.save(jpg, "JPEG", quality=92, subsampling=0)
    if png.tell() <= jpg.tell() * 2.0:
        return png.getvalue(), "png", im.size
    return jpg.getvalue(), "jpeg", im.size


def enhance(img, src_ppi, target_ppi=300, crisp_ink=True):
    """Low-resolution photos/screenshots: upscale to the target ppi (Lanczos), unsharp
    mask scaled to the upscale factor, and a gentle tone curve that makes ink solid
    black while paper stays white. Gives crisp characters and clean diagram lines
    on a projector without any ML model."""
    from PIL import ImageFilter
    f = target_ppi / max(src_ppi, 1.0)
    im = img
    if f > 1.15:
        f = min(f, 4.0)
        im = im.resize((int(im.width * f), int(im.height * f)), Image.LANCZOS)
    else:
        f = 1.0
    im = im.filter(ImageFilter.UnsharpMask(radius=max(1.0, 0.9 * f), percent=140, threshold=2))
    if not crisp_ink:
        a = np.asarray(im.convert("RGB")).astype(np.float32) / 255.0
        a = np.clip((a - 0.06) / 0.86, 0, 1) ** 1.25
        return Image.fromarray((a * 255).astype(np.uint8)), (src_ppi * f)
    g = np.asarray(im.convert("L")).astype(np.float32)
    # ink crisping: compare each pixel with its neighbourhood and push it through a
    # smooth sigmoid -> solid black strokes with soft (anti-aliased) edges, white paper
    local = cv2.GaussianBlur(g, (0, 0), sigmaX=max(4.0, 3.0 * f))
    ink = 1.0 / (1.0 + np.exp(-(local - g - 14.0) * 0.3))   # paper -> ~0, strokes -> ~1
    crisp = 255.0 * (1.0 - ink)
    dark = g < 110                                        # solid areas (filled shapes, bold)
    crisp[dark] = np.minimum(crisp[dark], g[dark])
    base = np.clip((g - 15) * 255.0 / 215.0, 0, 255)
    out = np.minimum(0.85 * crisp + 0.15 * base, np.maximum(crisp, base))
    out[out > 232] = 255
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)), (src_ppi * f)


def tighten(img):
    """Shrink a crop to its real content: ignore long table rules, and drop a narrow
    strip on the right (marks / negative-marks cells) separated by a wide blank gap.
    The left edge is never cut, so the printed question number stays visible."""
    H0, W0 = img.height, img.width
    if H0 < 20 or W0 < 20:
        return img, (0, 0, W0, H0)
    full = img
    img, f = _small(img)
    H, W = img.height, img.width
    c = content_mask(img)
    if c.sum() < 50:
        return full, (0, 0, W0, H0)
    cols = c.sum(axis=0) > 0
    xs = np.where(cols)[0]
    right = xs.max() + 1
    # look for a wide blank gap followed by a narrow, sparse strip
    gap_min = max(12, int(0.06 * W))
    run, x = 0, right - 1
    while x > 0:
        if cols[x]:
            if run >= gap_min and (right - (x + run + 1)) < 0.15 * W and c[:, x + run:right].sum() < 0.03 * c.sum():
                right = x + 1
                break
            run = 0
        else:
            run += 1
        x -= 1
    xs = np.where(c[:, :right].sum(axis=0) > 0)[0]
    ys = np.where(c[:, :right].sum(axis=1) > 0)[0]
    if not len(xs) or not len(ys):
        return full, (0, 0, W0, H0)
    mg = 8
    rect = (max(0, xs.min() - mg), max(0, ys.min() - mg), min(W, xs.max() + 1 + mg), min(H, ys.max() + 1 + mg))
    rect = tuple(int(round(v * f)) for v in rect)
    rect = (rect[0], rect[1], min(W0, rect[2]), min(H0, rect[3]))
    return full.crop(rect), rect


def _small(img, max_side=2400):
    """Work copy for mask analysis (big 600-dpi renders are analysed at <= max_side)."""
    f = max(1.0, max(img.size) / max_side)
    if f == 1.0:
        return img, 1.0
    return img.resize((int(img.width / f), int(img.height / f)), Image.BILINEAR), f


def content_mask(img):
    """Ink without long table rules (they would hide blank bands / margins)."""
    m = ink_mask(img).astype(np.uint8)
    H, W = m.shape
    horiz = cv2.morphologyEx(m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (max(10, W // 3), 1)))
    vert = cv2.morphologyEx(m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(10, H // 4))))
    return (m & ~(horiz | vert)).astype(bool)


def compress_gaps(img, ppi, max_gap_in=0.12):
    """Collapse tall blank horizontal bands (common between stacked options) so
    the question can be shown larger. Not used on figures."""
    sm, f = _small(img)
    ms = content_mask(sm).sum(axis=1) == 0
    idx = np.minimum((np.arange(img.height) / f).astype(int), len(ms) - 1)
    blank = ms[idx]
    max_gap = max(4, int(max_gap_in * ppi))
    keep, run = [], 0
    for y, b in enumerate(blank):
        run = run + 1 if b else 0
        if run <= max_gap:
            keep.append(y)
    if len(keep) >= len(blank) - 2:
        return img
    a = np.asarray(img)
    return Image.fromarray(a[keep])


def strip_rules(img):
    """Erase table rules: vertical lines taller than ~55% of the crop and horizontal
    lines wider than ~75% (cell borders around a question). Short lines such as
    underlines, fraction bars and figure frames are kept."""
    full = img
    img, f = _small(img)
    g = np.asarray(img.convert("L"))
    H, W = g.shape
    if H < 30 or W < 30:
        return full
    # plain darkness test: faint 1-px grey table rules are missed by adaptive thresholds
    m = (g < 225).astype(np.uint8)
    vert = cv2.morphologyEx(m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, int(H * 0.55))))
    horiz = cv2.morphologyEx(m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (int(W * 0.75), 1)))
    # short border stubs left at the very top/bottom edge of the crop (cut cell borders)
    edge = np.zeros_like(m)
    band = max(2, int(H * 0.03))
    stub = cv2.getStructuringElement(cv2.MORPH_RECT, (max(8, int(W * 0.03)), 1))
    edge[:band] = cv2.morphologyEx(m[:band], cv2.MORPH_OPEN, stub)
    edge[-band:] = cv2.morphologyEx(m[-band:], cv2.MORPH_OPEN, stub)
    rules = cv2.dilate(vert | horiz | edge, np.ones((3, 3), np.uint8))
    if not rules.any():
        return full
    if f != 1.0:
        rules = cv2.resize(rules, (full.width, full.height), interpolation=cv2.INTER_NEAREST)
        rules = cv2.dilate(rules, np.ones((int(f) + 1, int(f) + 1), np.uint8))
    a = np.asarray(full).copy()
    a[rules > 0] = 255
    return Image.fromarray(a)


def erase(img, rects):
    """White out pixel rectangles (e.g. a duplicate question-ID column)."""
    if not rects:
        return img
    a = np.asarray(img.convert("RGB")).copy()
    for l, t, r, b in rects:
        a[max(0, int(t)):max(0, int(b)), max(0, int(l)):max(0, int(r))] = 255
    return Image.fromarray(a)
```

### 9.6 `refine.py`: snap LLM boxes to hanging-indent block starts (P1)

Used after T2 (Gemini) boxes only, never after text-layer or OCR boxes. Bounded moves (`MAX_MOVE`). Keep a switch to turn it off (§10 #8).

```python
# source: pdf2ppt/qdeck/refine.py
"""CPU refinement of LLM boxes (fixes boxes that are a few lines short or shifted).

Exam layouts use a hanging indent: the line carrying the question number starts at
the column's left margin, wrapped stem lines and options are indented. Inside each
column we find text lines with a projection profile, mark the flush-left ones as
"block starts", then snap every numbered fragment's top to the nearest block start
and its bottom to just above the next fragment's start. Moves are bounded, so a wrong
snap can never travel far.
"""
import numpy as np
import cv2

MAX_MOVE = 0.06       # fraction of page height a boundary may move
BOUNDARY_ROLES = ("question", "continuation", "shared_passage", "solution", "answer_key",
                  "instructions", "section_header")


def _lines(bw):
    rows = bw.sum(axis=1) > 0
    out, s = [], None
    for y, r in enumerate(list(rows) + [False]):
        if r and s is None:
            s = y
        elif not r and s is not None:
            if y - s >= 3:
                out.append([s, y])
            s = None
    merged = []
    for a, b in out:                       # join accents / matras split from their line
        if merged and a - merged[-1][1] <= 2:
            merged[-1][1] = b
        else:
            merged.append([a, b])
    return merged


def refine(page_img, frags, boxes):
    """page_img: PIL image of the page (layout resolution). frags/boxes aligned lists,
    boxes are (x0,y0,x1,y1) fractions or None. Returns new boxes."""
    g = np.asarray(page_img.convert("L"))
    H, W = g.shape
    bw = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 18)
    bw = cv2.morphologyEx(bw, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))   # drop speckle
    new = list(boxes)
    # running footers / logos the LLM marked: the last block of a column stops there
    stops = [b for f, b in zip(frags, boxes) if b is not None and f.get("role") in ("clutter", "section_header")]
    idx = [i for i, (f, b) in enumerate(zip(frags, boxes)) if b is not None and f.get("role") in BOUNDARY_ROLES
           and (b[2] - b[0]) < 0.6]
    # group into columns by horizontal overlap
    cols = []
    for i in sorted(idx, key=lambda i: boxes[i][0]):
        x0, _, x1, _ = boxes[i]
        for c in cols:
            if min(x1, c["x1"]) - max(x0, c["x0"]) > 0.5 * min(x1 - x0, c["x1"] - c["x0"]):
                c["items"].append(i)
                c["x0"], c["x1"] = min(c["x0"], x0), max(c["x1"], x1)
                break
        else:
            cols.append({"x0": x0, "x1": x1, "items": [i]})
    for c in cols:
        items = sorted(c["items"], key=lambda i: boxes[i][1])
        X0, X1 = int(c["x0"] * W), int(np.ceil(c["x1"] * W))
        Y0 = int(max(0, boxes[items[0]][1] - MAX_MOVE) * H)
        Y1 = int(min(1, boxes[items[-1]][3] + MAX_MOVE) * H)
        strip = bw[Y0:Y1, X0:X1]
        lines = _lines(strip)
        if len(lines) < 3:
            continue
        lefts = []
        for a, b in lines:
            colsum = strip[a:b].sum(axis=0) > 0
            xs = np.where(colsum)[0]
            lefts.append(xs.min() if len(xs) else 10 ** 6)
        margin = np.percentile(lefts, 10)
        tol = 0.012 * W
        starts = [(a + Y0, b + Y0) for (a, b), l in zip(lines, lefts) if l <= margin + tol]
        all_lines = [(a + Y0, b + Y0) for a, b in lines]
        gaps = [all_lines[k + 1][0] - all_lines[k][1] for k in range(len(all_lines) - 1)]
        med_gap = float(np.median(gaps)) if gaps else 4.0
        tops = {}
        for i in items:
            f = frags[i]
            y0 = boxes[i][1] * H
            if f.get("role") == "continuation" or (f.get("role") == "question" and f.get("number") is None):
                tops[i] = y0
                continue
            cand = [s for s in starts if abs(s[0] - y0) <= MAX_MOVE * H]
            tops[i] = (min(cand, key=lambda s: abs(s[0] - y0))[0] - 2) if cand else y0
        for k, i in enumerate(items):
            x0, _, x1, y1 = new[i]
            top = tops[i]
            if k + 1 < len(items):
                bottom = tops[items[k + 1]] - 1
            else:
                # last block of the column: extend through lines that continue the block
                bottom = y1 * H
                after = [ln for ln in all_lines if ln[0] >= top]
                for a, b in after:
                    if a > bottom + 1.6 * med_gap + 2 or _in_clutter((a + b) / 2 / H, c, stops):
                        break
                    bottom = max(bottom, b)
            if abs(bottom - y1 * H) > MAX_MOVE * H * 1.5:
                bottom = y1 * H
            if bottom - top > 6:
                new[i] = (x0, max(0.0, top / H), x1, min(1.0, bottom / H))
    return new


def _in_clutter(y, col, stops):
    for x0, y0, x1, y1 in stops:
        if y0 - 0.004 <= y <= y1 + 0.004 and min(x1, col["x1"]) > max(x0, col["x0"]):
            return True
    return False
```

### 9.7 `ingest.py`: page sources, clip rendering, dedupe (P0)

**Map to Android:** `PdfPage.crop` = `PdfRenderer.Page.render(bitmap, destClip, matrix, RENDER_MODE_FOR_DISPLAY)` with a scale `Matrix` and a clip, so a big page is never rendered whole (one page open at a time, not thread-safe). `text_lines` = PDF text extraction (spike: PDFium wrapper, or `PdfRenderer` for pixels + PdfBox-Android for text; pick the one that passes the spike in the build prompt). `ImagePage` = `ImageDecoder` / `BitmapFactory` + EXIF transpose + downscale to 3200 px.
**Fix:** the page-kind rule (`> 200` chars) is §10 #2. `dhash` is 16×16, Hamming ≤ 12 = duplicate.

```python
# source: pdf2ppt/qdeck/ingest.py
"""Turn uploads into a stream of SourcePage objects, one page at a time.

A page knows how to give (a) a small JPEG for layout analysis and (b) a crop of any
normalised box at whatever resolution the slide needs. PDF pages are re-rendered per
crop (clip rendering), so a whole page is never held at high DPI in memory.
"""
import io
import os

import numpy as np
from PIL import Image, ImageOps

try:
    import pymupdf as fitz
except ImportError:  # older PyMuPDF
    import fitz

IMAGE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff", ".gif"}
MAX_IMAGE_SIDE = 3200


class SourcePage:
    native_ppi = None

    def __init__(self, file_name, index, kind):
        self.file_name = file_name
        self.index = index      # 0-based index within its file
        self.kind = kind        # "pdf-digital" | "pdf-scan" | "image"
        self.width_in = None    # physical size (inches) if known
        self.height_in = None

    @property
    def ref(self):
        return f"{os.path.basename(self.file_name)}#p{self.index + 1}"

    def layout_jpeg(self, long_side):
        img = self.render_full(long_side)
        buf = io.BytesIO()
        img.convert("RGB").save(buf, "JPEG", quality=82)
        return buf.getvalue(), img.size

    def render_full(self, long_side):
        raise NotImplementedError

    def crop(self, box, px_per_in):
        """box = (x0, y0, x1, y1) in 0..1 page fractions."""
        raise NotImplementedError

    def text_lines(self):
        """[(x0, y0, x1, y1, text, size)] in 0..1 fractions; empty if no text layer."""
        return []

    def close(self):
        pass


class PdfPage(SourcePage):
    def __init__(self, doc, file_name, index):
        page = doc[index]
        text = page.get_text("text").strip()
        imgs = page.get_images()
        kind = "pdf-digital" if len(text) > 200 else "pdf-scan"
        super().__init__(file_name, index, kind)
        self.doc, self.page = doc, page
        self.rect = page.rect
        self.width_in = self.rect.width / 72.0
        self.height_in = self.rect.height / 72.0
        self.n_images = len(imgs)
        self.native_ppi = None
        if kind == "pdf-scan":   # resolution of the largest embedded photo/scan
            best = 0
            for im in page.get_images(full=True):
                try:
                    for r in page.get_image_rects(im[0]):
                        if r.width * r.height > best and r.width > 0:
                            best = r.width * r.height
                            self.native_ppi = im[2] / (r.width / 72.0)
                except Exception:
                    pass

    def render_full(self, long_side):
        z = long_side / max(self.rect.width, self.rect.height)
        pix = self.page.get_pixmap(matrix=fitz.Matrix(z, z), alpha=False)
        return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)

    def crop(self, box, px_per_in):
        x0, y0, x1, y1 = box
        r = self.rect
        clip = fitz.Rect(r.x0 + x0 * r.width, r.y0 + y0 * r.height,
                         r.x0 + x1 * r.width, r.y0 + y1 * r.height)
        z = px_per_in / 72.0
        pix = self.page.get_pixmap(matrix=fitz.Matrix(z, z), clip=clip, alpha=False)
        return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)

    def text_lines(self):
        if self.kind != "pdf-digital":
            return []
        W, H = self.rect.width, self.rect.height
        out = []
        for b in self.page.get_text("dict")["blocks"]:
            for ln in b.get("lines", []):
                spans = [s for s in ln["spans"] if s["text"].strip()]
                if not spans:
                    continue
                x0, y0, x1, y1 = ln["bbox"]
                t = " ".join(s["text"].strip() for s in spans)
                size = max(s["size"] for s in spans)
                out.append((x0 / W, y0 / H, x1 / W, y1 / H, t, size))
        return out


class ImagePage(SourcePage):
    def __init__(self, file_name, img, index=0):
        super().__init__(file_name, index, "image")
        img = ImageOps.exif_transpose(img).convert("RGB")
        if max(img.size) > MAX_IMAGE_SIDE:
            img.thumbnail((MAX_IMAGE_SIDE, MAX_IMAGE_SIDE), Image.LANCZOS)
        self.img = img
        # Unknown physical size: assume the page is printed ~8.3 in wide (A4) so that
        # legibility estimates stay meaningful; screenshots are treated the same way.
        self.width_in = 8.3 if img.width < img.height else 11.7
        self.height_in = self.width_in * img.height / img.width

    def render_full(self, long_side):
        im = self.img.copy()
        im.thumbnail((long_side, long_side), Image.LANCZOS)
        return im

    def crop(self, box, px_per_in):
        W, H = self.img.size
        x0, y0, x1, y1 = box
        c = self.img.crop((int(x0 * W), int(y0 * H), int(np.ceil(x1 * W)), int(np.ceil(y1 * H))))
        # Never upscale beyond the source; the slide only needs ~220 dpi.
        want_w = int((x1 - x0) * self.width_in * px_per_in)
        if want_w and c.width > want_w * 1.05:
            c = c.resize((want_w, max(1, int(c.height * want_w / c.width))), Image.LANCZOS)
        return c

    def close(self):
        self.img = None


def iter_pages(paths):
    """Yield SourcePage objects in upload order, opening one file at a time."""
    for path in paths:
        ext = os.path.splitext(path)[1].lower()
        if ext == ".pdf":
            doc = fitz.open(path)
            try:
                for i in range(doc.page_count):
                    p = PdfPage(doc, path, i)
                    yield p
                    p.close()
            finally:
                doc.close()
        elif ext in IMAGE_EXT:
            with Image.open(path) as im:
                p = ImagePage(path, im)
            yield p
            p.close()
        else:
            raise ValueError(f"Unsupported file type: {os.path.basename(path)}")


def count_pages(paths):
    n = 0
    for path in paths:
        if path.lower().endswith(".pdf"):
            with fitz.open(path) as d:
                n += d.page_count
        else:
            n += 1
    return n


def dhash(img, size=16):
    g = img.convert("L").resize((size + 1, size), Image.BILINEAR)
    a = np.asarray(g, dtype=np.int16)
    return (a[:, 1:] > a[:, :-1]).flatten()


def _pdf_figure_boxes(self):
    """Raster images + vector drawing clusters on the page (fractions), excluding
    page-sized background/watermark art."""
    W, H = self.rect.width, self.rect.height
    out = []
    try:
        for info in self.page.get_image_info():
            x0, y0, x1, y1 = info["bbox"]
            if (x1 - x0) * (y1 - y0) < 0.35 * W * H:
                out.append((x0 / W, y0 / H, x1 / W, y1 / H))
    except Exception:
        pass
    return out


PdfPage.figure_boxes = _pdf_figure_boxes
SourcePage.figure_boxes = lambda self: []
```

### 9.8 `pipeline.py`: orchestration (P0)

**Keep the shape:** pass 1 (small layout JPEGs, dHash, text lines), pass 2 (cheapest layout first, then escalate), pass 3 (crops, one page at a time), assemble, then project + export.
**Replace pass 2a/2b** with the ladder of §5.1 and the scheduler of §5.2. Keep `_script_lang`, `_frag`, `_stem`, `_box`. Lines 122-220 (the crop pass and project build) are not repeated here: read them in the legacy file when porting. The server-side parts are not ported.

```python
# source: pdf2ppt/qdeck/pipeline.py  (lines 1-121, 221-295)
"""End-to-end job: pages -> layout (Gemini, classical fallback) -> crops -> questions
-> pptx + QA report + manifest. Streams one page at a time; peak RAM stays small."""
import os
import shutil
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image

from . import imaging
from .assemble import Fragment, assemble, reading_order
from .ingest import count_pages, dhash, iter_pages
from .layout_classical import ClassicalLayout
from .layout_gemini import GeminiLayout, QuotaExhausted
from .project import Project
from .refine import refine
from .watermark import strip_pdf_watermarks

KEEP_ROLES = ("question", "continuation", "shared_passage")
MIN_FONT_PT = 10      # below this on the slide the question is flagged "small_text"
ILLEGIBLE_PT = 7      # below this it is flagged "too_small_to_read"


def _box(b):
    y0, x0, y1, x1 = [max(0, min(1000, int(v))) / 1000 for v in (list(b) + [0, 0, 1000, 1000])[:4]]
    if x1 <= x0 or y1 <= y0:
        return None
    return (x0, y0, x1, y1)


def run_job(paths, out_dir, settings, options=None, progress=lambda *a: None, log=print, keys=None,
            on_pool=None):
    """Analyse the inputs into an editable project (out_dir/project.json) and export
    the first version of the deck + QA report from it."""
    t0 = time.time()
    opts = settings.job_options()
    opts.update(options or {})
    os.makedirs(out_dir, exist_ok=True)
    work = os.path.join(out_dir, "work")
    pages_dir = os.path.join(out_dir, "pages")
    os.makedirs(work, exist_ok=True)
    os.makedirs(pages_dir, exist_ok=True)
    # watermark removal at the source: cleaned PDF copies are used for everything
    srcs = []
    for i, path in enumerate(paths):
        if path.lower().endswith(".pdf") and opts["watermark"] == "remove":
            clean = os.path.join(out_dir, "src", f"{i:02d}_{os.path.basename(path)}")
            os.makedirs(os.path.dirname(clean), exist_ok=True)
            try:
                strip_pdf_watermarks(path, clean, log=log)
                srcs.append(clean)
                continue
            except Exception as e:
                log(f"watermark strip skipped for {os.path.basename(path)}: {type(e).__name__}")
        srcs.append(path)
    paths = srcs
    n_pages = count_pages(paths)
    progress("pages", 0, n_pages)

    # ---- pass 1: small layout images, duplicate-page detection, text layers -----------
    pages = []
    hashes = []
    for seq, page in enumerate(iter_pages(paths)):
        jpeg, size = page.layout_jpeg(settings.layout_px)
        jp = os.path.join(pages_dir, f"{seq:04d}.jpg")
        with open(jp, "wb") as f:
            f.write(jpeg)
        h = dhash(Image.open(jp))
        dup_of = next((i for i, hh in enumerate(hashes) if np.count_nonzero(h != hh) <= 12), None)
        hashes.append(h)
        pages.append({"seq": seq, "ref": page.ref, "kind": page.kind, "jpeg": jp, "dup_of": dup_of,
                      "lines": page.text_lines(), "figs": page.figure_boxes(), "src": page.file_name,
                      "index": page.index, "w_in": page.width_in, "h_in": page.height_in,
                      "native_ppi": page.native_ppi,
                      "thumb_w": size[0], "thumb_h": size[1]})
        progress("rendered", seq + 1, n_pages)
    classical = ClassicalLayout()
    classical.prime([p["lines"] for p in pages], [p["figs"] for p in pages])

    # ---- pass 2a: classical first for born-digital files ------------------------------
    layouts = [None] * len(pages)
    if settings.classical_first:
        _classical_digital(paths, pages, layouts, classical, log)
        classical = ClassicalLayout()          # fresh state for any later fallback use
        classical.prime([p["lines"] for p in pages], [p["figs"] for p in pages])

    # ---- pass 2b: vision-LLM layout for the rest (batched, parallel, cached) ----------
    llm = GeminiLayout(settings, log=log, keys=keys) if settings.use_llm else None
    if llm is not None and on_pool:
        on_pool(llm.pool)
    todo = [p for p in pages if layouts[p["seq"]] is None and p["dup_of"] is None]
    for p in pages:
        if p["dup_of"] is not None:
            layouts[p["seq"]] = {"page_kind": "duplicate", "dark_background": False, "fragments": [],
                                 "_model": "skip"}
    done = [len(pages) - len(todo)]
    progress("analysed", done[0], n_pages)
    llm_failed = [False]
    batches = [todo[i:i + max(1, settings.pages_per_call)] for i in range(0, len(todo), max(1, settings.pages_per_call))]

    def run_batch(batch):
        if llm is not None and not llm_failed[0]:
            try:
                jpegs = []
                for p in batch:
                    with open(p["jpeg"], "rb") as f:
                        jpegs.append(f.read())
                res = llm.analyse_batch(jpegs)
                for p, r in zip(batch, res):
                    layouts[p["seq"]] = r
            except QuotaExhausted as e:
                log(f"LLM unavailable ({e}); classical layout for the remaining pages")
                llm_failed[0] = True
        done[0] += len(batch)
        progress("analysed", done[0], n_pages)

    if batches:
        workers = max(1, min(len(batches), llm.pool.parallelism if llm else 1, max(settings.gemini_workers, 1) * 4))
        with ThreadPoolExecutor(max_workers=workers) as ex:
            list(ex.map(run_batch, batches))

# ...

def _classical_digital(paths, pages, layouts, classical, log):
    """Run the CPU parser on born-digital files; keep its result only when the
    numbering integrity is perfect (no gaps / duplicates, every page accounted for)."""
    from .assemble import integrity_check, Question
    seq = 0
    for path in paths:
        file_pages = []
        for page in iter_pages([path]):
            file_pages.append((seq, page.kind,
                               classical.analyse_text_page(page) if page.kind == "pdf-digital" else None))
            seq += 1
        if not file_pages or any(k != "pdf-digital" for _, k, _ in file_pages):
            continue
        nums = [f.get("number") for _, _, lay in file_pages for f in lay["fragments"] if f["role"] == "question"]
        ok = (len(nums) >= 3 and None not in nums and nums == list(range(nums[0], nums[0] + len(nums))))
        if ok:
            for s_, _, lay in file_pages:
                if pages[s_]["dup_of"] is None:
                    layouts[s_] = lay
            log(f"{os.path.basename(path)}: text layer parsed without LLM ({len(nums)} questions, numbering complete)")
        else:
            log(f"{os.path.basename(path)}: classical parse incomplete; using the vision LLM")


def _script_lang(text, fallback):
    """Language from the transcribed script, not the model's label (lite models call a
    Hindi block 'bilingual' when it contains a few Latin symbols)."""
    dev = sum(1 for c in text if "\u0900" <= c <= "\u097f")
    lat = sum(1 for c in text if c.isascii() and c.isalpha())
    if dev + lat < 12:
        return fallback
    r = dev / (dev + lat)
    return "hi" if r > 0.6 else ("en" if r < 0.1 else "bilingual")


def _frag(p, f, box, path, i, lay):
    text = (f.get("stem") or f.get("text") or "").strip()
    f["lang"] = _script_lang(text + " " + " ".join(f.get("options") or []), f.get("lang", "en"))
    return Fragment(page_seq=p["seq"], page_ref=p["ref"], order=f.get("order", i), role=f.get("role", "question"),
                    label=f.get("label"), number=f.get("number"), lang=f.get("lang", "en"), box=box,
                    crop_path=path, text=text, options=[o for o in (f.get("options") or []) if o],
                    confidence=float(f.get("confidence", 0.5)), has_figure=bool(f.get("has_figure")),
                    has_math=bool(f.get("has_math")), has_table=bool(f.get("has_table")),
                    continues_previous=bool(f.get("continues_previous")),
                    continues_on_next=bool(f.get("continues_on_next")), source_kind=p["kind"],
                    model=lay.get("_model", ""))


OPT = __import__("re").compile(r"^\s*[\(\[]?([a-dA-D1-4])[\)\].]\s+(.*)$")


def _stem(q):
    """Question text without duplicated lines; option lines the LLM left inside the
    stem are moved to q.options (when it returned none)."""
    lines, seen = [], set()
    for f in [q.root] + q.parts:
        for ln in (f.text or "").splitlines():
            k = " ".join(ln.split()).lower()
            if k and k not in seen:
                seen.add(k)
                lines.append(ln.strip())
    if not q.options:
        stem, opts = [], []
        for ln in lines:
            m = OPT.match(ln)
            if m and len(opts) < 4:
                opts.append(m.group(2))
            elif opts:
                opts[-1] += " " + ln
            else:
                stem.append(ln)
        if len(opts) >= 2:
            q.root.options = opts
            return " ".join(stem)
    return " ".join(lines)
```

### 9.9 `deck.py`: slide geometry, fit estimate, placement (P0)

**Replace** python-pptx with the OOXML writer of §5.6 and the `SlideLayout` enum of §5.3 (`TOP`, `TOP_RIGHT`). **Keep:** the box geometry (`Q1` equals `TOP_RIGHT`), the placement maths in `add_snapshot` (scale to fit, cap at `MAX_PT`, estimated displayed font size), and `rebuild_fits` if you add text rebuild later (P2). The "fill" layout is dropped.

```python
# source: pdf2ppt/qdeck/deck.py
"""PowerPoint writer. 16:9, white, one question per slide in Quadrant 1 (top-right).
Repeated decoration (cross-hair axes, background) lives on the slide master only."""
import io

from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import MSO_ANCHOR
from pptx.oxml.ns import qn
from pptx.util import Emu, Inches, Pt

SLIDE_W, SLIDE_H = 13.333, 7.5
INSET = 0.15
Q1 = (SLIDE_W / 2 + INSET, INSET, SLIDE_W / 2 - 2 * INSET, SLIDE_H / 2 - 2 * INSET)  # left, top, w, h (in)
MIN_REBUILD_PT = 16

THEMES = {
    # v1 ships "light"; "dark" is the v2 switch: same manifest, different paint.
    "light": {"bg": RGBColor(0xFF, 0xFF, 0xFF), "fg": RGBColor(0x11, 0x11, 0x11), "axis": RGBColor(0xC8, 0xC8, 0xC8)},
    "dark": {"bg": RGBColor(0x12, 0x14, 0x18), "fg": RGBColor(0xF2, 0xF2, 0xF2), "axis": RGBColor(0x44, 0x48, 0x50)},
}

DEVANAGARI_FONT = "Nirmala UI"   # Windows 8+; PowerPoint falls back to Mangal on Windows 7
LATIN_FONT = "Arial"


MARGIN = 0.3
FULL = (MARGIN, MARGIN, SLIDE_W - 2 * MARGIN, SLIDE_H - 2 * MARGIN)
MAX_PT = 36          # never blow a short question up beyond ~36 pt type


def _new_presentation(theme):
    prs = Presentation()
    prs.slide_width = Inches(SLIDE_W)
    prs.slide_height = Inches(SLIDE_H)
    blank = prs.slide_layouts[6]
    bg = prs.slide_master.background.fill
    bg.solid()
    bg.fore_color.rgb = THEMES[theme]["bg"]
    return prs, blank


def _set_fonts(run, size_pt, color, bold=False):
    run.font.size = Pt(size_pt)
    run.font.bold = bold
    run.font.color.rgb = color
    run.font.name = LATIN_FONT
    rPr = run._r.get_or_add_rPr()
    for tag, face in (("a:ea", DEVANAGARI_FONT), ("a:cs", DEVANAGARI_FONT)):
        el = rPr.find(qn(tag))
        if el is None:
            el = etree.SubElement(rPr, qn(tag))
        el.set("typeface", face)


def rebuild_fits(label, stem, options, box=Q1):
    """Largest font (pt) at which the rebuilt text fits the box, or None."""
    paras = [f"{label} {stem}"] + [f"({chr(97 + i)}) {o}" for i, o in enumerate(options)]
    for size in range(40, MIN_REBUILD_PT - 1, -1):
        cpl = box[2] * 72 / (0.55 * size)  # characters per line (average glyph width ~0.55 em)
        lines = sum(max(1, -(-len(p) // int(cpl))) for p in paras)
        if lines * size * 1.25 / 72 + 0.15 <= box[3]:
            return size
    return None


class DeckBuilder:
    """layout "fill": the question uses the whole slide (centred, top-aligned);
    layout "top-right": the v1 quadrant placement. No axes, no quadrant lines."""

    def __init__(self, theme="light", layout="fill"):
        self.theme = theme
        self.prs, self.layout = _new_presentation(theme)
        self.t = THEMES[theme]
        self.box = FULL if layout == "fill" else Q1
        self.centre = layout == "fill"

    def add_snapshot(self, img_bytes, px_size, notes, src_w_in=None, glyph_pt=None):
        """Place a question picture; returns the estimated font size on the slide."""
        s = self.prs.slides.add_slide(self.layout)
        L, T, BW, BH = self.box
        w_px, h_px = px_size
        scale = min(BW / w_px, BH / h_px)
        w_in, h_in = w_px * scale, h_px * scale
        disp = None
        if src_w_in and glyph_pt:
            disp = glyph_pt / 0.52 * (w_in / src_w_in)
            if disp > MAX_PT:                       # short question: don't make it gigantic
                f = MAX_PT / disp
                w_in, h_in, disp = w_in * f, h_in * f, MAX_PT
        left = L + (BW - w_in) / 2 if self.centre else L
        s.shapes.add_picture(io.BytesIO(img_bytes), Inches(left), Inches(T), Inches(w_in), Inches(h_in))
        self._notes(s, notes)
        return round(disp, 1) if disp else None

    def add_blank(self):
        """Empty slide for the teacher's working."""
        self.prs.slides.add_slide(self.layout)

    def add_rebuilt(self, label, stem, options, size_pt, notes):
        s = self.prs.slides.add_slide(self.layout)
        tb = s.shapes.add_textbox(Inches(self.box[0]), Inches(self.box[1]), Inches(self.box[2]), Inches(self.box[3]))
        tf = tb.text_frame
        tf.word_wrap = True
        tf.vertical_anchor = MSO_ANCHOR.TOP
        for side in ("margin_left", "margin_right", "margin_top", "margin_bottom"):
            setattr(tf, side, Emu(Inches(0.05)))
        p = tf.paragraphs[0]
        r = p.add_run()
        r.text = label + " "
        _set_fonts(r, size_pt, self.t["fg"], bold=True)
        r = p.add_run()
        r.text = stem
        _set_fonts(r, size_pt, self.t["fg"])
        for i, o in enumerate(options):
            p = tf.add_paragraph()
            p.space_before = Pt(size_pt * 0.3)
            r = p.add_run()
            r.text = f"({chr(97 + i)}) {o}"
            _set_fonts(r, size_pt, self.t["fg"])
        self._notes(s, notes)

    @staticmethod
    def _notes(slide, notes):
        if notes:
            slide.notes_slide.notes_text_frame.text = notes

    def save(self, path):
        self.prs.save(path)
```

### 9.10 `project.py`: render pipeline and export (P0)

**Keep:** `target_dpi` (resolution depends on the layout box: include the layout in `_render_key`), and the **order of operations** in `render_parts`: crop from the source at the target dpi → erase duplicate-ID columns → `to_light` → `strip_rules` → `tighten` → (photos) `enhance` → align and `stack` parts of a page-split question → `compress_gaps` (not for figures) → `tighten`. `export` computes the displayed font size and sets the flags `small_text` (< 12 pt) and `too_small_to_read` (< 7 pt). The rest of the file (undo stack, locking, public JSON) is the editor's storage: reuse the schema, simplify the code.

```python
# source: pdf2ppt/qdeck/project.py  (lines 131-230, 237-299)
    def _render_key(self, it):
        s = self.settings
        blob = json.dumps([it.get("parts"), it.get("custom_image"), it.get("erase"), s["watermark"],
                           s.get("theme"), it.get("no_compress"), s.get("layout"), s.get("dpi"), 3], sort_keys=True)
        return hashlib.md5(blob.encode()).hexdigest()[:12]

    def item_png(self, iid):
        it = self.item(iid)
        key = self._render_key(it)
        path = os.path.join(self.root, "items", f"{iid}_{key}.png")
        if not os.path.exists(path):
            img, ppi = self.render_parts(it)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            img.save(path, compress_level=1)          # working copy: fast, lossless
            it["img_w"], it["img_h"], it["ppi"] = img.width, img.height, ppi
            glyph = imaging.text_height_pt(img, ppi)
            it["glyph_pt"] = glyph
        return path

    def target_dpi(self, it):
        """Pixels per *paper* inch so the picture has `settings.dpi` pixels per inch
        at the size it is shown on the slide (questions are enlarged to fill it)."""
        from .deck import FULL, Q1
        out_dpi = int(self.settings.get("dpi", 300))
        box = FULL if self.settings.get("layout", "fill") == "fill" else Q1
        w = h = 0.0
        for part in it.get("parts") or []:
            meta = self.data["pages"][part["page"]]
            x0, y0, x1, y1 = part["box"]
            w = max(w, (x1 - x0) * meta["w_in"])
            h += (y1 - y0) * meta["h_in"] + 0.05
        if w <= 0 or h <= 0:
            return out_dpi
        scale = min(box[2] / w, box[3] / h)
        return int(min(600, max(out_dpi, out_dpi * scale)))

    def render_parts(self, it, dpi=None):
        """Crop every part from its page, clean it (watermark, rules, light theme),
        stack page-split parts with their original alignment, trim margins."""
        s = self.settings
        dpi = dpi or self.target_dpi(it)
        if it.get("custom_image"):
            img = Image.open(os.path.join(self.root, "items", it["custom_image"])).convert("RGB")
            return img, float(it.get("custom_ppi") or 200)
        imgs, lefts, ppis = [], [], []
        for part in it.get("parts") or []:
            meta = self.data["pages"][part["page"]]
            page = self.open_page(part["page"])
            try:
                box = part["box"]
                native = float(meta.get("native_ppi") or dpi)
                if meta["kind"] == "pdf-digital":
                    img, ppi = page.crop(box, dpi), dpi                 # vector: render sharp at dpi
                elif meta["kind"] == "image":
                    img = page.crop(box, 10000)                          # native pixels, no resampling
                    ppi = img.width / max(1e-6, (box[2] - box[0]) * page.width_in)
                else:                                                    # scanned / photographed PDF page
                    ppi = min(dpi, native * 1.0)
                    img = page.crop(box, ppi)
                erase = [e for e in it.get("erase") or [] if e.get("page") == part["page"]]
                if erase:
                    W, H = img.size
                    bw, bh = box[2] - box[0], box[3] - box[1]
                    img = imaging.erase(img, [((e["box"][0] - box[0]) / bw * W, (e["box"][1] - box[1]) / bh * H,
                                               (e["box"][2] - box[0]) / bw * W, (e["box"][3] - box[1]) / bh * H)
                                              for e in erase])
                img = imaging.to_light(img, watermark=s["watermark"], photo=meta["kind"] != "pdf-digital",
                                       screenshot=meta["kind"] == "image")
                img = imaging.strip_rules(img)
                img, rect = imaging.tighten(img)
                rect_ppi = ppi
                if meta["kind"] != "pdf-digital":
                    # photos / scans / screenshots: upscale to the display resolution and
                    # crisp the ink (also clears pale watermarks when removal is on)
                    img, ppi = imaging.enhance(img, ppi, max(dpi, ppi), crisp_ink=s["watermark"] == "remove")
                imgs.append(img)
                ppis.append(ppi)
                lefts.append(box[0] * meta["w_in"] + rect[0] / rect_ppi)
            finally:
                doc = getattr(page, "_doc_owner", None)
                if doc is not None:
                    doc.close()
        if not imgs:
            return Image.new("RGB", (400, 120), "white"), 200.0
        target = min(ppis)
        scaled = []
        for im, p in zip(imgs, ppis):
            if p > target * 1.05:
                f = target / p
                im = im.resize((max(1, int(im.width * f)), max(1, int(im.height * f))), Image.LANCZOS)
            scaled.append(im)
        aligned = max(lefts) - min(lefts) < 1.0
        img = imaging.stack(scaled, offsets=[int((l - min(lefts)) * target) for l in lefts] if aligned else None)
        if not it.get("no_compress") and "figure" not in (it.get("flags") or []):
            img = imaging.compress_gaps(img, target)
        img, _ = imaging.tighten(img)
        if s.get("theme") == "dark":
            from PIL import ImageOps
            img = ImageOps.colorize(ImageOps.invert(img.convert("L")), black="#121418", white="#f2f2f2")
        return img, float(target)

# ...

    def export(self, out_dir, title="questions"):
        s = self.settings
        deck = DeckBuilder(theme=s.get("theme", "light"), layout=s.get("layout", "fill"))
        rows, thumbs = [], []
        for n, it in enumerate(self.data["items"], 1):
            notes = _notes(it)
            if it.get("render") == "text" and (it.get("stem") or "").strip():
                size = rebuild_fits(it.get("label") or "", it["stem"], it.get("options") or [], deck.box)
                if size:
                    deck.add_rebuilt(it.get("label") or "", it["stem"], it.get("options") or [], size, notes)
                    disp = size
                else:
                    disp = self._add_picture(deck, it, notes)
            else:
                disp = self._add_picture(deck, it, notes)
            it["display_font_pt"] = disp
            blank = it.get("blank_after")
            if blank is None:
                blank = s.get("blank_after", True)
            if blank:
                deck.add_blank()
            flags = list(dict.fromkeys(it.get("flags") or []))
            flags = [f for f in flags if f not in ("small_text", "too_small_to_read")]
            if disp is not None and disp < 7:
                flags.append("too_small_to_read")
            elif disp is not None and disp < 12:
                flags.append("small_text")
            it["flags"] = flags
            pages = " + ".join(dict.fromkeys(self.data["pages"][p["page"]]["ref"] for p in it.get("parts") or []))
            rows.append({"slide": n, "label": it.get("label"), "number": it.get("number"), "section": it.get("section", 1),
                         "lang": it.get("lang"), "pages": pages or "(custom)", "render": it.get("render"),
                         "confidence": it.get("confidence"), "display_font_pt": disp, "flags": ";".join(flags),
                         "text": (it.get("stem") or "")[:300]})
            with open(self.item_png(it["id"]), "rb") as f:
                thumbs.append(_thumb(f.read()))
        os.makedirs(out_dir, exist_ok=True)
        base = os.path.join(out_dir, title)
        deck.save(base + ".pptx")
        integ = dict(self.integrity(), sections=[])
        write_csv(base + "_qa.csv", rows)
        write_html(base + "_qa.html", title, self.data.get("summary", ""), integ, rows, thumbs)
        self.save()
        return base + ".pptx", len(deck.prs.slides)

    def _add_picture(self, deck, it, notes):
        path = self.item_png(it["id"])
        img = Image.open(path)
        ppi = float(it.get("ppi") or 220)
        dpi = int(self.settings.get("dpi", 300))
        data, fmt, px = imaging.encode(img, int(deck.box[2] * dpi * 1.05), int(deck.box[3] * dpi * 1.05))
        scale_in = deck.add_snapshot(data, px, notes, src_w_in=img.width / ppi, glyph_pt=it.get("glyph_pt"))
        return scale_in


def _notes(it):
    lines = [f"Q {it.get('label') or '?'}"]
    if it.get("flags"):
        lines.append("flags: " + ", ".join(it["flags"]))
    if it.get("stem"):
        lines.append(it["stem"][:1500])
    for i, o in enumerate(it.get("options") or []):
        lines.append(f"({chr(97 + i)}) {o}")
    return "\n".join(lines)
```

### 9.11 `config.py`: defaults worth keeping (P0)

`gemini_rpm = 8` (per key), `layout_px = 1536`, `crop_dpi = 250`, `classical_first = True`. `pages_per_call = 3` becomes `slicePages = 2` (range 1-4). Keep every default in one `Settings` object that the UI can edit, and never read keys from it.

```python
# source: pdf2ppt/qdeck/config.py  (lines 20-47)
@dataclass
class Settings:
    gemini_api_key: str = field(default_factory=lambda: _env("GEMINI_API_KEY", ""))
    # Any number of keys (newline / comma separated). Used when a job brings none.
    gemini_api_keys: str = field(default_factory=lambda: _env("GEMINI_API_KEYS", ""))
    # First model that answers wins; later ones are used on 404 / quota exhaustion.
    gemini_models: str = field(default_factory=lambda: _env(
        "GEMINI_MODELS", "gemini-3.8-flash,gemini-3-flash-preview,gemini-3.7-flash,gemini-3.6-flash,gemini-3.5-flash-lite,gemini-3.1-flash-lite"))
    gemini_rpm: int = field(default_factory=lambda: _env("GEMINI_RPM", 8))
    gemini_workers: int = field(default_factory=lambda: _env("GEMINI_WORKERS", 2))
    # Pages per request. Free tier is counted in requests/day (20 per Flash model at the
    # time of writing), so batching pages is the main lever.
    pages_per_call: int = field(default_factory=lambda: _env("PAGES_PER_CALL", 3))
    # Digital PDFs: trust the classical parser when its numbering integrity is perfect.
    classical_first: bool = field(default_factory=lambda: _env("CLASSICAL_FIRST", True))
    layout_px: int = field(default_factory=lambda: _env("LAYOUT_PX", 1536))   # long side sent to the LLM
    crop_dpi: int = field(default_factory=lambda: _env("CROP_DPI", 250))       # px per inch of the question pictures as shown on the slide
    cache_dir: str = field(default_factory=lambda: _env("CACHE_DIR", ".cache"))
    use_llm: bool = field(default_factory=lambda: _env("USE_LLM", True))

    # Output options (also settable per job from the web form)
    watermark: str = "remove"        # remove | keep  (v2: no watermark in output by default)
    layout: str = "fill"             # fill (whole slide) | top-right (v1 quadrant)
    blank_after: bool = True         # empty working slide after every question
    lang: str = "en"                 # en | hi | both  (for papers that print each question twice)
    render: str = "auto"             # auto | snapshot | rebuild
    theme: str = "light"             # light (v1); dark is a v2 switch, see deck.py
    solutions_in_notes: bool = False # v2 toggle; v1 always excludes solutions from slides
```

### 9.12 `watermark.py`: PDF-level watermark removal (P2, not in the MVP)

Idea only: objects stamped identically on most pages (Form XObjects, large repeated images, `Watermark` annotations) are blanked in a cleaned copy of the PDF, so the text underneath stays pixel-perfect. It needs low-level PDF editing, which is heavy on Android. **MVP does raster-level removal only** (`to_light(..., watermark="remove")`: pale pixels above luminance 238 become white for digital pages; photos use local-contrast `enhance`). Read `pdf2ppt/qdeck/watermark.py` if this is added later.


---

## 10. Known legacy defects: do NOT port these as they are

Found by running the legacy Python on the synthetic fixtures (`tools/reference_run.py`) and by reading the code. Each has a fixture that exposes it.

| # | Defect | Where | Effect | What the Kotlin port must do |
|---|---|---|---|---|
| 1 | **Answer-key detection is too strict.** The page counts as a key only if the header matches *and* more than 60 % of lines are ≤ 4 characters | `layout_classical._looks_like_answer_key` | A key such as `10  C` is parsed as 15 extra questions (30 became 45) | Header `ANSWER KEY` / `Answers` **and** ≥ 70 % of lines match `^\d{1,3}\s*[.\-:)]?\s*\(?[A-Da-d1-4]\)?$`, or a dense grid of short tokens |
| 2 | **Short pages are classed as scans.** A page with < 200 chars of text is `pdf-scan` | `ingest.PdfPage.__init__` | A last page with one question loses its number (`digital_1col`) | Classify per page by *real text lines found*, and let a short page inherit the file's majority kind when its neighbours are digital |
| 3 | **`integrity_check().ok` has false passes.** 0 questions found = 0 expected = ok. Unnumbered items add to `expected`, so they also pass. A numbering restart opens a new "section", so duplicates are never reported | `assemble.integrity_check` | Silent 0-question decks (`garbage_textlayer`), "ok" for 38 unnumbered scan blocks | Use the rules in §5.1. "No numbers found" is **not ok** |
| 4 | **A junk text layer is trusted.** Any page with > 200 chars of extractable text is "digital" | `ingest` | `garbage_textlayer.pdf` yields 0 questions | If a text page yields 0 anchors while ink exists, escalate to OCR |
| 5 | **Image-only pages without a key produce unnumbered blocks** and still report "ok" | `layout_classical.analyse_image_page` | Teachers get a deck that looks fine and is wrong | Make it T3 only, flag every slide `low_confidence` + `no_number`, never "ok" |
| 6 | **Model names are hard-coded** (`gemini-3.8-flash`, …) and will rot | `config.Settings.gemini_models` | 404s, wasted calls | Config default + `models.list` discovery + user-editable list |
| 7 | `maxOutputTokens: 60000` may exceed a model's limit | `layout_gemini._call` | 400 errors on some models | Read the limit from `models.list` (`outputTokenLimit`) or make it configurable |
| 8 | Box refinement constants (`MAX_MOVE`, `tol`) were tuned on a few papers | `refine.py` | May misfire on unusual layouts | Keep, but unit-test on fixtures and keep a switch to disable |
| 9 | The dark theme is unfinished; rebuild-as-text uses the font `Nirmala UI` (Windows only) | `deck.py` | n/a on Android | Skip for MVP (P2) |
| 10 | The server-side bits (job dir, TTL, upload limits, uvicorn, in-memory jobs) | `server.py` | n/a | Do not port. The phone is the server |

---

## 11. Glossary for the owner (not a mobile developer)

| Word | Meaning |
|---|---|
| **APK** | The installable file of an Android app, like a `.exe` for Android |
| **Sideloading** | Installing an APK from a file instead of the Play Store. Android asks you to allow "Install unknown apps" once for the app you open it from |
| **Play Protect** | Google's scanner. It may say "unrecognised app". Tap "Install anyway" for an app you built yourself |
| **Android SDK / Gradle** | The toolbox and build tool that turn the source code into an APK |
| **Kotlin / Jetpack Compose** | The language and the screen toolkit used for modern Android apps |
| **GitHub Actions** | A free cloud machine that can build the APK for you, so nothing heavy is installed on your PC |
| **WorkManager / foreground service** | Android's way to run a long job reliably in the background with a visible notification |
| **Keystore** | A vault built into the phone's hardware or OS where encryption keys live and cannot be copied out |
| **OCR** | Software that reads the text in a picture. ML Kit OCR runs on the phone, offline |
| **Slice** | A small group of consecutive pages (2 by default) sent to one API key in one request |
| **Lane** | One (API key, model) pair, tracked separately for rate limits and daily quota |
| **Debug / release build** | A debug APK is signed with a test key and is fine for your own phone. A release build is for the Play Store (not needed for the MVP) |
