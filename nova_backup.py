import json
import os
import re
import subprocess

MODEL = os.path.expanduser(
    "~/NOVA/models/llama-3.2-1b-instruct-q4_k_m.gguf"
)

LLAMA = os.path.expanduser(
    "~/llama.cpp/build/bin/llama-cli"
)

MEMORY = os.path.expanduser("~/NOVA/memory.json")

history = []


def load_memory():
    try:
        with open(MEMORY, "r") as f:
            return json.load(f)
    except:
        return {}


def save_memory(data):
    with open(MEMORY, "w") as f:
        json.dump(data, f, indent=4)


def learn(text):
    memory = load_memory()

    patterns = [
        (r"my name is (.+)", "name"),
        (r"my favorite game is (.+)", "favorite_game"),
        (r"my favourite game is (.+)", "favorite_game"),
        (r"my favorite color is (.+)", "favorite_color"),
        (r"my favourite color is (.+)", "favorite_color"),
        (r"my favorite colour is (.+)", "favorite_color"),
        (r"my favourite colour is (.+)", "favorite_color"),
        (r"i like (.+)", "likes"),
    ]

    for pattern, key in patterns:
        match = re.search(pattern, text, re.IGNORECASE)

        if match:
            value = match.group(1).strip().rstrip(".!?")
            memory[key] = value
            save_memory(memory)
            return key, value

    return None


def ask_nova(message):
    memory = load_memory()

    memory_text = "\n".join(
        f"{key}: {value}"
        for key, value in memory.items()
    )

    conversation = ""

    for user, reply in history[-4:]:
        conversation += f"User: {user}\nNOVA: {reply}\n"

    prompt = f"""You are NOVA, a friendly offline AI assistant.

Known information about the user:
{memory_text}

Previous conversation:
{conversation}

Answer directly and naturally.
Do not invent information.

User: {message}
NOVA:"""

    result = subprocess.run(
        [
            LLAMA,
            "-m", MODEL,
            "-c", "2048",
            "-n", "128",
            "--no-conversation",
            "--simple-io",
            "-p", prompt
        ],
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        return "Error: " + result.stderr

    return result.stdout.strip()


print()
print("==============================")
print("       NOVA OFFLINE")
print("       Llama 3.2 1B")
print("==============================")
print("Commands: /memory /clear /exit")
print()

while True:
    try:
        message = input("You: ").strip()

        if not message:
            continue

        if message.lower() == "/exit":
            print("NOVA: Goodbye!")
            break

        if message.lower() == "/memory":
            memory = load_memory()

            print("\nNOVA MEMORY:")

            if memory:
                for key, value in memory.items():
                    print(f"- {key}: {value}")
            else:
                print("- Empty")

            print()
            continue

        if message.lower() == "/clear":
            history.clear()
            print("NOVA: Conversation cleared.\n")
            continue

        learned = learn(message)

        if learned:
            key, value = learned
            print(f"NOVA: I'll remember that. ({key}: {value})\n")
            continue

        reply = ask_nova(message)

        print("\nNOVA:", reply)
        print()

        history.append((message, reply))

    except KeyboardInterrupt:
        print("\nNOVA: Goodbye!")
        break
