# Make NOVA use your laptop's brain

Your phone is small. Your laptop is strong. This makes NOVA answer with the
laptop instead of the phone. Better answers, and the phone stays cool.

**Two steps. That's all.**

> **Not using a laptop at all?** Then skip straight to
> **[Running it on Oracle Cloud](#running-it-on-oracle-cloud-free)** below — it
> is a complete path on its own, and nothing in it needs a laptop.

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

## Using it when you're not at home

At home, that is all you need. Away from home, your phone cannot see your
laptop directly, so you need a way in. Two ways, easiest first:

**Tailscale (recommended, free for personal use).** Install it on the laptop
and on the phone, sign both into the same account. It gives your laptop a
private address that works from anywhere. Put that address into NOVA instead
(ask me and I will walk you through it). Only your own devices can reach it.

**A tunnel or port forward.** This makes the server reachable from the open
internet, so **it must have a password**:

1. Put a file called `password.txt` next to `start-nova-server.bat`, and type a
   password into it. The script picks it up automatically.
2. Type the same password into NOVA: **Settings -> Remote model -> Password**.

Without a password, anyone who finds the address can use your laptop's model.
With one, only you can.

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

**Private questions only is ON by default.** NOVA sends just the question you
typed. Your notes, your documents, the facts you asked it to remember, your
exam dates and your earlier conversation all stay on the phone.

On your own laptop you can turn it off for better answers — it is your machine.
**Leave it on for anything you do not own**, and especially for a free cloud
server.

If NOVA is not certain a prompt is clean, it does not send it at all: it answers
on the phone instead. It never guesses in that direction.

### Running it on Oracle Cloud (free)

Your laptop is the easy option. If you want a server that is up even when your
laptop is closed, Oracle's Always Free tier gives you one — free, with no time
limit.

**You do three things. The script does everything else.**

**1. Make the machine.** In the Oracle website: *Create Instance* → image
**Ubuntu** → shape **Ampere A1** (the ARM one — 2 CPUs and 12 GB is the free
allowance) → save the SSH key it offers you.

**2. Open the door.** Still in the website: your subnet's **Security List** →
*Add Ingress Rule* → Source `0.0.0.0/0`, IP Protocol **TCP**, Destination Port
**8080**. Without this the phone cannot reach it, and nothing else will tell you
that.

**3. Run the script.** Connect to the machine (the website has a *Cloud Shell*
button, so you do not even need a terminal app) and paste one line:

```
curl -fsSL https://raw.githubusercontent.com/rautshivamxyz-beep/NOVA/main/setup-oracle.sh | bash
```

It installs llama.cpp, downloads a model, **makes a password for you**, starts
the server, and sets it to come back after a reboot. When it finishes it prints
two lines — an address and a password. Type those into NOVA:

**Settings → Remote model →** switch ON → Address and Password. Leave **Private
questions only** ON. A cloud machine is not your machine.

It takes a while the first time (the model is about 2 GB, and it may have to
build llama.cpp — 15 to 30 minutes on a free machine). Leave it running.

**One warning worth knowing:** Oracle reclaims an Always Free instance when it
looks idle for 7 days — CPU, network and memory all under 20%. A personal AI is
idle almost all the time, so expect it to be stopped unless you keep it busy.
A laptop you own has no such rule.

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
