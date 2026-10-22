# Make NOVA use your laptop's brain

Your phone is small. Your laptop is strong. This makes NOVA answer with the
laptop instead of the phone. Better answers, and the phone stays cool.

**Two steps. That's all.**

---

## Step 1 — on the laptop

**Double-click `start-nova-server.bat`** (Windows) or run
`bash start-nova-server.sh` (Linux/Mac).

A black window opens and starts the model. **Leave it open.** Closing it stops
the model.

If it says it can't find something, it tells you exactly what to do and opens
the right download page for you. Follow the two lines it prints.

## Step 2 — on the phone

NOVA → **Settings → Remote model** → turn **Use a model on my own network** on.

**That's it.** NOVA looks around your Wi-Fi, finds the laptop, and fills the
address in by itself. You never type a number.

Ask something. The laptop answers.

---

## If it doesn't work

**NOVA says "Nothing found."**
The black window isn't running, or the phone is on a different Wi-Fi. Check both.

**It worked yesterday, not today.**
Tap **Find my computer** again. Your laptop's address can change when it
restarts, and this re-finds it.

**Nothing at all happens.**
Make sure the black window is still open on the laptop.

---

## Making the laptop do this by itself (since it's always on)

So you never double-click anything again:

**Windows** — press Start, type *Task Scheduler*, open it.
Create Task → **Triggers** tab → *At startup*. → **Actions** tab → *Start a
program* → point it at `start-nova-server.bat`. Save.

**Linux** — ask me and I'll write you the systemd unit; it's four lines.

---

## What leaves your phone

Your question, and the notes NOVA would have used to answer it — to your own
laptop, over your own Wi-Fi. Nothing goes to any company. That's the whole
point of doing it this way.

---

<details>
<summary><b>For the curious: what is actually happening</b></summary>

`start-nova-server.bat` runs **llama.cpp's `llama-server`** with a model of your
choosing, listening on port 8080. NOVA talks to it using the standard
OpenAI-style `/v1/chat/completions` protocol, streaming, so the answer appears
as it's written.

**Find my computer** probes `/v1/models` across your Wi-Fi's address range and
takes the first machine that answers like a model server.

If the laptop is asleep or off the Wi-Fi, NOVA falls back to the model on the
phone mid-sentence — you never get a dead chat.

**Want a bigger model?** Drop a larger `.gguf` into the `models` folder and
restart the script. An 8B or 14B runs well on a gaming laptop. If your laptop
has an NVIDIA GPU, use the CUDA build of llama.cpp — it's several times faster.

**Want the manual version?** It's just:

```
llama-server -m models/your-model.gguf --host 0.0.0.0 --port 8080 -c 8192
```

</details>
