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
