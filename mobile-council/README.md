# NOVA AI Development Council — phone-first

A Termux-compatible Python sidecar for the NOVA Kotlin Android repository. The controller runs on your phone; model inference runs through provider APIs.

## Debate workflow
1. Configured providers independently propose solutions in parallel.
2. Each provider critiques the collected proposals.
3. A chair synthesizes consensus, unresolved disagreements, implementation steps, tests and rollback.
4. Reports are saved locally under .council/runs.
5. A configured provider can draft a unified diff for source files you explicitly supply.
6. Patches are checked with git apply --check and require an explicit --apply flag on a non-main branch.

No automatic merge, push or main-branch editing.

## Provider configuration
Gemini uses GEMINI_API_KEY. Claude uses ANTHROPIC_API_KEY. Grok uses XAI_API_KEY. Groq uses GROQ_API_KEY. OpenAI uses OPENAI_API_KEY. Indus/Sarvam uses the OpenAI-compatible Sarvam endpoint: set INDUS_API_KEY, INDUS_BASE_URL=https://api.sarvam.ai/v2 and INDUS_MODEL=sarvam-105b. Other OpenAI-compatible services can use the same adapter. Sarvam documents a one-time ₹100 signup credit for verified users; after it is consumed, usage is paid.

Indus is supported only if an official developer API endpoint and credentials are available. A consumer chat app is not automatically an API. Provider availability, model IDs, free tiers and rate limits vary. The tool cannot make paid services free.

## Termux install
    pkg update -y
    pkg install -y git python
    cd ~
    git clone https://github.com/rautshivamxyz-beep/NOVA.git
    cd NOVA
    git fetch origin
    git switch --track origin/feat/phone-ai-development-council
    cd mobile-council
    cp .env.example .env
    nano .env

Add only API keys you actually have. Run chmod 600 .env to restrict local access. Never commit keys or paste them into prompts. Check each provider's data-use terms before sending private source code; Google's free Gemini tier may use submitted content to improve its products.

## Run
    python council.py status
    python council.py debate "Audit NOVA conversation quality and memory without reducing response speed"

Optional context:
    python council.py debate "Fix stop generation" --context "Relevant reproduction details..."

Reports are saved in ../.council/runs/.

## Code patch workflow
From repository root:
    git switch main
    git pull --ff-only
    python mobile-council/council.py branch stop-button-fix

Generate a patch for selected source files:
    python mobile-council/council.py patch --name stop-button-fix --report .council/runs/REPORT.md --files android/app/src/main/java/org/nova/MainActivity.kt

The patch is validated but not applied. Inspect it. To apply, repeat with --apply. Then inspect git diff, run tests, commit and push your feature branch yourself.

## Controls and limitations
COUNCIL_MAX_AGENTS defaults to 5. One debate can make a proposal request per provider, a critique request per responding provider, and one chair synthesis request. For strict zero-cost use, configure only APIs confirmed free in your account. Unconfigured providers are skipped; API errors are shown. COUNCIL_CODER chooses the patch model (default Gemini); COUNCIL_TIMEOUT sets timeout seconds.

This is a development sidecar, not an Android app screen. It needs internet. It sends only the files you explicitly provide, not the entire repository. Treat generated code as untrusted. This starter does not build APKs, merge PRs or push commits automatically.
