# Run a bigger model on your laptop, and use it from your phone

NOVA can answer from a model running on another machine on your own Wi-Fi — your
Lenovo LOQ, or a second phone. Nothing goes to a third party: the address is one
you type, on your own network.

Why bother: a 4 GB phone can hold about a 1.2B model. A laptop can hold an 8B or
a 14B. That is the single biggest quality jump available — and it costs the phone
nothing, because the laptop does the work.

---

## 1. On the laptop — start the server

Download **llama.cpp** (the Windows or Linux release from
`github.com/ggml-org/llama.cpp/releases`), then run `llama-server` with a model.

**Windows (PowerShell, from the folder you unzipped):**

```powershell
.\llama-server.exe -m C:\models\Qwen3-8B-Q4_K_M.gguf --host 0.0.0.0 --port 8080 -c 8192
```

**Linux:**

```bash
./llama-server -m ~/models/Qwen3-8B-Q4_K_M.gguf --host 0.0.0.0 --port 8080 -c 8192
```

- `--host 0.0.0.0` is what lets the phone reach it. Without it the server only
  listens on the laptop itself.
- `-c 8192` is the context size. Bigger = more memory.
- Any GGUF works. Qwen 3 8B, LFM 2.5 8B-A1B, Llama 3.1 8B — whatever fits.

Leave that window open. It prints a line when it is ready.

## 2. Find the laptop's address on the Wi-Fi

**Windows:** `ipconfig` → look for *IPv4 Address* under your Wi-Fi adapter, e.g.
`192.168.1.5`.

**Linux:** `hostname -I` or `ip addr`.

## 3. In NOVA

**Settings → Remote model**

- Turn **Use a model on my own network** on.
- **Address:** `http://192.168.1.5:8080` (your laptop's IP, port 8080).
- **Model name:** optional. Leave blank unless the server complains.

Ask something. NOVA uses the laptop. If the laptop is asleep or off the Wi-Fi,
NOVA quietly falls back to the model on the phone — you never get a dead chat.

---

## Two things worth knowing

**Both devices must be on the same Wi-Fi.** Guest networks and "client
isolation" block device-to-device traffic; if it does not connect, that is the
usual reason.

**Windows Firewall will ask.** Allow `llama-server` on *private* networks when
the prompt appears, or the phone cannot reach it.

## Making it come up on its own

So you do not have to start it by hand every time:

**Windows** — Task Scheduler → *Create Task* → trigger **At log on** → action:
start `llama-server.exe` with the arguments above.

**Linux** — a systemd unit with `ExecStart=` the same command and
`WantedBy=multi-user.target`.

## If you want a second phone to serve instead

The same thing works. Run the same `llama-server` build for Android (or any
OpenAI-compatible server) on the other phone, note its IP, and point NOVA at it.
"Bigger model" simply means "a machine that has more room than this one".

---

## What is sent, exactly

Your question, plus the notes and documents NOVA would have used to answer it —
to the address you typed. That is why this is your own machine and not a cloud
service: the whole point is that the text never leaves your network.

If you ever point it at a *cloud* address instead, that is a different promise,
and NOVA would need to say so plainly and take an API key. That is not built.
