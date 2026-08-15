from kivy.app import App
from kivy.uix.label import Label

class NOVAApp(App):
    def build(self):
        return Label(text="NOVA TEST - KIVY WORKS")

NOVAApp().run()
