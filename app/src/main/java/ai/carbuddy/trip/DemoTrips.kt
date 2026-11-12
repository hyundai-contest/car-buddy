package ai.carbuddy.trip

/**
 * 체험 모드 trips, remembered outside Room so that a demo cut short by an ignition-off is still
 * cleaned up on the next start. Production: SharedPreferences (DI); tests: in memory.
 */
interface DemoTrips {
    fun add(tripId: String)
    fun all(): Set<String>
    fun clear()

    class InMemory : DemoTrips {
        private val ids = mutableSetOf<String>()
        @Synchronized override fun add(tripId: String) { ids += tripId }
        @Synchronized override fun all(): Set<String> = ids.toSet()
        @Synchronized override fun clear() = ids.clear()
    }

    class Prefs(private val prefs: android.content.SharedPreferences) : DemoTrips {
        @Synchronized override fun add(tripId: String) { prefs.edit().putStringSet(KEY, all() + tripId).apply() }
        override fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
        @Synchronized override fun clear() { prefs.edit().remove(KEY).apply() }
        private companion object { const val KEY = "demo_trips" }
    }
}

/** What the 체험 banner shows. */
data class DemoStatus(val scenario: String, val stoppedByMotion: Boolean = false)
