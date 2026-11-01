#!/usr/bin/env bash
# ===== NOVA Desktop (Linux/macOS) launcher =====
cd "$(dirname "$0")" || exit 1

mkdir -p models

MODEL=""
for f in models/*.gguf; do
  [ -e "$f" ] || continue
  MODEL="$f"
  break
done

if [ -z "$MODEL" ]; then
  echo
  echo "NOVA Desktop needs a model file (.gguf) in the 'models' folder."
  echo
  echo "Download one with any browser, for example:"
  echo "  Qwen3 1.7B (recommended):"
  echo "    https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
  echo "  Qwen3 0.6B (smaller, for weak computers):"
  echo "    https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
  echo
  echo "Tip: you can also copy a .gguf file from your phone's NOVA"
  echo "models folder - they are the exact same files."
  echo
  exit 1
fi

# make the engine runnable (zip downloads can lose the +x bit)
chmod +x llama-server 2>/dev/null

# macOS: remove the download quarantine flag if present
if [ "$(uname)" = "Darwin" ]; then
  xattr -d com.apple.quarantine llama-server 2>/dev/null
  xattr -d com.apple.quarantine ./*.dylib 2>/dev/null
  xattr -dr com.apple.quarantine . 2>/dev/null
fi

echo "Starting NOVA Desktop..."
echo "Model: $MODEL"
echo "Chat will open at http://127.0.0.1:8080"
echo "Keep this window open while chatting. Press Ctrl+C to stop NOVA."
echo

# open the browser a moment later (engine needs a few seconds to load)
(
  sleep 3
  if command -v xdg-open >/dev/null 2>&1; then xdg-open http://127.0.0.1:8080
  else open http://127.0.0.1:8080 2>/dev/null
  fi
) &

exec ./llama-server -m "$MODEL" --alias NOVA --path web -c 4096 --port 8080 --host 127.0.0.1
