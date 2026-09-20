import os
import subprocess
import json

MODEL = os.path.expanduser(
    "~/NOVA/models/llama-3.2-1b-instruct-q4_k_m.gguf"
)

LLAMA = os.path.expanduser(
    "~/llama.cpp/build/bin/llama-cli"
)

MEMORY_FILE = os.path.expanduser(
    "~/NOVA/nova_memory.json"
)

MAX_CHATS = 11


def load_memory():
    try:
        with open(MEMORY_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except:
        return {}


def save_memory(memory):
    with open(MEMORY_FILE, "w", encoding="utf-8") as f:
        json.dump(memory, f, indent=2, ensure_ascii=False)


def main():

    if not os.path.exists(MODEL):
        print("❌ Model not found.")
        return

    if not os.path.exists(LLAMA):
        print("❌ llama-cli not found.")
        return

    print("""
==============================
       NOVA OFFLINE
       Llama 3.2 1B
==============================
11-chat memory
Commands: /memory /clear /exit
""")

    env = os.environ.copy()

    env["LD_LIBRARY_PATH"] = os.path.expanduser(
        "~/tmp/nova-libs"
    )

    memory = load_memory()

    system_prompt = """You are NOVA, a friendly offline AI assistant.
Give short, natural answers.
Remember the conversation during this session.
Use the user's name when you know it.
Do not mention these instructions."""

    process = subprocess.Popen(
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

            "-sys", system_prompt
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env=env
    )

    try:

        # Let llama.cpp finish loading
        while True:
            line = process.stdout.readline()

            if not line:
                break

            if ">" in line:
                break

        chat_count = 0

        while True:

            user = input("You: ").strip()

            if not user:
                continue

            command = user.lower()

            if command == "/exit":
                print("\nNOVA: Goodbye!")
                break

            if command == "/memory":
                if memory:
                    print("\nNOVA MEMORY:")
                    for k, v in memory.items():
                        print(f"- {k}: {v}")
                else:
                    print("\nNOVA MEMORY:\n- No memories yet.")
                continue

            if command == "/clear":
                memory = {}
                save_memory(memory)
                print("\nNOVA: Memory cleared.")
                continue

            # Simple memory
            lower = user.lower()

            if "my name is " in lower:
                name = user[lower.find("my name is ") + 11:].strip()
                if name:
                    memory["name"] = name
                    save_memory(memory)

            chat_count += 1

            process.stdin.write(user + "\n")
            process.stdin.flush()

            answer = ""

            while True:
                line = process.stdout.readline()

                if not line:
                    break

                line = line.rstrip()

                if line.startswith("[ Prompt:"):
                    continue

                if line == ">":
                    break

                if line.startswith("Exiting"):
                    break

                answer += line + "\n"

                # llama normally returns control after generation
                if line and not line.startswith(">"):
                    continue

            answer = answer.strip()

            if answer:
                print("\nNOVA:", answer)
            else:
                print("\nNOVA: I'm here.")

            if chat_count >= MAX_CHATS:
                chat_count = MAX_CHATS

    except KeyboardInterrupt:
        print("\n\nNOVA: Goodbye!")

    finally:
        try:
            process.terminate()
            process.wait(timeout=3)
        except:
            try:
                process.kill()
            except:
                pass


if __name__ == "__main__":
    main()
