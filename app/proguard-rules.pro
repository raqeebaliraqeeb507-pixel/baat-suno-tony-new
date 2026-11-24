# Keep OkHttp and Kotlin Coroutines classes intact for release builds
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature,InnerClasses,EnclosingMethod
