# ACG Compass ProGuard / R8 rules.
# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep Room generated code.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Retrofit / OkHttp standard keeps are provided by their consumer rules.
