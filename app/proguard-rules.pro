# Firestore maps these POJOs by reflection. Keep their field names, constructors,
# getters and setters in minified release APKs; R8 otherwise strips/renames them.
-keep class com.example.salonapp.Item { *; }
-keep class com.example.salonapp.ChatSession { *; }
-keep class com.example.salonapp.ChatMessage { *; }

# MediaPipe Tasks uses reflection and stack inspection to initialize its JNI graph.
# Preserve its Java API/framework names and members in minified release builds.
-keep class com.google.mediapipe.** { *; }

# Optional LiteRT-LM backends and MediaPipe proto types are referenced by
# transitive library code but are not packaged by this app.
-dontwarn com.google.ai.edge.litertlm.Backend$CPU
-dontwarn com.google.ai.edge.litertlm.Backend$GPU
-dontwarn com.google.ai.edge.litertlm.Backend$NPU
-dontwarn com.google.ai.edge.litertlm.Backend
-dontwarn com.google.ai.edge.litertlm.EmbeddingEngine
-dontwarn com.google.ai.edge.litertlm.EmbeddingEngineConfig
-dontwarn com.google.ai.edge.litertlm.EmbeddingOptions
-dontwarn com.google.ai.edge.litertlm.EmbeddingResponse
-dontwarn com.google.ai.edge.litertlm.InputData$Image
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
