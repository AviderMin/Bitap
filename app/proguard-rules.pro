# Bitap release rules
-keep class com.google.ai.edge.litertlm.** { *; }
# Keep the local AI service classes: a stale/missing implementation must never
# surface as NoClassDefFoundError in a minified IME process.
-keep class com.bitap.keyboard.ai.internal.** { *; }
-keep class com.bitap.keyboard.ai.LocalAiRewriteService { *; }
-keepclassmembers class * implements java.io.Closeable { public void close(); }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
