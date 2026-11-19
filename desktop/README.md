# NOVA Desktop — Native Windows (offline)

The supported Windows desktop target is a standalone **NOVA.exe** window. It does not use BlueStacks and does not open a browser tab.

## One-time setup
1. Download the `NOVA-Windows-Installer` artifact from a successful run of the [Windows build workflow](https://github.com/rautshivamxyz-beep/NOVA/actions/workflows/nova-desktop-windows.yml).
2. Run the installer. It installs per-user and does not require administrator privileges.
3. Place one compatible `.gguf` model in `%LOCALAPPDATA%\NOVA\models\`.
4. Launch NOVA and select the model to load.

The Microsoft WebView2 Runtime is required for the embedded desktop view. It is normally installed on current Windows 10/11 systems. Install it once if Windows reports that it is missing.

## Offline and privacy
- No API keys, cloud-provider options, accounts, sign-in, telemetry, or remote inference.
- Local GGUF inference through llama.cpp.
- The engine binds only to `127.0.0.1`; the embedded window blocks navigation to any other host.
- The app stops the inference process when its window closes.
- Once the package and model are present, chat works without internet. The optional manual update check uses GitHub.

## Model and logs
- Models: `%LOCALAPPDATA%\NOVA\models\`
- Engine diagnostics: `%LOCALAPPDATA%\NOVA\nova-engine.log`
- Legacy models beside NOVA.exe are copied to the persistent model folder without deleting the originals.

## GPU
The package includes an NVIDIA CUDA backend targeting Ampere compute capability 8.6, including RTX 3050 Laptop GPUs. NOVA requests GPU layer offload. Models larger than available VRAM can use system RAM for remaining layers.
