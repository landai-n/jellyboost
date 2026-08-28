# R8 configuration for the release build (M10, docs/PLAN.md "Release hardening").
#
# READ THIS BEFORE ADDING A RULE.
#
# The rule of the file is: *do not cargo-cult*. Almost every library in this stack ships its own
# consumer rules inside its AAR/JAR, and AGP feeds those to R8 automatically. A rule belongs here
# only when it was shown to be missing. Each rule below records why it exists.
#
# Verified as already covered by consumer rules (deliberately NOT repeated here):
#   * kotlinx.serialization — kotlinx-serialization-core ships
#     META-INF/com.android.tools/r8/kotlinx-serialization-{common,r8}.pro, which keeps the
#     `Companion` field, `serializer()`, `INSTANCE`, the `$$serializer.descriptor` field and the
#     RuntimeVisibleAnnotations attribute for every @kotlinx.serialization.Serializable class.
#     That covers our @Serializable nav routes in :core:common and every SDK model we (de)serialise.
#   * jellyfin-sdk models — jellyfin-core-android's proguard.txt already does
#     `-keep class org.jellyfin.sdk.model.**.* { *; }` (see the belt-and-braces note below for the
#     one gap that pattern leaves).
#   * Room — room-runtime ships `-keep class * extends androidx.room.RoomDatabase { void <init>(); }`,
#     which keeps JellyfinDatabase *and* the generated JellyfinDatabase_Impl unobfuscated, so
#     Room's `Class.forName(canonicalName + "_Impl")` lookup resolves. Room's enum columns are
#     generated as `when` blocks over string literals, so enum constant names are compile-time
#     constants and survive obfuscation with no rule.
#   * Hilt / Dagger — hilt-android keeps @EntryPoint-annotated types for its reflective cast;
#     everything else Hilt generates is statically referenced. Hilt_JellyboostApplication and
#     the activities/services are kept by the AAPT-generated rules for manifest-declared components.
#   * WorkManager — work-runtime ships `-keepnames class * extends androidx.work.ListenableWorker`
#     plus a constructor keep, and hilt-work adds `-keepnames @HiltWorker class * extends
#     ListenableWorker`. Worker class names are persisted in the WorkManager DB, so keeping the
#     names (not just the classes) is what matters — and it is already done.
#   * Media3 / ExoPlayer — media3-exoplayer keeps the reflectively-loaded extension renderer
#     constructors (including androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer, which is what
#     EXTENSION_RENDERER_MODE_PREFER instantiates), and org.jellyfin.media3:media3-ffmpeg-decoder
#     keeps its own JNI entry points (`native <methods>` + FfmpegAudioDecoder.growOutputBuffer).
#   * libass (io.github.peerless2012:ass-kt) — the AAR's own proguard.txt keeps Ass, AssTrack,
#     AssRender, AssFrame, AssTex and AssEvent whole, which is every type the JNI layer resolves
#     by name. `ass-media`, the Media3 extension on top of it, ships an *empty* proguard.txt —
#     hence the one rule below.
#   * OkHttp — okhttp ships its -dontwarn set for the optional Conscrypt/BouncyCastle/JSR-305
#     compile-only dependencies.
#   * Navigation — navigation-common keeps Navigator subclasses and RuntimeVisibleAnnotations.
#   * androidx.security.crypto / Tink — tink-android ships the protobuf keep rules it needs.
#   * DataStore, Paging, Lifecycle, Compose — all ship "safe to shrink" or targeted rules.


# ---------------------------------------------------------------------------
# Crash-report readability
# ---------------------------------------------------------------------------
# Keep line numbers so a release stack trace can be mapped back through
# app/build/outputs/mapping/release/mapping.txt. `-renamesourcefileattribute` then collapses every
# source file name to the single string "SourceFile", which is smaller than keeping the real names
# and is what retrace expects.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# ---------------------------------------------------------------------------
# Coil 3 — ServiceLoader-discovered network fetcher
# ---------------------------------------------------------------------------
# JellyboostApplication builds its ImageLoader without registering a network fetcher
# explicitly, so every remote poster is fetched through the OkHttp fetcher that coil-network-okhttp
# publishes via META-INF/services/coil3.util.FetcherServiceLoaderTarget
# (coil3.network.okhttp.internal.OkHttpNetworkFetcherServiceLoaderTarget). Nothing references that
# class statically. R8 does model `ServiceLoader.load`, but the failure mode if it ever does not —
# every network image silently 404s at runtime, with no build-time signal — is bad enough that we
# pin the contract explicitly rather than rely on the optimisation. coil-network-okhttp ships no
# consumer rules of its own.
-keep class * implements coil3.util.FetcherServiceLoaderTarget { <init>(); }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { <init>(); }


# ---------------------------------------------------------------------------
# SLF4J 2.x provider — required by the jellyfin SDK
# ---------------------------------------------------------------------------
# The SDK logs through kotlin-logging -> SLF4J. Release ships org.slf4j:slf4j-nop as the binding
# (build(logging) commit: the SDK's request-URL logging is discarded rather than written to
# logcat), and the *presence* of a binding is load-bearing — removing it entirely broke UDP server
# discovery (DECISIONS.md 2026-07-28). SLF4J 2.x resolves its binding through
# ServiceLoader<org.slf4j.spi.SLF4JServiceProvider>, so the provider implementation has no static
# referrer and would otherwise be shrunk away.
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { <init>(); }
# slf4j-api probes for the absent 1.x-style binding class; that reference is dead by design.
-dontwarn org.slf4j.impl.**


# ---------------------------------------------------------------------------
# jellyfin-sdk models — belt and braces over the SDK's own consumer rule
# ---------------------------------------------------------------------------
# jellyfin-core's proguard.txt uses `org.jellyfin.sdk.model.**.*`, which requires at least one
# sub-package segment and therefore does *not* match the handful of types that sit directly in
# `org.jellyfin.sdk.model` (ClientInfo, DeviceInfo, FileInfo, ServerVersion). Those are all
# constructed from our own code today, so usage keeps them, but they are also the SDK's public
# surface and a future SDK bump could route one of them through reflective (de)serialisation. The
# whole model tree is data classes we serialise anyway, so keeping it costs little.
-keep class org.jellyfin.sdk.model.** { *; }


# ---------------------------------------------------------------------------
# libass MKV support — two MatroskaExtractor fields read reflectively
# ---------------------------------------------------------------------------
# io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor reaches into its superclass for
# `extractorOutput` and `subtitleSample` via Class.getDeclaredField, in a *static* initialiser: a
# rename or a shrink turns the first embedded-ASS MKV into an ExceptionInInitializerError, in
# release builds only, on a path debug never exercises. ass-media's proguard.txt is empty, so
# nothing keeps them but this. Both field names verified present in media3 1.9.0.
-keepclassmembers class androidx.media3.extractor.mkv.MatroskaExtractor {
    private androidx.media3.extractor.ExtractorOutput extractorOutput;
    private androidx.media3.common.util.ParsableByteArray subtitleSample;
}


# ---------------------------------------------------------------------------
# Kotlin metadata / coroutines hygiene
# ---------------------------------------------------------------------------
# kotlinx-coroutines-core references a JVM-only ServiceLoader debug agent and a
# java.lang.instrument hook that do not exist on Android.
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.**
