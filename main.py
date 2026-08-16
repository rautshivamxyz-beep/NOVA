import json
import os
from threading import Thread

from kivy.app import App
from kivy.clock import Clock
from kivy.lang import Builder
from kivy.uix.label import Label
from kivy.core.window import Window

from nova import ask_nova


class NovaApp(App):

    def build(self):
        self.chat_history = []
        self.typing_widget = None
        return Builder.load_file("main.kv")

    def on_start(self):
        Window.softinput_mode = "below_target"
        self.load_chat_history()

        if not self.chat_history:
            self.bot_message(
                "👋 Hello! I'm NOVA.\nI'm running locally with Llama 3.2 1B."
            )

    def send_message(self):
        text = self.root.ids.message_input.text.strip()

        if not text:
            return

        self.root.ids.message_input.text = ""

        self.user_message(text)
        self.start_typing()

        Thread(
            target=self.run_nova,
            args=(text,),
            daemon=True
        ).start()

    def run_nova(self, message):
        try:
            reply = ask_nova(message)
            if reply is None:
                reply = "I couldn't generate a response."
            reply = str(reply)

        except Exception as e:
            reply = f"❌ Error: {e}"

        Clock.schedule_once(
            lambda dt: self.finish_reply(reply),
            0
        )

    def finish_reply(self, reply):
        self.stop_typing()
        self.bot_message(reply)
        self.save_chat_history()

    def start_typing(self):
        self.typing_widget = self.bot_message(
            "⏳ NOVA is thinking...",
            save=False
        )

    def stop_typing(self):
        if self.typing_widget:
            try:
                self.root.ids.chat_list.remove_widget(
                    self.typing_widget
                )
            except Exception:
                pass

        self.typing_widget = None

    def user_message(self, message, save=True):
        label = Label(
            text=f"You\n{message}",
            size_hint_y=None,
            halign="left",
            valign="top"
        )

        label.bind(
            texture_size=lambda instance, size:
            setattr(instance, "height", size[1] + 20)
        )

        self.root.ids.chat_list.add_widget(label)

        if save:
            self.chat_history.append(("User", message))

        self.scroll_to_bottom()

    def bot_message(self, message, save=True):
        label = Label(
            text=f"NOVA\n{message}",
            size_hint_y=None,
            halign="left",
            valign="top"
        )

        label.bind(
            texture_size=lambda instance, size:
            setattr(instance, "height", size[1] + 20)
        )

        self.root.ids.chat_list.add_widget(label)

        if save:
            self.chat_history.append(("Nova", message))

        self.scroll_to_bottom()

        return label

    def scroll_to_bottom(self, *args):
        Clock.schedule_once(
            lambda dt: setattr(
                self.root.ids.chat_scroll,
                "scroll_y",
                0
            ),
            0.1
        )

    def save_chat_history(self):
        with open(
            "chat_history.json",
            "w",
            encoding="utf-8"
        ) as f:
            json.dump(
                self.chat_history,
                f,
                ensure_ascii=False,
                indent=2
            )

    def load_chat_history(self):
        if not os.path.exists("chat_history.json"):
            return

        try:
            with open(
                "chat_history.json",
                "r",
                encoding="utf-8"
            ) as f:
                self.chat_history = json.load(f)

            for sender, message in self.chat_history:
                if sender == "User":
                    self.user_message(message, save=False)
                else:
                    self.bot_message(message, save=False)

        except Exception:
            self.chat_history = []

    def clear_chat(self):
        self.root.ids.chat_list.clear_widgets()
        self.chat_history = []

        self.save_chat_history()

        self.bot_message(
            "🧹 Chat cleared."
        )


if __name__ == "__main__":
    NovaApp().run()
