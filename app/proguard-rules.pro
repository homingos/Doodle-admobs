# Default Android optimize rules already cover most cases.
# Google Mobile Ads ships with consumer rules — no manual rules needed.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AdMob loads our mediation custom-event adapter by name (Class.forName).
# Without this keep rule R8 strips or renames the class in release builds and
# the waterfall silently falls through to the next ad source.
-keep class com.doodlepop.app.VertexCustomEventInterstitial { *; }
-keep class com.doodlepop.app.VertexInterstitialActivity { *; }

# WebView JavascriptInterface bridge — methods are called from JS by name,
# so they must survive shrinking/obfuscation.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
