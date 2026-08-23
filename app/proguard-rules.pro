# Protobuf lite keeps its generated classes reachable only via reflection from the runtime.
-keep class com.homecam.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class org.webrtc.** { *; }
