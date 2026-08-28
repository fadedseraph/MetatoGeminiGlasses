# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to the default flags
# in proguard-android-optimize.txt.

-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
