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

### Can I run an 8B model for free?

Yes — but not free *and* always-on *and* fast. Pick two, and know which two
you picked.

**Always-on and free: Oracle.** The free Ampere machine has 12 GB of RAM, and a
7B at Q4 needs about 5 GB, so it fits. Get it with:

```
MODEL=7b bash setup-oracle.sh
```

To be exact about the name: that option used to be called `8b`, and it never was
one. It downloads **Qwen 2.5 7B**. The true 8B models are mostly gated behind a
Hugging Face login that a script cannot pass, so a 7B is what is actually
reachable — and the difference is smaller than the label suggested.

Be honest with yourself about the speed first: **a free Oracle machine has no
GPU.** Two CPU cores running an 8B gives you a few tokens a second — a short
paragraph can take half a minute. It works, it is free, it is not snappy. The
3B default is roughly two to three times quicker and is what I would run here.

**Fast and free: a GPU notebook.** Kaggle gives about **30 GPU-hours a week**,
free, on a real GPU (a P100 or two T4s), with sessions up to around 12 hours.
Colab's free tier hands you a T4 when capacity allows, with a shorter idle
timeout. On either of those an 8B is genuinely quick.

The catch is the same for both: **a notebook is not a server.** The session
ends, the machine is recycled, and your model goes with it. There is no
permanent address to point NOVA at unless you keep a session alive and tunnel
to it, which is fragile and against the spirit of those terms.

**So:**

| What you want | Where | What it costs you |
| --- | --- | --- |
| Up whenever you ask | Oracle Always Free | Slow — a few tokens a second |
| 8B at real speed | Kaggle (30 h/week) or Colab | It disconnects; not a permanent server |

### Running it on Oracle Cloud (free)

Oracle's Always Free tier gives you a server that is up on its own — free, with
no time limit.

> **Read this before you expose anything.** A password does not encrypt a
> connection. NOVA sends the password as a bearer token and the question as the
> body, so over plain `http` across the open internet both are readable by
> anything on the path, and the token can simply be replayed.
>
> **So NOVA refuses it.** Plain `http` to a public address is rejected and the
> answer is produced on the phone instead. Plain `http` to your *own* network —
> your Wi-Fi, or **Tailscale** — is allowed, because there it is your cable.
>
> The practical answer: **use Tailscale.** Install it on the server and the
> phone, sign both into the same account, and point NOVA at the Tailscale
> address (it starts with `100.`, which NOVA treats as your own network). It is
> free for personal use and only your devices can reach it. If you would rather
> stay on the public internet, put the server behind an HTTPS reverse proxy and
> use the `https://` address.

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

### Keep it from being swept up (do this one)

Oracle reclaims an Always Free instance when it looks idle for 7 days — CPU,
network **and** memory all under the threshold (20% in their current docs; it
has been raised over the years). A personal AI is idle almost all the time, so
yours will look exactly like that.

**Oracle's own fix is to convert your account to Pay As You Go.** Their notice
to affected users says it plainly: *"You can keep idle compute instances from
being stopped by converting your account to Pay As You Go (PAYG). With PAYG,
you will not be charged as long as your usage for all OCI resources remains
within the Always Free limits."*

So PAYG here is not "start paying". It is "prove you are a real account", and
your Always Free resources stay free. It asks for a card and a small
verification hold that comes back.

**And one trap while you are in there:** keep your total Ampere A1 use **within
2 OCPUs and 12 GB**. Oracle's FAQ is blunt about the alternative — if you
provision more A1 than an Always Free tenancy allows, *all* your A1 instances
are disabled and then deleted after 30 days. Some older guides quote 4 OCPUs
and 24 GB; that is not the current allowance, and following them can cost you
the machine.

Also: log into the account now and then. An unused account can be treated as
abandoned and suspended.

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
