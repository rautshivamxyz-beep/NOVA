import os
import subprocess

MODEL = os.path.expanduser(
    "~/NOVA/models/llama-3.2-1b-instruct-q4_k_m.gguf"
)

LLAMA = os.path.expanduser(
    "~/llama.cpp/build/bin/llama-cli"
)

env = os.environ.copy()
env["LD_LIBRARY_PATH"] = os.path.expanduser("~/tmp/nova-libs")

print("""
==============================
       NOVA OFFLINE
       Llama 3.2 1B
==============================
Persistent Chat
Commands: /exit
""")

try:
    subprocess.run(
        [
            LLAMA,
            "-m", MODEL,
            "-c", "512",
            "-t", "1",
            "-b", "8",
            "-ub", "8",
            "-n", "32",
            "-cnv",
            "--simple-io",
            "-sys",
            "You are NOVA, a friendly offline AI assistant. "
            "Give short, natural answers. "
            "Remember the conversation during this session."
        ],
        env=env
    )

except KeyboardInterrupt:
    print("\nNOVA: Goodbye!")
