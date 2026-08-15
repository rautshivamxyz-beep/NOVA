from kivymd.app import MDApp
from kivymd.uix.label import MDLabel

class NOVATest(MDApp):
    def build(self):
        return MDLabel(
            text="NOVA TEST - KIVYMD WORKS",
            halign="center"
        )

NOVATest().run()
