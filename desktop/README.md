# NOVA Desktop — Native Windows (offline)

The supported Windows desktop target is a standalone **NOVA.exe** window installed through **NOVA-Setup.exe**. It does not use BlueStacks and does not open a browser tab.

## Install and add models

1. Download the **NOVA-Windows-Installer** artifact from a successful run of the [Windows build workflow](https://github.com/rautshivamxyz-beep/NOVA/actions/workflows/nova-desktop-windows.yml).
2. Run `NOVA-Setup.exe` and complete the per-user installation.
3. Launch NOVA from the desktop shortcut or Start Menu.
4. Click **Open models folder**, copy one or more compatible `.gguf` files into it, and click **Refresh**.
5. Select a model and click **Load model**.

The models folder is inside the user's NOVA installation directory, so it is writable without administrator permissions. Keep a backup of model files before uninstalling NOVA.

The Microsoft WebView2 Runtime is required for the embedded desktop view. It is normally installed on current Windows 10/11 systems. Install it once if Windows reports that it is missing.

## Offline and privacy

- No API keys, cloud-provider options, accounts, sign-in, telemetry, or remote inference.
- Local GGUF inference through llama.cpp.
- The engine binds only to `127.0.0.1`; the embedded window blocks navigation to any other host.
- The app stops the inference process when its window closes.
- Once the app and model are installed, chat works without internet.

## GPU

The package includes an NVIDIA CUDA backend targeting Ampere compute capability 8.6, including RTX 3050 Laptop GPUs. NOVA requests GPU layer offload. Models larger than available VRAM can use system RAM for remaining layers.

## Logs

Startup and model errors are written to `nova-engine.log` beside NOVA.exe.


## In-app updates

Use **Check for updates** in the native NOVA toolbar. NOVA checks the project's GitHub Releases page and can download and run the signed-by-transport HTTPS installer when a newer version is published. Updates require an internet connection; local model inference remains offline. Chats/settings and GGUF models are kept outside the install directory.
