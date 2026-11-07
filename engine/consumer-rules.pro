# JNI sherpa-onnx читает поля конфигов и создаёт объекты результатов по именам
-keep class com.k2fsa.sherpa.onnx.** { *; }
# JNI symbols are resolved by this exact class/method name.
-keep class com.vexto.engine.pipeline.EmotionNative { *; }
-keepclasseswithmembernames class com.vexto.engine.emotion.GigaAmOrt { native <methods>; }

-keep class com.vexto.engine.punctuation.RuPunctOrt { *; }

-keep class com.vexto.engine.audio.NativeResampler { native <methods>; }
