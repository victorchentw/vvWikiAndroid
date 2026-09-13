# The app uses a local WebView bridge. Keep public bridge method names.
-keepclassmembers class com.victor.vvwiki.ReaderActivity$JsBridge {
    public <methods>;
}
