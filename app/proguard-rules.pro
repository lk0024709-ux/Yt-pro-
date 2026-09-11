# YT Pro (native client) - ProGuard / R8 rules.

# ---------------------------------------------------------------------------
# NewPipeExtractor: heavy reflection (service discovery, parsers, YouTube
# signature deciphering via the bundled Rhino engine). Keep the whole
# extractor model plus the JavaScript runtime it reflects into.
# ---------------------------------------------------------------------------
-keep class org.schabi.newpipe.extractor.** { *; }
-keepclassmembers class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.schabi.newpipe.extractor.**
-keepattributes Signature, InnerClasses, EnclosingMethod

# ---------------------------------------------------------------------------
# Networking / image loading / player ship their own consumer rules; silence
# warnings for repackaged internals only.
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.bumptech.glide.**
-dontwarn androidx.media3.**

# Glide: keep the module + parser contracts so image loading survives
# shrinking (no AppGlideModule is used; Glide.with(view) only).
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$ImageType {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# Serializable models cross component boundaries via executors/caches.
-keepclassmembers class com.au.ytpro.data.** implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep the generated BuildConfig so the About screen can read VERSION_NAME.
-keep class com.au.ytpro.BuildConfig { *; }

# Preserve annotations.
-keepattributes *Annotation*
