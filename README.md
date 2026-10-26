# NOVA — Offline AI for Android

NOVA runs language models directly on your Android phone. Once a model is downloaded, chatting works offline. No account or API key is required, and your chats and models stay on your device.

**Repository:** https://github.com/rautshivamxyz-beep/NOVA

## Start here (no coding required)

### 1. Get the app

1. Open the [latest GitHub Actions builds](https://github.com/rautshivamxyz-beep/NOVA/actions/workflows/nova-apk.yml).
2. Open the newest successful run on the `main` branch.
3. Under **Artifacts**, download **NOVA-APK** and extract the ZIP if needed.
4. Open the APK on your Android phone and allow installation from that source if Android asks.

If there is no successful run or no APK artifact, open the [Actions page](https://github.com/rautshivamxyz-beep/NOVA/actions) and check the latest failed run's logs.

### 2. Download a model

1. Open NOVA and go to **Models**.
2. Choose a model marked as fitting your device. The app uses **✓** for comfortable, **△** for tight, and **✗** for too large.
3. Download the model and wait for it to finish.
4. Select **Load**, return to chat, and send a message.

Model files are large (often around 1 GB or more). Use Wi-Fi and make sure you have enough free storage. A download can be resumed if interrupted.

### 3. Choose a model that fits

Use the in-app RAM recommendation first; these rough guidelines are only a starting point.

| Phone RAM | Start with | Notes |
|---|---|---|
| 2–3 GB | A 1B-class GGUF | Best chance of fitting; keep other apps closed |
| 4 GB | A small 1B–2B GGUF | 3B models may be too tight once Android is running |
| 6–8 GB | A 2B–3B GGUF | Some 7B models may still be too large |
| 12 GB or more | Try 3B–7B first | Larger models use substantially more memory |

A smaller quantized model (for example, Q4 or Q3) usually needs less memory. Bigger models are not automatically better on a phone: if a model does not fit, NOVA may become slow or Android may close it.

## Build an APK on GitHub (recommended)

You do **not** need Termux or Android Studio to use GitHub Actions.

1. Open the [NOVA Actions workflow](https://github.com/rautshivamxyz-beep/NOVA/actions/workflows/nova-apk.yml).
2. Select **Run workflow** if that option is available, or push a commit to `main`.
3. Open the workflow run and wait for it to finish.
4. Download the **NOVA-APK** artifact from the successful run.

The artifact is a ZIP containing the build output; extract it before installing the APK. A successful build does not automatically install the app on your phone.

### Build locally (developers)

Requirements: JDK 17 and Android SDK 35. Open the `android/` directory in Android Studio, or run:

```bash
cd android
./gradlew assembleDebug
```

The debug APK should be at `android/app/build/outputs/apk/debug/app-debug.apk` relative to the repository root. Local builds need a working Android SDK setup.

## Common problems

- **The app says no model is loaded:** Open **Models**, download a compatible model, and tap **Load**.
- **Download is slow or interrupted:** Use Wi-Fi, check free storage, and retry the download.
- **Model fails to load or the app closes:** Choose a smaller model, close other apps, and retry.
- **The model is marked too large:** Do not force it on a low-memory phone; try a smaller model or quantization.
- **No APK artifact appears:** Check that the workflow run succeeded. Failed runs do not produce a usable APK.
- **GitHub build fails:** Open the failed run, select the failed job, and inspect the first actual error in the logs. Share that error when asking for help.

When reporting a problem, include your phone model, Android version, model filename/quantization, and the relevant build or app error. Do not post passwords, access tokens, or private keys.

## What NOVA contains

The native Android app lives in `android/`. Main source files are under `android/app/src/main/java/org/nova/`:

- `MainActivity.kt` — chat interface and response controls
- `ModelsActivity.kt` — model download, import, load, and delete
- `NovaEngine.kt` — inference engine lifecycle
- `ModelCatalog.kt` — model catalog and storage paths
- `ModelDownloader.kt` — downloads and local model import
- `DeviceCapabilities.kt` — device RAM checks
- `Settings.kt` — saved chat settings

The app uses the Android llama.cpp binding at `android/app/libs/llama-release.aar`.

## Useful links

- [Download/build workflow](https://github.com/rautshivamxyz-beep/NOVA/actions/workflows/nova-apk.yml)
- [All workflow runs](https://github.com/rautshivamxyz-beep/NOVA/actions)
- [Browse source code](https://github.com/rautshivamxyz-beep/NOVA/tree/main/android)
- [Report a bug or request a feature](https://github.com/rautshivamxyz-beep/NOVA/issues/new/choose)

## Developer notes

- The native Android app in `android/` is the current app. Root-level files such as `main.py`, `nova.py`, `main.kv`, and `buildozer.spec` are from an older Kivy prototype; they are not the native Android entry point.
- NOVA runs one local model at a time.
- Model quality, speed, licensing, and memory use depend on the selected GGUF model and device.
- The project uses llama.cpp and its Android binding. Check the relevant upstream and model licenses before redistributing builds or models.
