# NOVA Desktop

NOVA on your computer. Same AI brain as the NOVA phone app (same llama.cpp
engine, same .gguf models), running fully offline on Windows, Linux or macOS.
Nothing leaves your machine - the chat runs between your browser and a local
engine process.

## Quick start

### Windows
1. Unzip `NOVA-Desktop-Windows.zip`.
2. Double-click `start-nova-windows.bat`.
   - First time: it creates a `models` folder and asks you to put a model in it.
3. Download a model (see below) into the `models` folder, then run the script
   again. Your browser opens NOVA at http://127.0.0.1:8080.
   - If Windows SmartScreen shows "Windows protected your PC", click
     "More info" then "Run anyway" (the file has no Microsoft signature -
     it is built by NOVA's own build server from open-source code).

### Linux / macOS
1. Unzip `NOVA-Desktop-Linux.zip` (or `NOVA-Desktop-macOS.zip`).
2. In a terminal: `bash start-nova-linux-mac.sh`
3. Put a `.gguf` model in the `models` folder first (see below).

Note for macOS: the build needs an Apple Silicon Mac (M1/M2/M3/M4 - any
Mac from late 2021 onward). On Intel Macs, use the Linux build in a
terminal or ask for a dedicated build.

## Getting a model

Any `.gguf` file works. Recommended (same ones the phone app uses):

| Model | Size | Good for |
|---|---|---|
| Qwen3 1.7B Q4_K_M | ~1.1 GB | best all-rounder: study help, coding, reasoning |
| Qwen3 0.6B Q4_K_M | ~0.4 GB | very fast even on old computers |
| MiniCPM5 2B Q4_K_M | ~1.6 GB | strong answers, good Hindi handling |

Download links (use any browser):
- https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf
- https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf
- https://huggingface.co/bartowski/MiniCPM5-2B-GGUF/resolve/main/MiniCPM5-2B-Q4_K_M.gguf

Shortcut if your phone already has NOVA models: connect the phone by USB and
copy the `.gguf` files from `Android/data/org.nova/files/models` into the
`models` folder here. Same files, no second download.

## What works here

- Full chat with streaming answers, markdown, code blocks
- Multiple chats, saved in your browser (stay on this computer)
- Settings: system prompt, temperature, answer length
- Fully offline, no account, no internet after the model download

## What stays on the phone

The study toolkit (Knowledge notes, photo OCR, quizzes, exam countdown,
reminders, spaced repetition) is part of the Android app. NOVA Desktop is
the chat brain; the phone app is the study companion.

## Advanced

- Speculative decoding (needs a second small model): edit the start script
  and add `-md models\qwen3-0.6b.gguf` to the llama-server line to speed up
  a bigger Qwen3 model.
- Different context size: change `-c 4096` in the start script.
- Any GGUF model from Hugging Face works - try 4B-8B models on computers
  with 8GB+ RAM. They are noticeably smarter than the phone-sized models.

## Troubleshooting

- "Page not reachable" in browser: the engine is still loading the model -
  wait a few seconds and refresh. The first load can take up to a minute.
- Very slow answers: use Qwen3 0.6B, or a computer with more RAM/cores.
- Port already in use: change `--port 8080` in the start script to another
  number (and open that address in the browser).
