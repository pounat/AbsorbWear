# Keep WearableListenerService entry points so Play Services can dispatch
# DataItem/Message callbacks at runtime.
-keep class com.barnabas.absorb.wear.sync.** { *; }

# kotlinx.serialization keeps the @Serializable companions used at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.barnabas.absorb.wear.**$$serializer { *; }
-keepclassmembers class com.barnabas.absorb.wear.** {
    *** Companion;
}
-keepclasseswithmembers class com.barnabas.absorb.wear.** {
    kotlinx.serialization.KSerializer serializer(...);
}
