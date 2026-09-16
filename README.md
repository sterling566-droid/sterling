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

Two ways to talk to it:

- **Primary: just open the app.** No wake word needed - the moment Sterling
  opens (with microphone permission granted), it starts listening
  immediately and keeps a running conversation going: listen, answer,
  speak, listen again. This only uses the microphone while the app is
  visible on screen, which is both simpler and a much safer permission
  story than always-on background listening.
- **Advanced: the background orb.** A floating orb that sits over other
  apps and listens for a wake word ("hey sterling" by default) even when
  Sterling itself is closed. This needs the extra permissions (overlay,
  battery exemption, foreground service) and is the part most likely to
  run into OS-specific background restrictions on newer Android versions.

Files:

- `MainActivity.kt` — Compose UI: the in-app conversation orb (primary
  mode), plus settings (API key with working paste/show, model, wake word),
  permission buttons, background-orb start/stop, and a crash-log viewer.
- `SterlingService.kt` — the background foreground service for the
  advanced wake-word orb mode: `WindowManager` overlay, pulsing
  `ScaleAnimation`, `SpeechRecognizer` wake-word loop, Android's native
  `TextToSpeech` for replies.
- `OpenRouterClient.kt` — OpenAI-style chat client with a tool-calling
  loop (max 4 hops), using OkHttp + org.json.
- `Tools.kt` — `web_search` (DuckDuckGo HTML via Jsoup), `search_contacts`
  (`ContactsContract`), `list_files`/`read_text_file` (shared storage,
  restricted to inside that root as a path-traversal safety rail).

## Publishing to Play Store — what's actually involved

This can get the app most of the way there, but a few things need to
happen outside the code, on your end, and it's worth knowing them upfront
rather than assuming a build alone is submission-ready:

1. **A signed release build, not a debug one.** `app/build.gradle.kts`
   already has a `release` build type wired up to read signing
   credentials from `local.properties` (which is gitignored - never
   commit real secrets) or environment variables. To actually use it:
   ```bash
   keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias sterling
   ```
   Then add to `local.properties` (create it in the project root if it
   doesn't exist):
   ```properties
   RELEASE_STORE_FILE=/absolute/path/to/release-key.jks
   RELEASE_STORE_PASSWORD=your_keystore_password
   RELEASE_KEY_ALIAS=sterling
   RELEASE_KEY_PASSWORD=your_key_password
   ```
   Then `gradle assembleRelease` produces a signed APK. **Keep that
   keystore file and its passwords somewhere safe and backed up** - if
   you lose it, you can never publish an update to the same app listing
   again, only a brand new one.

2. **Permission scrutiny.** Google reviews apps requesting sensitive
   permissions more strictly than you might expect for a personal
   project. The background orb's permission set - continuous microphone
   use, contacts access, and especially "All files access"
   (`MANAGE_EXTERNAL_STORAGE`, which Google generally expects only from
   apps whose *core purpose* is file management) - is exactly the kind of
   thing that draws rejections or requires a written justification during
   review. Realistically: **the in-app no-wake-word mode alone (mic used
   only while visible, no contacts, no file access) would sail through
   review far more easily than shipping the full feature set.** Consider
   submitting a minimal version first and adding the advanced features
   later once the listing is established, rather than shipping everything
   at once.

3. **A privacy policy URL.** Required in Play Console for any app
   requesting microphone, contacts, or storage permissions - this needs
   to be a real hosted page describing what data the app accesses and
   why, not just a checkbox.

4. **Play Console account, listing assets, content rating
   questionnaire.** A one-time $25 developer registration fee, an app
   icon (already done - the adaptive icon in this project), screenshots,
   a short/full description, and answering Google's content rating
   questions. All standard Play Console steps that happen after you have
   a signed APK, not something a code change can shortcut.

## Known limitations (carried over from before)

- The background orb's wake-word loop restarts Android's cloud
  `SpeechRecognizer` repeatedly rather than using a dedicated low-power
  offline wake-word engine - more battery/data use than a "real" always-on
  assistant, with brief gaps between recognizer sessions. The in-app
  no-wake-word mode doesn't have this problem since it's always actively
  listening while the screen is open.
- `web_search` scrapes a public HTML page, not an official API - it can
  break if DuckDuckGo changes their results page markup.
- If a background restriction issue like the Android 17 memory limits
  that motivated the original rewrite comes up again, that's an OS-level
  constraint, not necessarily a code bug - the in-app crash log viewer
  will show a real Kotlin exception if there is one; if it stays empty
  despite a crash, that points to an external OS kill outside the app's
  control.

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

**For the primary in-app mode (recommended - try this first):**

1. Open the app, enter your OpenRouter API key, tap **Save settings**.
2. Tap **"Grant microphone access"** on the orb, allow the permission
   dialog.
3. That's it - it starts listening immediately. Say something and it'll
   answer out loud, then keep listening for your next question
   automatically. Tap **"Pause conversation"** any time to stop.

**For the advanced background wake-word orb (optional, more permissions,
more likely to hit OS-specific background restrictions):**

4. Tap **"1. Grant mic, contacts + overlay permissions"** — mic/contacts/
   notification dialogs are automatic; you'll then be sent to a Settings
   screen for "Display over other apps" (a "special" permission Android
   never grants silently).
5. Tap **"2. Grant 'All files access'"** — another Settings-screen-only
   permission, needed for the file-browsing tool. Everything else works
   fine without it.
6. Tap **"3. Disable battery optimization for Sterling"**.
7. Tap **"4. Start background orb (wake word)"** — the glowing orb
   should appear with a persistent notification (required by Android for
   any foreground service).

Orb colors (both modes): blue = idle/listening for wake word, green =
heard you / actively listening, orange = thinking, purple = speaking, red
= error. The background orb can be dragged anywhere on screen.

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
