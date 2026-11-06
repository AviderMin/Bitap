# Bitap release rules
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * implements java.io.Closeable { public void close(); }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
