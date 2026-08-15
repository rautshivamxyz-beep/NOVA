[app]

title = NOVA
package.name = nova
package.domain = org.nova

source.dir = .
source.include_exts = py,kv,json,png,jpg,jpeg

version = 1.0

requirements = python3,kivy,kivymd

orientation = portrait

fullscreen = 0

android.api = 33
android.minapi = 21
android.ndk = 25b

android.archs = arm64-v8a
android.sdk_path = /usr/local/lib/android/sdk

[buildozer]

log_level = 2
warn_on_root = 1
