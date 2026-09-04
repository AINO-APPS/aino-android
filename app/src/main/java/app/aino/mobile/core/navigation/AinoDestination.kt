package app.aino.mobile.core.navigation

enum class AinoDestination(val label: String) {
    Home("Home"),
    Activity("Activity"),
    Profile("Profile"),
}

fun destinationFor(label: String): AinoDestination? =
    AinoDestination.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
