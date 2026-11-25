# Keep AndroidViewModel constructors used by viewModel()
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}

# Keep Honor framework-magic reflection targets and suppress warnings
-dontwarn com.hihonor.**
-keep class com.app.magiccapsule.capsule.MagicCapsuleBridge { *; }

# Keep Shizuku API, Provider, and reflective newProcess calls under R8
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
