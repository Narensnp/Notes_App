# Release builds are not minified (isMinifyEnabled = false), so no rules are needed.
# If you enable R8 later, keep kotlinx.serialization metadata:
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.layers.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.layers.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
