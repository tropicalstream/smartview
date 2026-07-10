# SmartView

**SmartView is a voice-first web browser for AR smart glasses**, built for the RayNeo X3 Pro. It replaces the usual pinch-to-scroll, on-screen-keyboard browsing experience with edge scrolling, a hands-free voice assistant, and an AI page agent that can carry out tasks on any web page for you.

---

## Features

- **No scrollbars.** Scrolling is done by parking the cursor at the top or bottom edge of the page.
- **Edge gestures.** Pull the left edge to go back a page. Pull the right edge to dim the display to black while audio keeps playing (handy for listening to a video without the screen lit up).
- **Voice control.** Double-tap to give a spoken command:
  - `"search <keyword> on duckduckgo"` / `"...on google"` — search the web
  - `"bookmark this web page"` — save the current page with automatically derived keywords
  - `"delete <keyword> bookmark"` — remove a saved bookmark
  - `"open <keyword>"` — jump to a bookmarked page by voice
  - `"open bookmarks"` — open the bookmark & settings manager
  - `"refresh"` — reload the page
  - Anything else is handed to the AI page agent as a task.
- **AI page agent.** Powered by [page-agent](https://alibaba.github.io/page-agent/), SmartView can carry out multi-step tasks on the current page ("click the first result", "find the contact email and read it to me") using your own LLM API key. The agent's replies are read aloud, and it will ask follow-up questions by voice when it needs more input.
- **Triple-tap** opens the bookmark & settings page, which also shows the current time, date, battery, and network status.
- **On-screen keyboard** with a built-in microphone key for voice-to-text dictation, for the rare cases you need to type.
- **Switchable AI providers.** Voice transcription and speech are powered by Groq; the page agent's language model is configurable (Google Gemini, Groq, or Cerebras) so you can pick based on your own free-tier limits and preferences.

All AI features run on your own API keys, entered directly on the device — nothing is bundled or hardcoded.

---

## Building

Requirements: Android Studio / Android SDK, JDK 17. Toolchain: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, compileSdk 35, minSdk 29.

1. Clone the repo.
2. Create a `local.properties` with your SDK path, e.g. `sdk.dir=/path/to/Android/sdk`.
3. Build a debug APK:

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

A prebuilt debug APK is attached to the GitHub release for quick sideloading.

### API keys

SmartView needs no keys to build or install. After launching the app, open **bookmarks/settings** (triple-tap) and enter:

- A **Groq** API key ([console.groq.com](https://console.groq.com)) — powers voice transcription and speech.
- An API key for whichever **page agent provider** you choose (Google Gemini, Groq, or Cerebras all offer free tiers).

Keys are stored locally on the device only.

---

## Credits

- **[page-agent](https://alibaba.github.io/page-agent/)** — the AI web-page automation engine.
- **[Groq](https://groq.com/)** — speech transcription and text-to-speech.

SmartView is a personal, non-commercial project.
