# ProGuard/R8 kuralları — release küçültme ve gizlemesi açıkken (isMinifyEnabled=true)
# uygulamanın kırılmaması için gereken korumalar.

# --- Native / JNI köprüsü ---
# JNI fonksiyonları Java_<paket>_<sınıf>_<metot> adıyla çözülür. R8 sınıf ya da
# metot adını değiştirirse native motor (Kalman + FSM) tamamen kopar.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.example.yolarkadasim.service.TrackingService { *; }

# --- Gson (reflection ile serialize edilen model) ---
# FavoritesStore, FavoriteStop'u TypeToken ile serialize/deserialize eder; alan
# adları korunmalı, aksi halde favori duraklar okunamaz.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class com.example.yolarkadasim.data.FavoritesStore$FavoriteStop { <fields>; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- osmdroid ---
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# --- Çökme izlerinin okunabilir kalması ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
