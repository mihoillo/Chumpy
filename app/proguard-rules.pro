# The gomobile AAR ships its own consumer rules; these are the same, restated in case
# the local-AAR path ever stops applying them.
-keep class go.** { *; }
-keep class libv2ray.** { *; }

# Room entities are reflected over by the compiler-generated code only; keep the
# kotlinx-serialization core off the shrinker's blind spots.
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class slop.miho.aurora.xray.**$$serializer { *; }
-keepclassmembers class slop.miho.aurora.xray.** { *** Companion; }
-keepclasseswithmembers class slop.miho.aurora.xray.** { kotlinx.serialization.KSerializer serializer(...); }
