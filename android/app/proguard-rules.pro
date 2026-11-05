# ProGuard / R8 Rules for Hexnet Android Application
# Preserve Bouncy Castle cryptographic providers and algorithms
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Preserve Room database entities, DAOs, and migrations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Android KeyStore integration
-keepclassmembers class android.security.keystore.** { *; }
