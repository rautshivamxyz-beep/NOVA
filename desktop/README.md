# NOVA Desktop — Windows (offline)

NOVA Desktop is the Windows desktop companion to the NOVA Android project. It runs a local GGUF model on your PC using llama.cpp. The chat interface, model process, and chat storage stay on the same computer.

## One-time setup

1. Download the **NOVA-Desktop-Windows-CUDA** artifact from the successful run of the [NOVA Desktop Windows workflow](https://github.com/rautshivamxyz-beep/NOVA/actions/workflows/nova-desktop-windows.yml).
2. Extract the ZIP to a normal folder, for example `C:\\NOVA Desktop`. Do not run it from inside the ZIP.
3. Put one `.gguf` model file in the `models` folder.
   - You can copy a model from your phone over USB, or download a compatible GGUF model separately.
   - The desktop build does not bundle a model because model size and licensing vary.
4. Double-click `start-nova-windows.bat`.

The browser opens automatically at `http://127.0.0.1:8080` after the local model reports ready. The separate **NOVA Engine** window must remain open while chatting. Close that window to stop inference.

## Offline and privacy design

- No cloud model providers, external inference APIs, API keys, accounts, or sign-in.
- No telemetry or analytics are added by this desktop launcher.
- The model runs locally from the GGUF file on your PC.
- The UI talks to the local engine over loopback at `127.0.0.1`; the engine is not bound to your LAN or public network.
- Chat history and settings are stored in this computer's browser storage.
- After the desktop package and model have been obtained, chatting does not require an internet connection.

The local loopback connection is only the internal UI-to-engine transport. It does not send prompts to an online service.

## GPU and performance

This package builds llama.cpp with an NVIDIA CUDA backend for RTX 30-series-class Ampere hardware (including the RTX 3050 Laptop GPU, compute capability 8.6) and includes the CPU backend as a fallback. A compatible NVIDIA display driver is required; the CUDA Toolkit does not need to be installed on your PC.

The launcher requests GPU offload with `-ngl 99`. llama.cpp will offload as many layers as fit in available VRAM. Large models may still need some layers in system RAM. Start with a quantized model that fits your 6 GB GPU memory and 16 GB system RAM; the model's size, context length, and other running applications affect the actual fit.

The default context is 4096 tokens. To change it, edit `-c 4096` in `start-nova-windows.bat`. To force CPU inference, change `-ngl 99` to `-ngl 0`.

## Create a desktop shortcut

Double-click `create-desktop-shortcut.bat` once. It creates a **NOVA Desktop** shortcut on your Windows desktop. After that, launch NOVA from the shortcut.

## Included features

- Streaming local chat
- Markdown and code rendering
- Multiple conversations
- Local chat history
- System prompt, temperature, and answer-length settings
- Stop generation and regenerate last answer

The Windows package reuses the desktop interface in `nova-web\\`. It does not replace or modify the Android application.

## Troubleshooting

- **No model found:** Place a `.gguf` file in `models\\` and launch again.
- **NOVA does not become ready:** Read the error in the NOVA Engine window. The model may be incompatible, damaged, or too large for available memory.
- **CUDA error:** Update your NVIDIA display driver. To test CPU mode, change `-ngl 99` to `-ngl 0`.
- **Port 8080 is in use:** Close the other process using port 8080 or change the port in the launcher and browser URL.
- **Browser does not open:** Open `http://127.0.0.1:8080` manually while the engine window is running.

## Build provenance

The GitHub Actions workflow builds the Windows engine from the upstream llama.cpp `v1.0.0` source tag, then packages it with the NOVA desktop UI and launch scripts. The GGUF model is intentionally not included.
