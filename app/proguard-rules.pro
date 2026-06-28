# KissLink ProGuard / R8 rules
#
# AGP + 各函式庫(Compose、Room、Material 等)已內建 consumer 規則，
# 一般無需額外保留。以下為保險用的最小規則。

# 保留行號，方便對應 crash stack（移除原始檔名以縮減）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room：實體/DAO 由產生碼存取，預設規則已涵蓋；此處不額外處理。
# Jetpack Compose：compose 函式庫自帶 keep 規則，無需手動保留。

# OkHttp/Okio: javax.annotation 僅供註解，安全忽略
-dontwarn javax.annotation.**

# Hilt：DI 元件由 kapt/ksp 產生，需讓 R8 能看到產生出的注入點。
# 大部分規則已由 hilt-android.aar 內建 consumer 規則處理；
# 下方只保留仍需顯式聲明的 Hilt 入口點介面。
-keep class dagger.hilt.** { *; }
-keep interface dagger.hilt.** { *; }
-keepclassmembers class * {
    @dagger.hilt.android.AndroidEntryPoint *;
}

# Room：Entity/DAO 由產生碼反射存取；room-runtime 已內建 consumer 規則，此處備份。
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Kotlin coroutines：R8 移除 internal DebugMetadata 可能影響 stacktrace 可讀性。
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# 保留 Parcelable：PairingToken/GroupCredential 透過 Bundle 傳遞時不能被改名。
-keepclassmembers class com.kisslink.** implements android.os.Parcelable {
    static android.os.Parcelable$Creator CREATOR;
}
