# NOVA — local AI assistant for Android

NOVA is a fully offline AI chat app. It runs Llama-family language models
**directly on your phone** using [llama.cpp](https://github.com/ggml-org/llama.cpp)
via the official Android binding (`llama-release.aar`, `com.arm.aichat`).

- **Any GGUF model** — download from the built-in catalog or paste any
  Hugging Face GGUF URL, or import a `.gguf` file you already have
- **Any device** — the Models screen checks your RAM and shows which models
  fit (✓ comfortable / △ tight / ✗ too big)
- **100% offline** — inference, chat history and models all stay on-device
- No account, no API key, no data leaves the phone

The APK is tiny (~25 MB); models are downloaded in-app and stored in the
app's private storage, so you only download what your device can run.

---

## What's inside

```
android/
├── app/src/main/java/org/nova/
│   ├── MainActivity.kt       Chat: streaming replies, stop, new chat,
│   │                         system prompt + response length settings
│   ├── ModelsActivity.kt     Model manager: catalog, custom URL download,
│   │                         local import, load/delete
│   ├── NovaEngine.kt         Wrapper around the com.arm.aichat engine
│   │                         (load/unload/reload, state handling)
│   ├── ModelCatalog.kt       Curated GGUF list + models directory
│   ├── ModelDownloader.kt    Resumable downloader + file import
│   ├── DeviceCapabilities.kt RAM probing + model fit heuristics
│   └── Settings.kt           System prompt / response length / last model
├── app/libs/llama-release.aar  llama.cpp Android binding (see below)
└── .github/workflows/nova-apk.yml  CI that builds the APK
```

## Building the APK

### Option A — GitHub Actions (easiest)

Push this project to your repo. The included workflow
(`.github/workflows/nova-apk.yml`) builds the APK on every push to `main`
(or via *Run workflow*). Download the `NOVA-APK` artifact from the run
page and sideload it on your phone.

### Option B — On-device with Termux

You already have the SDK in Termux, so from the repo root:

```bash
cd android
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
```

If Gradle can't run the wrapper, use your installed Gradle directly
(`gradle assembleDebug`) like the CI does.

### Option C — Android Studio

Open the `android/` folder, let it sync, and build. If `local.properties`
points at a Termux path, delete it first (Android Studio writes its own).

Requirements: JDK 17, Android SDK 35, min Android 11 (API 30) on the phone.

---

## Which model should I use?

The Models screen marks each catalog entry for **your** device, but as a
rule of thumb (Q4_K_M quantization, the default recommendation):

| Device RAM | Good models | File size |
|---|---|---|
| 2–3 GB (budget phones) | Llama 3.2 1B | ~0.8 GB |
| 3–4 GB | SmolLM2 1.7B, Gemma 2 2B | 1–1.6 GB |
| 4–6 GB | Llama 3.2 3B, Qwen 2.5 3B, Phi 3.5 | 1.8–2.3 GB |
| 6–8 GB (flagships) | Llama 3.1 8B, Qwen 2.5 7B, Mistral 7B | 4–5 GB |
| 12 GB+ / tablets | Qwen 2.5 14B | ~8.5 GB |

A model needs roughly **1.3–1.4× its file size in free RAM** once loaded
(weights + KV cache + runtime), and Android keeps 2–3 GB for itself —
that's what NOVA's fit checks account for.

70B-class models (~40 GB even at Q4) are desktop/server territory only —
they cannot run on phones, which is why NOVA caps its catalog at 14B and
still lets you try any GGUF at your own risk via custom URL/import.

**Quantization tip:** if a Q4_K_M model is too big for your device, the
same HF repo usually offers smaller quants (Q3_K_M, Q2_K) — copy that
file's URL into NOVA's custom URL box. Or pick `IQ4_XS`/`Q3` variants.

## Updating / rebuilding the llama.cpp binding

`android/app/libs/llama-release.aar` is the official Android binding built
from [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)
(`examples/llama.android`, the Arm AI-Chat library). To update it:

```bash
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp/examples/llama.android
./gradlew :lib:assembleRelease
cp lib/build/outputs/aar/lib-release.aar <NOVA>/android/app/libs/llama-release.aar
```

The API surface NOVA uses: `AiChat.getInferenceEngine(context)`,
`loadModel(path)`, `setSystemPrompt(...)`, `sendUserPrompt(...)` returning
a `Flow<String>`, `cleanUp()`, `destroy()`, and the engine `State` flow.

## How the chat works

- The native layer keeps the full conversation, so each message is sent
  with `sendUserPrompt` and tokens stream back one by one.
- **New chat** = reload the model (that's how the binding clears history).
- **Stop** (the ➤ button becomes ■ while generating) cancels token
  collection; the model stays loaded.
- The system prompt must be applied at model load, so changing it in
  settings offers a one-tap reload.

## Notes & limitations

- One model at a time (llama.cpp single-context design).
- First launch downloads take a while (1–9 GB) — resumable if interrupted.
- Model quality depends entirely on the GGUF you pick; NOVA adds nothing.
- Respect each model's license (Llama = Meta license, Qwen = Apache-2.0,
  Gemma = Google's license, etc.).
- llama.cpp is MIT-licensed; this app is your own project code.

## Old experiments

`main.py` / `nova.py` / `main.kv` / `buildozer.spec` were the earlier
Kivy-based prototype. The native Android app in `android/` replaces them.
