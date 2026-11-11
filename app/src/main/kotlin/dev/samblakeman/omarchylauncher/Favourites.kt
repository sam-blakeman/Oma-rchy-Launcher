package dev.samblakeman.omarchylauncher

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

/** Per-app tweaks: apps hidden from the launcher, and names the user has given apps. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()

    fun setHidden(keys: Set<String>) = prefs.edit().putStringSet("hidden", keys).apply()

    fun names(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith(NAME) }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix(NAME) to it } }
        .toMap()

    /** A blank name goes back to the app's own. */
    fun setName(key: String, name: String) {
        val edit = prefs.edit()
        if (name.isBlank()) edit.remove(NAME + key) else edit.putString(NAME + key, name.trim())
        edit.apply()
    }

    private companion object {
        const val NAME = "name:"
    }
}
