# kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.axon.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.axon.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
