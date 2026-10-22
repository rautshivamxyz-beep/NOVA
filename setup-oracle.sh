#!/usr/bin/env bash
# ===================================================================
#  NOVA on Oracle Cloud - the whole server side, in one command.
#
#  Paste this on your Oracle VM (the "Always Free" one):
#
#      curl -fsSL https://raw.githubusercontent.com/rautshivamxyz-beep/NOVA/main/setup-oracle.sh | bash
#
#  It installs llama.cpp, downloads a model, makes a password, and
#  starts everything at boot. At the end it prints the address and the
#  password to type into NOVA on your phone.
#
#  Nothing about you is sent anywhere by this script.
# ===================================================================
set -euo pipefail

PORT="${PORT:-8080}"
DIR="$HOME/nova-server"
# Swap this for any GGUF you like - a bigger model needs more RAM.
MODEL_URL="${MODEL_URL:-https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf}"
MODEL_FILE="$DIR/models/$(basename "$MODEL_URL")"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

say "NOVA server setup - this takes a few minutes"
mkdir -p "$DIR/models"
cd "$DIR"

# ---- 1. the basics -------------------------------------------------
if ! have curl || ! have git; then
  say "Installing the tools I need (curl, git, unzip, build tools)..."
  sudo apt-get update -y
  sudo apt-get install -y curl git unzip cmake build-essential
fi

# ---- 2. llama-server ----------------------------------------------
if [ -x "$DIR/llama-server" ]; then
  say "llama-server is already here - keeping it."
else
  say "Looking for a ready-made build for this machine..."
  URL="$(curl -sL --max-time 60 \
      https://api.github.com/repos/ggml-org/llama.cpp/releases/latest 2>/dev/null \
    | grep -oE '"browser_download_url": *"[^"]+"' \
    | sed 's/.*"\(https[^"]*\)"/\1/' \
    | grep -iE 'ubuntu|linux' \
    | grep -iE 'arm64|aarch64' \
    | grep -viE 'x64|amd64' \
    | head -1 || true)"

  if [ -n "$URL" ]; then
    say "Found one. Downloading..."
    curl -fL --max-time 600 -o llama.zip "$URL"
    unzip -oq llama.zip
    rm -f llama.zip
    FOUND="$(find "$DIR" -maxdepth 3 -name llama-server -type f | head -1 || true)"
    [ -n "$FOUND" ] && cp "$FOUND" "$DIR/llama-server" && chmod +x "$DIR/llama-server"
  fi

  if [ ! -x "$DIR/llama-server" ]; then
    say "No ready-made build worked, so I'll build it. This is the slow part -"
    say "on a free Oracle machine expect 15 to 30 minutes. Leave it running."
    rm -rf "$DIR/llama.cpp"
    git clone --depth 1 https://github.com/ggml-org/llama.cpp "$DIR/llama.cpp"
    cd "$DIR/llama.cpp"
    cmake -B build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF
    cmake --build build --config Release -j"$(nproc)" --target llama-server
    cp build/bin/llama-server "$DIR/llama-server"
    chmod +x "$DIR/llama-server"
    cd "$DIR"
  fi
fi
[ -x "$DIR/llama-server" ] || { say "Could not get llama-server. Stopping."; exit 1; }

# ---- 3. the model --------------------------------------------------
if [ -s "$MODEL_FILE" ]; then
  say "The model is already downloaded."
else
  say "Downloading the model (about 2 GB). This is the other slow part."
  curl -fL --progress-bar --max-time 3600 -o "$MODEL_FILE.part" "$MODEL_URL" \
    || { say "The download failed. Put any .gguf in $DIR/models and run this again."; rm -f "$MODEL_FILE.part"; exit 1; }
  mv "$MODEL_FILE.part" "$MODEL_FILE"
fi

# ---- 4. a password, because this machine is on the open internet ---
KEYFILE="$DIR/password.txt"
if [ ! -s "$KEYFILE" ]; then
  say "Making a password - a cloud server MUST have one."
  head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24 > "$KEYFILE"
  chmod 600 "$KEYFILE"
fi
KEY="$(cat "$KEYFILE")"

# ---- 5. start it now, and again at every boot ----------------------
say "Setting it up to start at boot..."
sudo tee /etc/systemd/system/nova-server.service >/dev/null <<UNIT
[Unit]
Description=NOVA model server
After=network-online.target

[Service]
User=$USER
ExecStart=$DIR/llama-server -m $MODEL_FILE --host 0.0.0.0 --port $PORT -c 8192 --api-key $KEY
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable nova-server >/dev/null 2>&1
sudo systemctl restart nova-server

# ---- 6. let the port through the machine's own firewall ------------
if have ufw && sudo ufw status 2>/dev/null | grep -qi active; then
  sudo ufw allow "$PORT"/tcp >/dev/null 2>&1 || true
fi
sudo iptables -I INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null || true

sleep 3
IP="$(curl -s --max-time 15 https://api.ipify.org || echo 'your server IP')"

say "Done."
cat <<DONE

  Address :  http://$IP:$PORT
  Password:  $KEY

  Type those two into NOVA on your phone:
      Settings -> Remote model -> switch ON
      Address  = the Address above
      Password = the Password above
      Leave "Private questions only" ON.

  ONE MORE THING, in the Oracle website (this script cannot do it):
      Your subnet's Security List -> Add Ingress Rule
      Source 0.0.0.0/0   IP Protocol TCP   Destination Port $PORT
  Without that, the phone cannot reach this machine.

  Check it any time:   systemctl status nova-server
  Watch the answers:   journalctl -u nova-server -f
DONE
