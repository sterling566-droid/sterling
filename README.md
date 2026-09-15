# Sterling — native Android voice assistant

A floating glowing orb that listens for a wake word ("hey sterling" by
default), sends your question to an OpenRouter free-tier model, and speaks
the reply back — with tool-calling for web search, contacts lookup, and
file browsing.

## Why this is a full rewrite, not an update

The original version was built with Kivy + python-for-android. That stack
caused most of the real problems along the way: `pyjnius`'s dynamic
reflection calls threw an uncatchable crash on a mismatched method overload
(`TextView.setText`), python-for-android (a community-maintained project)
hadn't caught up with brand-new Android 17 restrictions, and the
Python+SDL2 runtime's memory footprint is large enough to plausibly trip
Android 17's new per-app memory limits.

This version is **native Kotlin** instead:

- Real typed Android API calls, checked at compile time — no more runtime
  reflection ambiguity like the `setText` crash.
- Uses Google's own current toolchain (Android Gradle Plugin), which stays
  current with new Android versions on its own — not dependent on a
  separate community project catching up.
- A much smaller runtime footprint than a Python+SDL2 app, which directly
  addresses the Android 17 memory-limit suspicion from before.
- Jetpack Compose UI — clipboard paste into text fields just works
  natively, no more fighting a flaky touch-based paste bubble.
- Native `ObjectAnimator`/`ScaleAnimation` for the bubble's pulse and glow
  — reliable, smooth, and genuinely easier to get right than doing the same
  thing through a reflection bridge.

## What it does

- `MainActivity.kt` — Compose settings screen: API key (with working
  paste/show), model, wake word, permission buttons, start/stop the
  service, and a crash-log viewer that reads crash files written by an
  uncaught-exception handler (no adb needed to see what went wrong).
- `SterlingService.kt` — the foreground service. Draws the floating orb
  (`WindowManager` overlay, pulsing via `ScaleAnimation`, colored by
  state), loops Android's native `SpeechRecognizer` for the wake word,
  calls OpenRouter (with tool-calling) via `OpenRouterClient.kt`, and
  speaks the reply with Android's built-in `TextToSpeech` engine (which
  commonly uses Google's own TTS engine when available, and is the
  API Android's newer background-audio rules expect apps to use, rather
  than a raw `MediaPlayer`).
- `OpenRouterClient.kt` — OpenAI-style chat client with a tool-calling
  loop (max 4 hops to avoid infinite tool loops), using OkHttp + org.json.
- `Tools.kt` — the three callable tools: `web_search` (scrapes DuckDuckGo's
  HTML results via Jsoup — no API key, but fragile if DuckDuckGo changes
  their markup), `search_contacts` (via `ContactsContract`), and
  `list_files`/`read_text_file` (restricted to inside shared storage as a
  path-traversal safety rail).

## 1. Get an OpenRouter API key + pick a free model

1. Sign up at https://openrouter.ai and create a key at
   https://openrouter.ai/keys
2. Browse free models at https://openrouter.ai/models?max_price=0 — any
   model ID ending in `:free` works. Free models and their limits change
   over time, so if one stops responding, swap in another from that page.
3. **Tool-calling reliability depends on the model.** Not every free model
   honors the `tools` parameter consistently — some ignore it and just
   answer from what they know. If tools never seem to fire, try a
   different `:free` model documented as supporting function/tool calling.

## 2. Build the APK

Same approach as before: push to GitHub, let Actions build it, download
the APK from the run's Artifacts. Nothing to install locally.

```bash
git init
git add .
git commit -m "Sterling native"
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
git branch -M main
git push -u origin main
```

Check the **Actions** tab on your repo — "Build Sterling APK (Native)"
should run noticeably faster than the old buildozer builds, since GitHub's
runners already have the Android SDK preinstalled (no SDK/NDK download).
When it finishes, download the `sterling-apk` artifact from that run.

## 3. First-run setup on the phone

Same permission dance as before, all manual by Android's own design:

1. Open the app, enter your OpenRouter key/model/wake word, tap **Save
   settings**.
2. Tap **"1. Grant mic, contacts + overlay permissions"** — mic/contacts/
   notification dialogs are automatic; you'll then be sent to a Settings
   screen for "Display over other apps" (a "special" permission Android
   never grants silently).
3. Tap **"2. Grant 'All files access'"** — another Settings-screen-only
   permission, needed for the file-browsing tool. Everything else works
   fine without it.
4. Tap **"3. Disable battery optimization for Sterling"**.
5. Tap **"4. Start Sterling"** — the glowing orb should appear with a
   persistent notification (required by Android for any foreground
   service).

Orb colors: blue = listening for wake word, green = heard it, orange =
thinking, purple = speaking, red = error. Drag it anywhere on screen.

## Known limitations (carried over, still true)

- The wake-word loop restarts Android's cloud `SpeechRecognizer`
  repeatedly rather than using a dedicated low-power offline wake-word
  engine — more battery/data use than a "real" always-on assistant, with
  brief gaps between recognizer sessions.
- `web_search` scrapes a public HTML page, not an official API — it can
  break if DuckDuckGo changes their results page markup.
- This is a debug build (unsigned), fine for personal sideloaded use.
- If you eventually hit a genuine Android-version-specific background
  restriction again (like the Android 17 memory limits that motivated this
  rewrite), that's an OS-level constraint, not necessarily a code bug —
  the in-app crash log viewer will show a real Kotlin exception if there
  is one; if it stays empty despite a crash, that points to an external
  OS kill outside the app's control.
