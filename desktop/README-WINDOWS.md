# NOVA Native Desktop for Windows

NOVA is a standalone Windows application. Double-click **NOVA.exe** to open its own desktop window. It does not use BlueStacks and does not open a browser tab.

## One-time setup
1. Download the **NOVA-Native-Windows** artifact from a successful run of the Windows build workflow.
2. Extract the ZIP to a normal folder.
3. Place one compatible `.gguf` model in the `models` folder.
4. Double-click `NOVA.exe`.

The Microsoft WebView2 Runtime is required to render the app window and is present on most current Windows 10/11 systems. If missing, install it once. The inference engine and model run locally.

## Offline guarantee
- No API-key fields, cloud provider settings, account, login, telemetry, or remote inference.
- Local GGUF inference through llama.cpp.
- Engine binds only to 127.0.0.1; the embedded window allows navigation only to that local address.
- The app stops the engine when its window closes.
- After the package and model are on the PC, chatting requires no internet.

## GPU
CUDA backend targets NVIDIA Ampere compute capability 8.6, including RTX 3050 Laptop GPUs. NOVA requests GPU layer offload; layers that do not fit in 6 GB VRAM can remain in system RAM.

Startup and model errors are recorded in `nova-engine.log` beside NOVA.exe.