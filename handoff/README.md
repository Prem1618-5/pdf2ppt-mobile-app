# Handoff pack: turn "Paper to Slides" into an Android app

This folder is everything a fresh Claude Opus session needs to build your Android app **without touching your existing project**. Your current code in `pdf2ppt/` is only ever read, never changed.

## What is in here

| File | For | What it is |
|---|---|---|
| `02_OPUS_BUILD_PROMPT.md` | Opus | The build prompt. It tells Opus what to build, in what order, how to test it, and how to hand you an APK |
| `01_CONTEXT_AND_SNIPPETS.md` | Opus | Your requirements, the three problems and their fixes, the new algorithms, what the old project taught us (dos and don'ts), the test plan, and the reusable code from the old project, copied unchanged |
| `fixtures/` | Opus | Test papers made on purpose, with the correct answers written down |
| `reference/` | Opus | Two known-good PowerPoint files, one per layout, used as templates |
| `tools/` | Opus | Small scripts that create the test papers and check the PowerPoint files |
| `README.md` | You | This page |

## How to start (about 2 minutes)

1. **Make an empty folder** for the new app, for example `D:\Development Project\papertoslides-android`.
2. Open **Claude Code** (the desktop app) in that folder and choose the **latest Opus** model.
3. Paste this as your first message:

   > Read `D:\Development Project\Coaching Problem Solution\android-handoff\02_OPUS_BUILD_PROMPT.md` and do exactly what it says. The old project at `D:\Development Project\Coaching Problem Solution\pdf2ppt` is read-only.

4. Answer the **one message** Opus sends you at the start (see below), then let it work.

## What Opus will ask you, and what you need to do

Opus sends one message at the start. It needs three things from you:

1. **Permission to install build tools on your PC** (Java and Google's Android toolkit, a few GB). Say yes for faster work, or no and it will build in the cloud instead (slower but installs nothing).
2. **An empty GitHub repository.** Go to <https://github.com/new>, name it `papertoslides-android` (private is fine), create it, and send Opus the web address. GitHub then builds the APK for you in the cloud.
3. *(Optional)* your phone model and Android version.

**Never paste an API key, password or token into the chat.** Opus will never ask for one.

## When it is done

Opus gives you an **APK** file (a download link or a path) and a plain guide, `HOW_TO_INSTALL.md`. In short:

1. Send the APK to your phone (cable, Google Drive, or download it there).
2. Open it. Android asks once to allow "Install unknown apps". Allow it. If Play Protect says "unrecognised app", tap **Install anyway** (you built it yourself).
3. Open the app, go to **Keys**, and paste your Gemini key (free from <https://aistudio.google.com>). Then choose a PDF and make your slides.

Then run **Settings → Self-test** once and send Opus a screenshot. It checks the app on your real phone and shows what to fix.

## What to expect (honest)

- Most clean, typed PDFs and many scans need **no Gemini calls at all**, because the phone reads them itself. That is what fixes the "hitting the API limit fast" problem.
- Photos of printed pages and Hindi or two-page spreads may still need Gemini. Several keys work **at the same time**, two pages each.
- Keys from the **same Google project share one limit**. Keys only add capacity if they come from different projects. Use keys you are allowed to use.
- The phone does the work, so a long paper can warm it up and use battery. The app slows itself down when the phone gets hot.
- The two layouts are **Top** (top half, full width) and **Top right** (top-right quarter). "Top" means the top half of the slide, full width (you confirmed this).

## Safety

- Your existing project (`pdf2ppt/`) is never modified.
- The live website at `https://pdf2ppt-qfb0.onrender.com` is not touched. It keeps working as before.
- The test papers in `fixtures/` are made up. Your real papers stay on your machine.
