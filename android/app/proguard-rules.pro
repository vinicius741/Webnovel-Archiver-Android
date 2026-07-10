# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Gson persists these models by Java field name. Keep the classes and field wire names stable across
# releases (R8 minify is enabled for release). Signature/Annotation attrs are required for TypeToken.
#
# Constructors must also stay: Kotlin default-parameter no-arg ctors apply field defaults (e.g.
# Story.publicationStatus = unknown). If R8 strips them, Gson falls back to Unsafe.allocateInstance,
# leaves missing JSON fields null, and the next Story.copy() crashes startup with:
# "Parameter specified as non-null is null: ... publicationStatus".
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.vinicius741.webnovelarchiver.domain.model.** {
    <init>(...);
    <fields>;
}
-keep class com.vinicius741.webnovelarchiver.data.storage.DurableJson$Envelope {
    <init>(...);
    <fields>;
}
-keep class com.vinicius741.webnovelarchiver.data.diagnostics.** {
    <init>(...);
    <fields>;
}

# Components are named from AndroidManifest.xml and must remain constructible after shrinking.
-keep public class com.vinicius741.webnovelarchiver.app.WebnovelArchiverApp { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.app.MainActivity { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.download.DownloadForegroundService { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.tts.TtsForegroundService { public <init>(); }
