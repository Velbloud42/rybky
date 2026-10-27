# MediaPipe volá Java třídy z nativního kódu (JNI) a používá protobuf přes reflexi.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**

# Flogger (logování MediaPipe) hledá volajícího procházením zásobníku. Když R8 jeho volání
# inlinuje nebo přejmenuje, Graph.<clinit> spadne s „no caller found on the stack“.
-keep class com.google.common.flogger.** { *; }
-keepnames class com.google.mediapipe.** { *; }
-dontwarn com.google.common.flogger.**
