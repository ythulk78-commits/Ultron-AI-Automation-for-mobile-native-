# ULTRON — AI Automation for Mobile (Native Android)

**An on-device, voice-and-text driven AI assistant for Android that doesn't just *talk* — it actually operates your phone.**

Ultron listens for a wake word, understands commands in **Hinglish or English**, and then drives real apps and system settings on your behalf: sends a WhatsApp message, searches YouTube, toggles DND, places a call, schedules an action for later, or figures out a multi-step task inside an app it has never seen before. The same command set is also reachable remotely from a **paired Telegram chat**.

> Native Android · Java · Zero-backend (no server of your own) · Accessibility-driven automation · LLM only as a fallback

**Developed by ANSHIK SINGH & ADITYA TRIPATHI**

---

## Table of Contents

- [What makes it different](#what-makes-it-different)
- [Feature overview](#feature-overview)
- [How a command flows through the app](#how-a-command-flows-through-the-app)
- [Project structure](#project-structure)
- [Module-by-module breakdown](#module-by-module-breakdown)
- [Tech stack](#tech-stack)
- [Build & install](#build--install)
- [First-run setup](#first-run-setup)
- [Telegram Bridge setup](#telegram-bridge-setup)
- [Command cheat sheet](#command-cheat-sheet)
- [Permissions & why each is needed](#permissions--why-each-is-needed)
- [Security & privacy model](#security--privacy-model)
- [Design decisions](#design-decisions)
- [Known limitations](#known-limitations)
- [Credits](#credits)

---

## What makes it different

Most "AI assistant" apps are a chat box with an API key behind it. Ultron is built the other way around — **execution first, LLM last**:

| | Typical LLM assistant app | Ultron |
|---|---|---|
| Every command | Hits the network | Regex parser answers most commands **offline, instantly** |
| Can it tap/type in other apps? | No | Yes — Android AccessibilityService |
| Unknown task in an unknown app | Fails or just replies with text | **Agentic loop**: dumps the live screen, asks the LLM which element to tap, acts, repeats |
| Voice vs. typing | Two different code paths | **One** shared `UltronCore.handle()` — identical behaviour and shared context |
| Remote control | — | Paired Telegram chat, same pipeline |
| API key storage | Plain SharedPreferences | `EncryptedSharedPreferences` (AES-256-GCM) |

---

## Feature overview

### 🎙️ Three ways to wake it
- **Hotword** — say *"Ultron"*, *"Mark 9"* (also matches common STT mishearings like *altron / ultran / mark nine*). A lightweight RMS-based VAD gate runs continuously, and only when actual speech energy is detected does it spend a short `SpeechRecognizer` pass — so the mic indicator stays calm and the battery survives.
- **Triple-press Volume Up** — works even with the screen locked.
- **Shake the phone** — accelerometer trigger.

### 🖥️ Full-screen HUD
`UltronHudActivity` opens with a native **circular-reveal** animation from the exact centre of the screen, showing an animated "arc reactor" style ring graphic drawn in pure `Canvas` (`HudRingsView` — no bitmaps or GIFs, so it opens with zero asset-loading latency) plus a **live conversation log**.

### ⌨️ Type it or say it — same pipeline
The HUD has a text input row. A typed sentence runs through the *exact* same `CommandParser → HybridCommandRouter → ActionExecutor` chain as a spoken one, because both go through the single `UltronCore` singleton. Anything Ultron can do by voice, it can do by typing.

### 🔁 Continuous conversation mode
After waking, the mic stays open turn-after-turn. TTS completion is awaited before re-opening the mic so Ultron never hears itself. Saying *"stop listening"* exits immediately.

### 📱 App & system control
- Open any of **80+ mapped apps** by spoken alias (`AppPackageMap`)
- Volume (set %/up/down), brightness %, screen lock, torch, Wi-Fi, Bluetooth, DND, ringer mode, battery level

### 🤖 Real app automation (accessibility-driven)
Bespoke flows for **WhatsApp** (send to a contact), **Chrome** (search), **Instagram** (profile search, DM, open DMs), **Telegram** (send), **YouTube** (search), **YouTube Music** / **Spotify** (play), **Notes** (create), **Flipkart / Amazon / Swiggy** (search), plus a `genericAppSearch()` best-effort fallback for apps without a bespoke flow. Calls and navigation go through real intents for reliability.

### 🧠 Hybrid brain (rules → LLM)
`HybridCommandRouter` tries, in order: custom commands → scheduled-action phrasing → regex rules → **only then** the OpenRouter LLM. Most commands never touch the network. The LLM call itself is tuned small and deterministic (`temperature=0.2`, `max_tokens=300`) and returns JSON intent, not prose.

### 🪄 Agentic executor (the interesting part)
For goals no hardcoded flow covers — *"instagram ke DM section me jao, aditya ko search karo, hii bhejo"* — `AgenticExecutor` runs a perceive→decide→act loop:

```
collect interactable nodes on screen  →  describe them as an indexed list
                ↓
    ask the LLM: which index, and what action? (click / type / scroll / open_app / done)
                ↓
         perform it via AccessibilityNavigator
                ↓
   screen-signature hash detects "stuck" screens  →  loop until done or step cap
```

### ⏰ Scheduling & custom commands
- *"5 minute baad volume 0 karo"* → `Scheduler` arms an `AlarmManager` alarm (falls back to an inexact alarm if `SCHEDULE_EXACT_ALARM` isn't granted, so it never hard-fails)
- Named macros like *"night mode on karo"* → a saved list of steps (`CustomCommandManager`, JSON-persisted, seeded with defaults)

### 💬 Multi-step chaining
One sentence can chain actions across apps — *"gaana bajao phir whatsapp pe maa ko bata do busy hoon"* becomes `MULTI_STEP` with ordered `subCommands` run in sequence.

### 📨 Telegram Bridge (remote control)
A long-polling foreground service lets you command Ultron from Telegram, through the same `UltronCore.handle()`. Locked behind a device-generated PIN with a one-chat permanent binding and brute-force rate limiting (details below).

### 🔋 Survivability
Foreground service with notification, `BOOT_COMPLETED` auto-restart, battery-optimization-exemption helper, and an OEM autostart shortcut for ColorOS/MIUI-style ROMs.

---

## How a command flows through the app

```
┌──────────────┐   ┌──────────────┐   ┌──────────────────┐   ┌──────────────┐
│  Hotword /   │   │   HUD text   │   │  Telegram paired │   │  Scheduled   │
│ Volume/Shake │   │    input     │   │       chat       │   │    alarm     │
└──────┬───────┘   └──────┬───────┘   └────────┬─────────┘   └──────┬───────┘
       │                  │                    │                    │
       └──────────────────┴────────────────────┴────────────────────┘
                                   │
                          ┌────────▼─────────┐
                          │   UltronCore     │  one singleton, one code path
                          │    .handle()     │  posts both sides to ConversationBus
                          └────────┬─────────┘
                                   │
                        ┌──────────▼───────────┐
                        │ HybridCommandRouter  │
                        └──────────┬───────────┘
            ┌──────────────┬───────┴───────┬──────────────────┐
            ▼              ▼               ▼                  ▼
   CustomCommandManager  Scheduler    CommandParser     LlmIntentParser
     (named macro)      (time phrase)  (regex, offline)  (OpenRouter JSON)
            └──────────────┴───────┬───────┴──────────────────┘
                                   │  UltronCommand {type, action, params}
                          ┌────────▼─────────┐
                          │  ActionExecutor  │
                          └────────┬─────────┘
       ┌───────────────┬───────────┼────────────┬───────────────┐
       ▼               ▼           ▼            ▼               ▼
  OPEN_APP      SYSTEM_CONTROL  AUTOMATION  AGENTIC_TASK   MULTI_STEP
  (intent)    SystemController  AppAutomation AgenticExecutor (sequential)
                                     Manager       │
                                        └──────────┴──► AccessibilityNavigator
                                                        (click / type / scroll / back)
                                   │
                          ┌────────▼─────────┐
                          │ ContextMemory +  │  3-turn memory, spoken reply,
                          │ TTSManager + Bus │  unified transcript
                          └──────────────────┘
```

---

## Project structure

```
Ultron-AI-Automation-for-mobile-native-/
├── README.md                     ← you are here
├── PROJECT_NOTES.md              ← original phase-by-phase build notes
├── .gitignore
├── build.gradle                  ← root build script (AGP 8.12.0)
├── settings.gradle               ← includes :app
├── gradle.properties             ← AndroidX, Jetifier, aapt2 override, JVM args
├── gradlew / gradlew.bat         ← Gradle wrapper scripts
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties ← Gradle 8.13
│
└── app/
    ├── build.gradle              ← module build: minSdk 29, target/compileSdk 35
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/ultron/assistant/     ← 39 classes, ~5,500 LOC
        │   │   │
        │   │   ├── ── Core & lifecycle ──
        │   │   ├── UltronApplication.java          app singleton holder
        │   │   ├── UltronCore.java                 THE shared brain (voice == text)
        │   │   ├── UltronService.java              foreground service, wake + continuous loop
        │   │   ├── BootReceiver.java               restart on BOOT_COMPLETED
        │   │   ├── MainActivity.java               launcher UI, permission granting
        │   │   ├── SettingsActivity.java           "Ultron Heart" — key, model, Telegram
        │   │   │
        │   │   ├── ── Wake & speech ──
        │   │   ├── HotwordDetector.java            VAD gate + "Ultron"/"Mark 9" matching
        │   │   ├── VolumeKeyDetector.java          triple-press Volume Up
        │   │   ├── ShakeDetector.java              accelerometer trigger
        │   │   ├── SpeechRecognizerHelper.java     STT session wrapper (hi-IN)
        │   │   ├── TTSManager.java                 speech out, with completion callback
        │   │   │
        │   │   ├── ── Understanding (NLU) ──
        │   │   ├── CommandParser.java              regex rules — offline, instant, free
        │   │   ├── HybridCommandRouter.java        rules → custom → schedule → LLM
        │   │   ├── LlmIntentParser.java            LLM text → UltronCommand JSON
        │   │   ├── OpenRouterClient.java           OkHttp client, HTTP error mapping
        │   │   ├── UltronCommand.java              parsed-command data holder + Type enum
        │   │   ├── ContextMemory.java              rolling 3-turn conversation context
        │   │   │
        │   │   ├── ── Execution ──
        │   │   ├── ActionExecutor.java             dispatch by UltronCommand.Type
        │   │   ├── SystemController.java           volume/brightness/lock/torch/wifi/BT/DND
        │   │   ├── AppAutomationManager.java       bespoke per-app flows (largest class)
        │   │   ├── AgenticExecutor.java            screen-dump → LLM → act → repeat
        │   │   ├── AccessibilityNavigator.java     find / click / type / scroll / back
        │   │   ├── UltronAccessibilityService.java the AccessibilityService itself
        │   │   ├── AppPackageMap.java              80+ spoken alias → package name
        │   │   ├── ContactsHelper.java             name → phone number lookup
        │   │   ├── DeviceAdminReceiver.java        enables lockScreen()
        │   │   │
        │   │   ├── ── Automation extras ──
        │   │   ├── CustomCommandManager.java       named macros, JSON-persisted
        │   │   ├── Scheduler.java                  "X minute baad ..." → AlarmManager
        │   │   ├── ScheduledActionReceiver.java    fires the scheduled command
        │   │   ├── BatteryOptimizationHelper.java  exemption + OEM autostart helper
        │   │   │
        │   │   ├── ── UI / HUD ──
        │   │   ├── UltronHudActivity.java          full-screen circular-reveal HUD
        │   │   ├── HudRingsView.java               animated arc-reactor rings (Canvas)
        │   │   ├── OverlayManager.java             lightweight listening overlay fallback
        │   │   ├── ConversationBus.java            app-wide transcript pub/sub (last 50)
        │   │   ├── ListeningStateBus.java          mic-state pub/sub for UI
        │   │   │
        │   │   ├── ── Remote (Telegram) ──
        │   │   ├── TelegramBridgeService.java      long-poll service → UltronCore
        │   │   ├── TelegramApiClient.java          getUpdates / sendMessage
        │   │   ├── TelegramAuthManager.java        PIN generation, binding, rate limiting
        │   │   └── SettingsManager.java            EncryptedSharedPreferences store
        │   │
        │   └── res/
        │       ├── layout/
        │       │   ├── activity_main.xml           permissions + start/stop + Talk button
        │       │   ├── activity_hud.xml            rings + chat log + text input row
        │       │   ├── activity_settings.xml       Ultron Heart + Telegram Bridge
        │       │   └── overlay_listening.xml       small floating listening indicator
        │       ├── drawable/                       launcher foreground, pulse ring
        │       ├── mipmap-anydpi-v26/              adaptive launcher icons
        │       ├── values/                         strings, colors, styles (AppTheme, HudTheme)
        │       └── xml/
        │           ├── ultron_accessibility_service.xml
        │           └── device_admin.xml
        │
        ├── test/java/com/ultron/assistant/         JUnit example
        └── androidTest/java/com/ultron/assistant/  Espresso example
```

---

## Module-by-module breakdown

### Core

| File | LOC | Responsibility |
|---|---:|---|
| `UltronCore.java` | 74 | The single shared brain. Lazily built singleton holding one `HybridCommandRouter`, `ActionExecutor`, `ContextMemory` and `TTSManager`. **Every** entry point — hotword STT result, typed HUD message, Telegram message, scheduled action — calls `handle(text, speak, callback)`, so behaviour can never diverge between channels and all channels share one transcript and one memory. |
| `UltronService.java` | 276 | Foreground service (`microphone` type). Owns the three wake triggers, the continuous-listening loop, the "stop listening" escape phrase, and `pauseWakeWord()`/`resumeWakeWord()` so the hotword detector and the HUD's own recognizer never fight over the mic (Android lets only one app hold it cleanly). |
| `UltronApplication.java` | 19 | Application-context holder so singletons don't leak activities. |

### Understanding

| File | LOC | Responsibility |
|---|---:|---|
| `CommandParser.java` | 288 | ~30 compiled regex patterns covering open-app, volume, brightness, lock, torch, Wi-Fi, BT, DND, WhatsApp send, Chrome search, Instagram DM/search, Telegram send, YouTube search, YT Music/Spotify play, notes, call-by-number, call-by-name. Hinglish-aware (`karo`, `bhejo`, `bajao`, `baad`). Returns `UNKNOWN` to hand off upward. |
| `HybridCommandRouter.java` | 64 | The cost/latency gate: custom macro → scheduled phrase → regex → LLM. Steps 1–3 are pure local string work. |
| `LlmIntentParser.java` | 180 | Prompts the model for strict JSON, strips code fences, tolerates missing fields, maps to `UltronCommand` (including `MULTI_STEP` and `AGENTIC_TASK`). Falls back to `CHAT` so a parse failure still produces a sane reply. |
| `OpenRouterClient.java` | 149 | OkHttp + Gson. `temperature=0.2`, `max_tokens=300`. Maps HTTP errors to spoken messages — a 429 becomes *"API limit aa gayi"*, not a crash. |
| `ContextMemory.java` | 51 | Rolling 3-turn deque rendered into the system prompt, so *"usko bhi bhej do"* resolves. |

### Execution

| File | LOC | Responsibility |
|---|---:|---|
| `ActionExecutor.java` | 335 | Dispatches on `UltronCommand.Type`; sequential runners for `MULTI_STEP` and custom-macro steps; `describeAsGoal()` converts a failed bespoke flow into an agentic goal. |
| `AppAutomationManager.java` | 537 | The per-app flow engine. A tiny internal `Step` DSL (`click` / `type` / `typeAndSubmit` / `typeAndSave`) plus a `fallback(viewId, text, contentDesc)` selector strategy, so a flow survives one selector changing between app versions. |
| `AgenticExecutor.java` | 273 | The perceive→decide→act loop with element indexing, screen-signature stuck detection, step cap and bounded delays. |
| `AccessibilityNavigator.java` | 258 | Node search (id / text / content-description with fallback), click with clickable-ancestor walk, `typeText`, IME submit, scroll, back/home/recents, `dumpVisibleTree()` and `collectInteractableNodes(max)` for the agent. |
| `SystemController.java` | 163 | Clamped percentage→native-range conversions, DeviceAdmin lock, camera-torch, Wi-Fi/BT/DND, battery read. |
| `AppPackageMap.java` | 183 | Normalised alias table (80+ apps) with fuzzy fallback resolution. |

### Remote & storage

| File | LOC | Responsibility |
|---|---:|---|
| `TelegramBridgeService.java` | 194 | Long-poll loop with offset tracking, `/start` · `/logout` · `/unbind` · `/reset` handling, foreground `dataSync` notification, silent drop of every unpaired chat. |
| `TelegramAuthManager.java` | 142 | 4-digit PIN, 5-minute validity, one-time use, **5 attempts / 10 min → 15 min cooldown** per chat. |
| `TelegramApiClient.java` | 129 | `getUpdates` / `sendMessage` + update model. |
| `SettingsManager.java` | 116 | `EncryptedSharedPreferences` (AES-256-GCM master key) for the OpenRouter key, model id, bot token, bound chat id, bridge-enabled flag. |

---

## Tech stack

| Layer | Choice |
|---|---|
| Language | **Java** (no Kotlin) |
| Build | Gradle **8.13** wrapper · AGP **8.12.0** · Groovy DSL |
| SDK | `minSdk 29` (Android 10) · `compileSdk` / `targetSdk 35` |
| UI | AppCompat 1.2.0 · Material 1.2.1 · ConstraintLayout 2.0.4 · custom `Canvas` view |
| Crypto | `androidx.security:security-crypto` 1.1.0-alpha06 |
| Network | OkHttp 4.12.0 · Gson 2.10.1 |
| Speech | Android `SpeechRecognizer` (hi-IN) + `TextToSpeech` — **fully on-device, no cloud STT** |
| Automation | `AccessibilityService` + `DevicePolicyManager` + intents |
| AI | OpenRouter (any model id, free or paid) — **fallback only** |
| Tests | JUnit 4.13.2 · Espresso 3.3.0 (scaffolding) |

---

## Build & install

### Android Studio
```bash
git clone https://github.com/ythulk78-commits/Ultron-AI-Automation-for-mobile-native-.git
cd Ultron-AI-Automation-for-mobile-native-
```
Open the folder as a project, let Gradle sync, then **Run**.

### Command line
```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### AndroidIDE / AIDE (on-device building)
Open the project folder via **Open Project**, let it sync, then **Build → Run**. `gradle.properties` already carries the `android.aapt2FromMavenOverride` needed by on-device toolchains.

> `local.properties` is intentionally **not** committed — Android Studio generates it, or create it yourself with `sdk.dir=/path/to/android/sdk`.

---

## First-run setup

1. Install and open **ULTRON**.
2. On the main screen, grant in this order:
   **Overlay → Accessibility → Device Admin → Write Settings → Contacts + Call**
3. Tap **Ignore Battery Optimization**, then open App Info and enable **OEM Autostart** (ColorOS / MIUI style ROMs expose no public API for this — it has to be a manual toggle).
4. Tap **Ultron Heart (AI Brain Settings)** → paste your OpenRouter API key from [openrouter.ai/keys](https://openrouter.ai/keys) → type any model id (e.g. `meta-llama/llama-3.1-8b-instruct:free`) → **Save**.
5. Tap **Start Ultron**.
6. Wake it: say **"Ultron"** / **"Mark 9"**, triple-press **Volume Up**, shake the phone, or just tap **Talk to Ultron (open HUD now)**.

---

## Telegram Bridge setup

1. **Ultron Heart → Telegram Bridge** → paste your bot token from [@BotFather](https://t.me/BotFather) → **Save Token & Start Bridge**.
2. Tap **Generate Remote PIN** — a 4-digit code appears on *that screen only*, valid 5 minutes, single use.
3. In Telegram, open your bot, send `/start`, then send the PIN as a plain message.
4. The bot replies **"Access granted"** — that chat is now the *only* chat that can ever command Ultron, until you tap **Reset Pairing** (or send `/logout`).

---

## Command cheat sheet

Hinglish and English both work.

| Intent | Example |
|---|---|
| Open an app | *"Instagram kholo"* · *"open Spotify"* |
| Volume | *"volume 70 karo"* · *"volume up"* |
| Brightness | *"brightness 30 karo"* |
| System toggles | *"wifi on"* · *"bluetooth off"* · *"dnd on"* · *"torch on"* |
| Lock | *"phone lock karo"* |
| WhatsApp | *"WhatsApp pe Mummy ko bhejo main ghar aa raha hoon"* |
| Instagram | *"Instagram pe aditya search karo"* · *"Instagram DM me aditya ko hii bhejo"* |
| Search | *"YouTube pe lofi beats search karo"* · *"Flipkart pe headphones search karo"* |
| Play music | *"gaana bajao"* · *"Spotify pe Arijit Singh bajao"* |
| Call | *"Papa ko call karo"* · *"9876543210 pe call karo"* |
| Notes | *"note banao — milk lena hai"* |
| Schedule | *"5 minute baad volume 0 karo"* |
| Macro | *"night mode on karo"* |
| Chain | *"gaana bajao phir WhatsApp pe maa ko bata do busy hoon"* |
| Agentic | *"Instagram ke DM section me jao, aditya search karo, hii bhejo"* |
| Anything else | Falls through to the LLM and gets answered as chat |
| Stop | *"stop listening"* |

---

## Permissions & why each is needed

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Hotword VAD + speech recognition |
| `FOREGROUND_SERVICE` + `_MICROPHONE` | Keeps the listening service alive (Android 14+ typing) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Telegram Bridge long-poll service |
| `SYSTEM_ALERT_WINDOW` | Listening overlay + HUD over other apps |
| `WRITE_SETTINGS` | Brightness control |
| `BIND_ACCESSIBILITY_SERVICE` | The whole automation layer — reading the screen and tapping/typing |
| `BIND_DEVICE_ADMIN` | `lockScreen()` |
| `READ_CONTACTS` + `CALL_PHONE` | Call-by-name |
| `CAMERA` + `FLASHLIGHT` | Torch |
| `ACCESS_NOTIFICATION_POLICY` | DND toggle |
| `MODIFY_AUDIO_SETTINGS` | Volume / ringer |
| `ACCESS_WIFI_STATE` · `CHANGE_WIFI_STATE` · `BLUETOOTH` · `BLUETOOTH_ADMIN` | Radio toggles |
| `RECEIVE_BOOT_COMPLETED` | Auto-restart after reboot |
| `SCHEDULE_EXACT_ALARM` | Scheduled actions (gracefully degrades to inexact) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep the service from being killed |
| `QUERY_ALL_PACKAGES` | Resolve the 80+ app packages it automates — fine for a sideloaded personal APK, but a **Play Store policy no-go**; remove it before publishing |

---

## Security & privacy model

- **Speech never leaves the device for recognition.** STT and TTS both use Android's on-device engines.
- **No backend of ours.** The only outbound calls are to OpenRouter (on an LLM fallback) and to Telegram (if you enable the bridge). There is no telemetry and no analytics.
- **Secrets are encrypted at rest.** The OpenRouter key and Telegram bot token live in `EncryptedSharedPreferences` with an AES-256-GCM master key, not plain prefs.
- **Telegram pairing is device-anchored.** The PIN is generated by and displayed *only* on your phone — Ultron never transmits it. The first chat to send a correct, unexpired PIN is permanently bound; every other chat is silently ignored, so a stranger who finds your bot gets no reply at all to probe against. Wrong PINs are rate-limited (5 per 10 min → 15 min cooldown), making the 4-digit space impractical to brute-force.
- **No shell or root path was added.** The Telegram bridge reaches exactly the same automation surface as the HUD — nothing more.
- **Don't commit live credentials.** No token or key is in this repository, and `.gitignore` covers `local.properties` and keystores. Treat any build with your real token baked in as sensitive.
- **The accessibility grant is powerful.** It is what lets Ultron operate your apps. Install only builds you trust and have read.

---

## Design decisions

1. **One pipeline for every input channel.** Voice, typing and Telegram all funnel into `UltronCore.handle()`. One fast path to optimise, one correct path to maintain, one shared transcript.
2. **Rules before the model.** Regex is free, offline and sub-millisecond. The LLM is a *fallback*, so the common case has no network latency and no API cost.
3. **VAD gate before recognition.** Continuous `SpeechRecognizer` sessions are expensive and keep the mic indicator lit. Gating on audio RMS keeps idle cost low.
4. **Canvas over assets.** The HUD rings are drawn, not decoded — nothing to load, so the reveal animation starts instantly.
5. **Selector fallbacks, not resource IDs alone.** Third-party app internals shift between versions, so every automation step can match on view id *or* text *or* content description.
6. **Agentic as the backstop, not the default.** Bespoke flows are deterministic and fast; the LLM-driven loop only takes over when no flow exists or one fails.
7. **Degrade, never crash.** Missing exact-alarm permission → inexact alarm. Rate-limited API → spoken explanation. Failed bespoke flow → agentic retry.

---

## Known limitations

- Phase-5 UI-automation flows match on generic text/content-description rather than exact resource IDs, so a selector or two may need adjusting for your specific installed app versions.
- `genericAppSearch()` is best-effort, not a guarantee, for apps without a bespoke flow.
- Free OpenRouter models have tight rate limits — a 429 surfaces as *"API limit aa gayi"*; switch models in Ultron Heart if you hit it often.
- OEM autostart (ColorOS / MIUI) has no public API and must be toggled by hand.
- `QUERY_ALL_PACKAGES` must be removed before any Play Store submission.
- Aggressive battery managers on some ROMs can still kill the foreground service despite the exemption helper.

---

## Credits

**Developed by ANSHIK SINGH & ADITYA TRIPATHI**

Built as a native Android exploration of what a phone assistant can be when it is allowed to *act* rather than only answer — on-device speech, deterministic rules first, and an LLM reserved for the cases that genuinely need one.
