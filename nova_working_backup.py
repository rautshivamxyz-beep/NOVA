import os
import json
import subprocess

MODEL = os.path.expanduser(
    "~/NOVA/models/llama-3.2-1b-instruct-q4_k_m.gguf"
)

MEMORY_FILE = os.path.expanduser(
    "~/NOVA/nova_memory.json"
)

LLAMA_BIN = os.path.expanduser(
    "~/llama.cpp/build/bin/llama-cli"
)


def load_memory():
    if not os.path.exists(MEMORY_FILE):
        return {}

    try:
        with open(MEMORY_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_memory(memory):
    with open(MEMORY_FILE, "w", encoding="utf-8") as f:
        json.dump(memory, f, indent=2, ensure_ascii=False)


def remember(message):
    memory = load_memory()

    text = message.lower()

    if "favorite color is" in text:
        color = message.split("favorite color is", 1)[1].strip()
        memory["favorite_color"] = color
        save_memory(memory)
        return f"I'll remember that. (favorite_color: {color})"

    return None


def ask_nova(message):
    # Memory command
    if message.strip().lower() == "/memory":
        memory = load_memory()

        if not memory:
            return "NOVA MEMORY:\n- No memories yet."

        result = "NOVA MEMORY:\n"

        for key, value in memory.items():
            result += f"- {key}: {value}\n"

        return result.rstrip()

    # Clear memory
    if message.strip().lower() == "/clear":
        save_memory({})
        return "🧹 NOVA memory cleared."

    # Exit
    if message.strip().lower() == "/exit":
        return "You can close NOVA now."

    # Remember information
    remembered = remember(message)

    if remembered:
        return remembered

    # Check model
    if not os.path.exists(MODEL):
        return f"❌ Model not found:\n{MODEL}"

    if not os.path.exists(LLAMA_BIN):
        return f"❌ llama-cli not found:\n{LLAMA_BIN}"

    memory = load_memory()

    memory_text = ""

    if memory:
        memory_text = "\nKnown memory:\n"

        for key, value in memory.items():
            memory_text += f"- {key}: {value}\n"

    prompt = f"""You are NOVA, a friendly offline AI assistant.
Answer naturally and concisely.
You are running locally on the user's Android phone.

{memory_text}

User: {message}
NOVA:"""

    try:
        result = subprocess.run(
            [
                LLAMA_BIN,
                "-m",
                MODEL,
                "-p",
                prompt,
                "-n",
                "256",
                "--temp",
                "0.7"
            ],
            capture_output=True,
            text=True,
            timeout=120
        )

        if result.returncode != 0:
            return f"❌ Llama error:\n{result.stderr}"

        output = result.stdout.strip()

        if "NOVA:" in output:
            output = output.split("NOVA:", 1)[-1].strip()

        return output

    except subprocess.TimeoutExpired:
        return "⏳ NOVA took too long to respond."

    except Exception as e:
        return f"❌ Error: {e}"


if __name__ == "__main__":

    print("""
==============================
       NOVA OFFLINE
       Llama 3.2 1B
==============================
Commands: /memory /clear /exit
""")

    while True:

        try:
            user = input("You: ").strip()

            if not user:
                continue

            if user.lower() == "/exit":
                print("NOVA: Goodbye!")
                break

            print("NOVA:", ask_nova(user))

        except KeyboardInterrupt:
            print("\nNOVA: Goodbye!")
            break

        except Exception as e:
            print("NOVA ERROR:", e)

