# LightTip — R8 keep rules.
#
# This file used to contain the single line "proguard placeholder", which is not a valid
# ProGuard option and would have failed the build the moment minification was switched on.
#
# Most of what this app depends on — Compose, CameraX, OkHttp, coroutines, Room — ships its
# own consumer rules inside the AAR, so R8 already knows how to shrink them. What follows is
# only the part no library can know about: the places where *this* app reaches for a class by
# name at runtime, where the name is the thing R8 would otherwise rename away.

# ---------------------------------------------------------------- Room
#
# Room does not call the generated implementation directly. `Room.databaseBuilder(ctx,
# TipDatabase::class.java, …)` takes the *abstract* class and then looks up its companion by
# string — "TipDatabase" + "_Impl" — through Class.forName. Rename either half and the builder
# throws "cannot find implementation for TipDatabase" on the first database access, which in
# this app is the first time you open a saved bill.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.gios.lighttip.data.**_Impl { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static <fields>;
}
# Entities and DAOs are read reflectively by the generated code.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ---------------------------------------------------------------- zxing
#
# zxing-android-embedded starts CaptureActivity from a manifest entry and picks its decoder
# implementation by class name. Neither reference survives shrinking on its own.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---------------------------------------------------------------- enums
#
# R8 usually preserves valueOf correctly, but it only has to be wrong once for a saved split
# to come back empty.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------- Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---------------------------------------------------------------- diagnostics
#
# Keep line numbers so a stack trace pasted into light-reports is still readable, and pin the
# source file attribute to a fixed string so it does not leak a local path.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
