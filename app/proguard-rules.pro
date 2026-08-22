# ACG Compass ProGuard / R8 rules.
# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep Room generated code.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Retrofit / OkHttp standard keeps are provided by their consumer rules.

# Tink（EncryptedSharedPreferences 的依赖）引用了仅编译期存在的 errorprone 注解；开启 R8 时不应因此失败。
-dontwarn com.google.errorprone.annotations.**
