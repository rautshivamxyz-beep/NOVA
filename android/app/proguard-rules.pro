# NOVA - R8 keep rules
# v9.33.58 (improvement pass): added when minifyEnabled was turned on.
#
# These keep rules cover the libraries NOVA loads reflectively or via JNI.
# They are deliberately generous: the goal is a correct release, not the
# smallest possible one. If a release crashes in one of these libraries,
# add `-dontobfuscate` below to isolate whether it is renaming or shrinking.

# --- attributes R8 must preserve ---
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# --- llama.cpp Android binding (com.arm.aichat) - JNI entry points ---
-keep class com.arm.aichat.** { *; }
-keepclassmembers class com.arm.aichat.** { *; }
-dontwarn com.arm.aichat.**

# --- ONNX Runtime (on-device embedder) - JNI + reflective session classes ---
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- ML Kit text recognition (bundled, no Play Services) ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-dontwarn com.google.mlkit.**

# --- PDFBox-Android + its font/resource lookups ---
-keep class com.tom_roush.** { *; }
-keepclassmembers class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# --- Markwon + Prism4j (annotation-generated grammars loaded by name) ---
-keep class io.noties.** { *; }
-keepclassmembers class io.noties.** { *; }
-keep @interface io.noties.prism4j.annotations.* { *; }
-keep class * implements io.noties.prism4j.Grammar { *; }
-keep class * { @io.noties.prism4j.annotations.PrismBundle *; }
-dontwarn io.noties.**

# --- Apache Commons Compress (NcieDelta bzip2) ---
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# --- Kotlin runtime metadata ---
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlin.**

# --- Android components referenced only from the manifest ---
# (AGP keeps manifest components automatically; listed for clarity/safety.)
-keep class org.nova.NovaListener { *; }
-keep class org.nova.RemoteEngine { *; }
-keep class org.nova.ReminderReceiver { *; }
-keep class org.nova.SendReceiver { *; }
-keep class org.nova.DreamReceiver { *; }
-keep class org.nova.LeftoverReceiver { *; }
-keep class org.nova.BootReceiver { *; }
-keep class org.nova.ncie.android.NovaTriggerReceiver { *; }

# --- common harmless warnings from transitive deps ---
-dontwarn javax.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Uncomment to disable renaming (shrinking still applies) if obfuscation
# breaks a reflective library you cannot pin down:
# -dontobfuscate

# --- transitive deps that reference JDK-only classes ---
# v9.33.58: the first R8 (minifyReleaseWithR8) run failed on
#   Missing class java.lang.reflect.AnnotatedType
#   (referenced from org.apache.commons.lang3.reflect.TypeUtils.isCyclical)
# commons-lang3 arrives transitively, and AnnotatedType exists only in the full
# JDK, not on the Android classpath - so R8 is told not to worry about it.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn org.apache.commons.lang3.**
