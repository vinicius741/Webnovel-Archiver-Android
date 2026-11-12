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
# Source reliability state is restored eagerly in AppContainer.init(). Unkept, R8 drops the generic
# signature of PersistedReliabilityDocument.hosts, Gson then yields LinkedTreeMap elements instead
# of PersistedHostReliability, and the element cast crashes Application.onCreate in release only.
-keep class com.vinicius741.webnovelarchiver.source.network.PersistedHostReliability {
    <init>(...);
    <fields>;
}
-keep class com.vinicius741.webnovelarchiver.source.network.SourceReliabilityStore$PersistedReliabilityDocument {
    <init>(...);
    <fields>;
}

# Generated narration (queue.json, recipe.json, index.json) and the narration server's /v1/info are
# Gson models too. Unkept in 1.0.18/1.0.19, R8 renamed their fields to a, b, c… and dropped the
# generic signatures: the queue failed to load on every start (LinkedHashMap cast to NarrationJob) and
# playing a generated chapter crashed (Double cast to Long in PackTtsOutput.applyDurations).
# NarrationLegacyJson reads files those releases wrote.
-keep class com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument { <init>(...); <fields>; }
-keep class com.vinicius741.webnovelarchiver.narration.NarrationJob { <init>(...); <fields>; }
-keep class com.vinicius741.webnovelarchiver.narration.NarrationVoice { <init>(...); <fields>; }
-keep class com.vinicius741.webnovelarchiver.narration.NarrationRecipe { <init>(...); <fields>; }
-keep class com.vinicius741.webnovelarchiver.narration.NarrationPackIndex { <init>(...); <fields>; }
-keep class com.vinicius741.webnovelarchiver.narration.NarrationServer { <init>(...); <fields>; }
-keep class com.vinicius741.webnovelarchiver.narration.NarrationServerInfo { <init>(...); <fields>; }
-keep enum com.vinicius741.webnovelarchiver.narration.NarrationOrigin { *; }
-keep enum com.vinicius741.webnovelarchiver.narration.NarrationJobState { *; }

# Components are named from AndroidManifest.xml and must remain constructible after shrinking.
-keep public class com.vinicius741.webnovelarchiver.app.WebnovelArchiverApp { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.app.MainActivity { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.download.DownloadForegroundService { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.tts.TtsForegroundService { public <init>(); }
-keep public class com.vinicius741.webnovelarchiver.appupdate.AppUpdateInstallReceiver { public <init>(); }

# sherpa-onnx (built-in Kokoro voices): native code reads the Kotlin config classes' fields by name
# and calls back into the API classes, so none of them may be renamed or stripped.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Commons Compress references optional codecs the Kokoro unpacker never uses.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
