package app.omalauncher

import android.content.Context

/** The apps pinned to the clear page, in the order they were pinned. */
class Favourites(context: Context) {
    private val prefs = context.getSharedPreferences("favourites", Context.MODE_PRIVATE)

    fun load(): List<String> = prefs.getString("keys", "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun save(keys: List<String>) = prefs.edit().putString("keys", keys.joinToString("\n")).apply()
}

/** When each app was last opened from the launcher, so recent apps can lead the list. */
class Recents(context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun load(): Map<String, Long> = prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()

    fun record(key: String) = prefs.edit().putLong(key, System.currentTimeMillis()).apply()
}
