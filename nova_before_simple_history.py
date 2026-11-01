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

    if not os.path.exists(MODEL):
        return "❌ Model not found."

    global CHAT_HISTORY

    history_text = ""

    for old_user, old_reply in CHAT_HISTORY[-10:]:
        history_text += f"User: {old_user}\nNOVA: {old_reply}\n"

    memory = load_memory()
    memory_text = ""

    if memory:
        memory_text = "\nKnown memories:\n"
        for key, value in memory.items():
            memory_text += f"- {key}: {value}\n"

    prompt = f"""You are NOVA, a friendly offline AI assistant.
Give short, natural answers.
You are running locally on an Android phone.

IMPORTANT:
- Answer the user's CURRENT message.
- Use Conversation History only to understand what the user previously said.
- Do not confuse saved memories with conversation history.
- If the user asks "what did I ask earlier", look at Conversation History.
- Keep answers short and natural.

Saved memories:
{memory_text}

Conversation History:
{history_text}

CURRENT USER MESSAGE:
{message}

NOVA:"""

    try:
        import urllib.request
        import json

        data = json.dumps({
            "prompt": prompt,
            "n_predict": 64,
            "temperature": 0.7,
            "stop": ["User:", "\nUser:"]
        }).encode("utf-8")

        request = urllib.request.Request(
            "http://127.0.0.1:8080/completion",
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST"
        )

        with urllib.request.urlopen(request, timeout=180) as response:
            result = json.loads(response.read().decode("utf-8"))

        output = result.get("content", "").strip()

        # Remove accidental prompt continuation
        for marker in [
            "CURRENT USER MESSAGE:",
            "Conversation History:",
            "Saved memories:",
            "NOVA:"
        ]:
            if marker in output:
                output = output.split(marker, 1)[0].strip()

        if not output:
            return "I'm here."

        CHAT_HISTORY.append((message, output))

        return output

    except Exception as e:
        return f"❌ Server error: {e}"


CHAT_HISTORY = []
CHAT_COUNT = 0
MAX_CHATS = 20

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
