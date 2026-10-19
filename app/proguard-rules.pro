# Sonora ships no third-party code; the default Android rules are sufficient.
# Keep the JavaScript bridge methods so the WebView can reach them.
-keepclassmembers class com.sonora.app.MainActivity$Bridge {
    public *;
}
