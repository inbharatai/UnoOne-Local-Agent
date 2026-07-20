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

# --- MediaPipe Tasks / protobuf-lite (Blind Aid object detector) ---
# MediaPipe 0.10.35 does not publish the consumer ProGuard rules that older releases included.
# Its Java/native boundary and FluentLogger.forEnclosingClass() both depend on stable class/method
# names and stack frames. Keeping only generated proto fields fixed one release failure but still
# allowed Graph to be renamed, causing "no caller found on the stack" during Blind Aid startup.
# Reliability is more important than the small APK saving here: preserve the full MediaPipe and
# Flogger surfaces used by the camera detector.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.common.flogger.** { *; }
-keepattributes SourceFile,LineNumberTable,EnclosingMethod
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# --- PDFBox Android optional codecs / public-key encryption ---
# UnoOne uses PDFBox for ordinary local AcroForm filling. PDFBox also contains optional
# code paths for JPEG-2000 and certificate-encrypted PDFs; those providers are deliberately
# not bundled, so R8 must not treat their absent classes as a release-build error.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

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
