# kotlinx.serialization ships its own consumer rules; keep our DTOs' serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.breeze.weather.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
