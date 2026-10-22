#!/usr/bin/env bash
# ===================================================================
#  NOVA model server - run this on your laptop.
#  It starts the model. Your phone finds the laptop by itself.
#  Keep this terminal open while you use NOVA. Ctrl-C to stop.
# ===================================================================
set -u
cd "$(dirname "$0")" || exit 1
PORT=8080

echo
echo "  NOVA model server"
echo "  -----------------"
echo

# ---- find llama-server ---------------------------------------------
SERVER=""
for c in ./llama-server ./llama/llama-server ./bin/llama-server; do
  [ -x "$c" ] && SERVER="$c" && break
done
if [ -z "$SERVER" ]; then
  SERVER="$(find . -maxdepth 3 -name llama-server -type f -perm -u+x 2>/dev/null | head -1)"
fi

if [ -z "$SERVER" ]; then
  echo "  I could not find llama-server in this folder."
  echo
  echo "  1) Download the build for your machine from:"
  echo "       https://github.com/ggml-org/llama.cpp/releases"
  echo "  2) Unzip it INTO THIS FOLDER, so 'llama-server' sits"
  echo "     right next to this script."
  echo "  3) Run this script again."
  echo
  exit 1
fi

# ---- find the model ------------------------------------------------
MODEL="$(find ./models . -maxdepth 2 -name '*.gguf' 2>/dev/null | head -1)"

if [ -z "$MODEL" ]; then
  echo "  I found the server, but no model file."
  echo
  echo "  A model is one big file ending in .gguf (about 2 to 5 GB)."
  echo "  Download one and put it in a folder called 'models' next to"
  echo "  this script, then run this script again."
  echo "    https://huggingface.co/models"
  echo
  exit 1
fi

echo "  Server : $SERVER"
echo "  Model  : $MODEL"
echo
echo "  Starting. Your phone can now find this laptop."
echo "  Leave this terminal OPEN while you use NOVA."
echo

"$SERVER" -m "$MODEL" --host 0.0.0.0 --port "$PORT" -c 8192
