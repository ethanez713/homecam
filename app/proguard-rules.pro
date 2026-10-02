# Protobuf lite keeps its generated classes reachable only via reflection from the runtime.
-keep class com.homecam.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class org.webrtc.** { *; }

# Verbose logs carry ICE candidates (LAN and tailnet addresses); they exist for debug builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
}
