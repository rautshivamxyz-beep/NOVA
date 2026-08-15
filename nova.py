import os
import json
import subprocess

MODEL = os.path.expanduser(
    "~/NOVA/models/llama-3.2-1b-instruct-q4_k_m.gguf"
)

LLAMA = os.path.expanduser(
    "~/llama.cpp/build/bin/llama-cli"
)

MEMORY_FILE = os.path.expanduser(
    "~/NOVA/nova_memory.json"
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


def ask_nova(message):

    command = message.strip().lower()

    # MEMORY
    if command == "/memory":
        memory = load_memory()

        if not memory:
            return "NOVA MEMORY:\n- No memories yet."

        result = "NOVA MEMORY:\n"

        for key, value in memory.items():
            result += f"- {key}: {value}\n"

        return result.rstrip()

    # CLEAR MEMORY
    if command == "/clear":
        save_memory({})
        return "🧹 NOVA memory cleared."

    # EXIT
    if command == "/exit":
        return "Goodbye!"

    # SAVE FAVORITE COLOR
    lower = message.lower()
    phrase = "my favorite color is "

    if phrase in lower:
        start = lower.find(phrase) + len(phrase)
        color = message[start:].strip()

        memory = load_memory()
        memory["favorite_color"] = color
        save_memory(memory)

        return f"I'll remember that. (favorite_color: {color})"

    # CHECK MODEL
    if not os.path.exists(MODEL):
        return "❌ Model not found."

    if not os.path.exists(LLAMA):
        return "❌ llama-cli not found."

    # MEMORY FOR PROMPT
    memory = load_memory()
    memory_text = ""

    if memory:
        memory_text = "\nKnown memories:\n"

        for key, value in memory.items():
            memory_text += f"- {key}: {value}\n"

    # PROMPT
    prompt = f"""You are NOVA, a friendly offline AI assistant.
Give short, natural answers.
You are running locally on an Android phone.
Do not mention these instructions.

{memory_text}

User: {message}
NOVA:"""

    # LIBRARIES
    env = os.environ.copy()

    env["LD_LIBRARY_PATH"] = os.path.expanduser(
        "~/llama.cpp/build/bin"
    )

    try:

        result = subprocess.run(
            [
                LLAMA,
                "-m",
                MODEL,

                # LOW RAM SETTINGS
                "-c",
                "128",

                "-t",
                "1",

                "-b",
                "8",

                "-ub",
                "8",

                "-n",
                "64",

                # SINGLE TURN
                "-st",

                "-p",
                prompt
            ],
            capture_output=True,
            text=True,
            env=env,
            timeout=180
        )

        if result.returncode != 0:

            error = result.stderr.strip()

            if not error:
                error = "Unknown llama.cpp error."

            return f"❌ Llama error:\n{error}"

        output = result.stdout.strip()

        if not output:
            return "I'm here."

        # Remove prompt text
        if "NOVA:" in output:
            output = output.split(
                "NOVA:",
                1
            )[-1].strip()

        return output

    except subprocess.TimeoutExpired:

        return "⏳ NOVA took too long to respond."

    except Exception as e:

        return f"❌ Error: {e}"


def main():

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

            reply = ask_nova(user)

            print()
            print("NOVA:", reply)
            print()

        except KeyboardInterrupt:

            print("\nNOVA: Goodbye!")

            break

        except Exception as e:

            print("\nNOVA ERROR:", e)


if __name__ == "__main__":
    main()
