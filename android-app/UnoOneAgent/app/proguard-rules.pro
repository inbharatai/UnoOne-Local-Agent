# ProGuard / R8 rules for UnoOne
# Release builds enable isMinifyEnabled + isShrinkResources. These keeps preserve the
# reflection-heavy / native-bound surfaces: Room entities & DAOs, kotlinx.serialization
# models, LiteRT-LM + Sherpa-ONNX native bindings, ML Kit, Hilt, and the LiteRT-LM ToolSet.

# --- Room ---
-keep class com.unoone.agent.storage.entity.** { *; }
-keep class com.unoone.agent.storage.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- kotlinx.serialization models (core.model + manifest DTOs) ---
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers class com.unoone.agent.core.model.** {
    *** Companion;
    <fields>;
}
-keep class com.unoone.agent.core.model.** { *; }
-keep class com.unoone.agent.modelmanager.model.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    <fields>;
    *** Companion;
}
-dontnote kotlinx.serialization.AnnotationsKt
# Keep serialization generated serializers
-keepclassmembers class **$$serializer { *; }

# --- LiteRT-LM (Gemma 3n E4B) + manual tool calling ---
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.unoone.agent.localbrain.UnoOneToolSet { *; }
-keep @com.google.ai.edge.litertlm.Tool class * { *; }
-keep @com.google.ai.edge.litertlm.ToolParam class * { *; }
-keepclasseswithmembers class * { @com.google.ai.edge.litertlm.Tool <methods>; }

# --- Sherpa-ONNX (native STT/TTS/KWS) ---
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.unoone.agent.voice.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# --- ML Kit (OCR / object detection, bundled in :phonecontrol) ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-dontwarn com.google.mlkit.**

# --- Hilt (already handled by the Hilt Gradle plugin, kept for safety) ---
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Compose / lifecycle (keep rules are provided by their libs; this is a safety net) ---
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**