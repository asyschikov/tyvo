# Keep the input method service. Android instantiates it by name from the
# manifest, so R8 cannot see the reference and would otherwise strip it --
# producing a release build whose keyboard simply does not appear.
-keep class com.tyvo.keyboard.ime.TyvoInputMethodService { *; }

# Same for the settings activity, which is both the launcher entry point and
# the IME's settingsActivity target.
-keep class com.tyvo.keyboard.settings.SettingsActivity { *; }

# OkHttp ships optional integrations it reflects on at runtime. Without these
# the release build warns loudly and can fail on network calls.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# org.json is part of the platform, but R8 sees the test-time artifact too.
-dontwarn org.json.**

# Tink, which backs EncryptedSharedPreferences, is annotated with Error Prone
# annotations that are compile-time only and never packaged. R8 treats the
# dangling references as fatal without this.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Keep the enum names used as persisted keys. Provider, Correction and the
# rest are stored by `name` in SharedPreferences, so renaming them would
# silently reset the user's settings on first launch of a release build.
-keepclassmembers enum com.tyvo.keyboard.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Compose keeps its own rules via the library's consumer file; nothing extra
# is needed here.
