# NOVA Native Desktop for Windows

NOVA is a standalone Windows application. Install it with **NOVA-Setup.exe**, then launch NOVA from the desktop shortcut or Start Menu. It does not use BlueStacks or open a browser tab.

## Install and add models

1. Download **NOVA-Windows-Installer** from a successful run of the Windows desktop build workflow.
2. Run `NOVA-Setup.exe` and complete setup. Installation is per-user and does not require administrator access.
3. Launch NOVA from the desktop shortcut or Start Menu.
4. In the model picker, click **Open models folder**.
5. Copy one or more compatible `.gguf` files into that folder.
6. Return to NOVA, click **Refresh**, choose a model, and click **Load model**.

You can add more models later and choose a different one each time NOVA starts. Model files are stored separately in NOVA's install folder under your user profile; uninstalling NOVA removes the app, so back up any models you want to keep before uninstalling.

The Microsoft WebView2 Runtime is required to render the app window and is present on most current Windows 10/11 systems. If missing, install it once. The inference engine and model run locally.

## Offline guarantee

- No API-key fields, cloud provider settings, account, login, telemetry, or remote inference.
- Local GGUF inference through llama.cpp.
- Engine binds only to 127.0.0.1; the embedded window allows navigation only to that local address.
- The app stops the engine when its window closes.
- After installation and adding a model, chatting requires no internet.

## GPU

CUDA backend targets NVIDIA Ampere compute capability 8.6, including RTX 3050 Laptop GPUs. NOVA requests GPU layer offload; layers that do not fit in 6 GB VRAM can remain in system RAM.

Startup and model errors are recorded in `nova-engine.log` beside NOVA.exe.


## In-app updates

Use **Check for updates** in the native NOVA toolbar. NOVA checks the project's GitHub Releases page and can download and run the signed-by-transport HTTPS installer when a newer version is published. Updates require an internet connection; local model inference remains offline. Chats/settings and GGUF models are kept outside the install directory.
