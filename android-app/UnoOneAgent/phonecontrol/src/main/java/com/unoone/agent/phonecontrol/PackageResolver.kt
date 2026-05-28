package com.unoone.agent.phonecontrol

object PackageResolver {
    fun resolveAppName(name: String): String? {
        return when (name.lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "gmail" -> "com.google.android.gmail"
            "calendar" -> "com.google.android.calendar"
            "camera" -> "com.android.camera"
            "settings" -> "com.android.settings"
            "chrome" -> "com.android.chrome"
            "youtube" -> "com.google.android.youtube"
            "maps" -> "com.google.android.apps.maps"
            "play store" -> "com.android.vending"
            "messages" -> "com.google.android.apps.messaging"
            else -> null
        }
    }
}
