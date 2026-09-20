import json
import os
from threading import Thread

from kivy.clock import Clock
from kivy.lang import Builder
from kivymd.app import MDApp
from kivymd.uix.label import MDLabel

from nova import ask_nova


class NovaApp(MDApp):

    def build(self):
        self.theme_cls.theme_style = "Light"
        self.theme_cls.primary_palette = "Blue"

        self.chat_history = []
        self.typing_widget = None

        return Builder.load_file("main.kv")

    def on_start(self):
        self.load_settings()
        self.load_chat_history()

        if not self.chat_history:
            self.bot_message(
                "👋 Hello! I'm NOVA.\nI'm running locally with Llama 3.2 1B.",
                save=True
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
        label = MDLabel(
            text=f"[b]🧑 You[/b]\n{message}",
            markup=True,
            adaptive_height=True,
            size_hint_y=None,
            padding=("12dp", "10dp"),
            theme_text_color="Primary"
        )

        self.root.ids.chat_list.add_widget(label)

        if save:
            self.chat_history.append(
                ("User", message)
            )

        self.scroll_to_bottom()

    def bot_message(self, message, save=True):
        label = MDLabel(
            text=f"[b]🤖 NOVA[/b]\n{message}",
            markup=True,
            adaptive_height=True,
            size_hint_y=None,
            padding=("12dp", "10dp"),
            theme_text_color="Primary"
        )

        self.root.ids.chat_list.add_widget(label)

        if save:
            self.chat_history.append(
                ("Nova", message)
            )

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

    def toggle_theme(self):
        if self.theme_cls.theme_style == "Light":
            self.theme_cls.theme_style = "Dark"
        else:
            self.theme_cls.theme_style = "Light"

        self.save_settings()

    def save_settings(self):
        with open(
            "settings.json",
            "w",
            encoding="utf-8"
        ) as f:
            json.dump(
                {
                    "theme": self.theme_cls.theme_style
                },
                f
            )

    def load_settings(self):
        if os.path.exists("settings.json"):
            try:
                with open(
                    "settings.json",
                    "r",
                    encoding="utf-8"
                ) as f:
                    data = json.load(f)

                self.theme_cls.theme_style = data.get(
                    "theme",
                    "Light"
                )

            except Exception:
                pass

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
                    self.user_message(
                        message,
                        save=False
                    )

                else:
                    self.bot_message(
                        message,
                        save=False
                    )

        except Exception:
            self.chat_history = []

    def clear_chat(self):
        self.root.ids.chat_list.clear_widgets()
        self.chat_history = []
        self.save_chat_history()

        self.bot_message(
            "🧹 Chat cleared.",
            save=True
        )

    def new_chat(self):
        self.clear_chat()


if __name__ == "__main__":
    NovaApp().run()
