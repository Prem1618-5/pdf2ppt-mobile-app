# How to install Paper to Slides on your phone

You do not need to know anything about app development. This takes about 5 minutes.

## 1. Download the app file (APK)

1. Open <https://github.com/Prem1618-5/pdf2ppt-mobile-app/actions> (sign in to GitHub).
2. Click the newest run with a **green tick** named **Android**.
3. Scroll down to **Artifacts** and click **PaperToSlides-debug-apk**. A `.zip` file downloads.
4. Open the zip. Inside is `PaperToSlides-v0.1.0-debug.apk`. That is the app.

(If a **Release** exists on the repository page, you can download the `.apk` from there instead.)

## 2. Put it on the phone

Any of these works:
- Download it directly on the phone (steps above, in the phone's browser), or
- Copy it with a USB cable into the phone's **Download** folder, or
- Upload it to Google Drive and open it from the Drive app on the phone.

## 3. Install it

1. On the phone, tap the `.apk` file (Files app → Downloads).
2. Android asks to **allow installing unknown apps** for that app (Chrome, Files or Drive). Tap **Settings**, switch it on, go back.
3. Tap **Install**.
4. If **Play Protect** says "unrecognised app" or "unsafe app", tap **More details → Install anyway**. It says this because the app is not from the Play Store; you built it yourself.

**Updating later:** install a newer APK the same way. It installs over the old one and keeps your keys and decks.

## 4. First run

1. Open **Paper to Slides**.
2. *(Optional, for scans and photos the phone cannot read)* tap the **key icon** (top right), paste your Gemini key and tap **Add**, then **Check**.
   Get a free key at <https://aistudio.google.com> → **Get API key**. Never share a key in chat or email.
3. Tap **Choose paper**, pick a PDF (or photos of the pages, in order).
4. Choose **Top** or **Top right**, then **Make slides**. You can leave the app; a notification shows progress.
5. When it is done, check the slides marked **Check** or **No number**, then tap **Save to Downloads** or **Share**.

## 5. Please run the self-test once

Settings (gear icon) → **Self-test** → **Run self-test**. It runs the built-in test papers on your phone (no key used)
and shows PASS / FAIL. Tap **Copy report** and send the text (or a screenshot) to whoever maintains the app.

## Good to know

- Typed (digital) PDFs and many scans need **no Gemini at all**: the phone reads them itself, offline.
- Several keys work at the same time, two pages per request each. Keys from the **same Google project share one daily limit**.
- The free daily limit resets at **midnight Pacific time** (about 12:30 – 1:30 pm in India).
- On the free tier Google may use the page images to improve its products. Do not send papers you may not share.
- Long papers make the phone warm and use battery; the app slows itself down when the phone gets hot.
