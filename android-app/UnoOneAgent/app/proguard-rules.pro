# ProGuard rules for UnoOne
# Keep Room entities
-keep class com.unoone.agent.storage.entity.** { *; }
# Keep serialization models
-keep class com.unoone.agent.core.model.** { *; }
# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
