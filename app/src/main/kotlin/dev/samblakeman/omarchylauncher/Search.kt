package dev.samblakeman.omarchylauncher

import android.content.Intent
import android.provider.Settings
import java.math.BigDecimal
import java.math.MathContext

// Extra search results beyond apps, in the spirit of Omarchy's Walker launcher:
// a calculator, shortcuts into Android settings, and a web search fallback.

/**
 * Evaluates arithmetic like `12*7` or `(3+4)^2`, with `%` as remainder. Returns null for
 * anything that isn't a complete expression with at least one operator.
 */
object Calculator {
    fun evaluate(input: String): Double? {
        val text = input.replace(" ", "").replace('x', '*').replace('×', '*').replace('÷', '/').replace(',', '.')
        if (text.isEmpty() || text.none { it.isDigit() } || text.none { it in "+-*/^%" }) return null
        if (text.all { it.isDigit() || it == '.' || it == '-' } && text.indexOf('-') <= 0) return null
        return runCatching { Parser(text).parse() }.getOrNull()?.takeIf { it.isFinite() }
    }

    fun format(value: Double): String =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
        }

    /** Recursive descent: expression = term (+|- term)*, term = power (*|/|% power)*, power = unary (^ power)? */
    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val value = expression()
            require(i == s.length)
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (i < s.length && s[i] in "+-") value = if (s[i++] == '+') value + term() else value - term()
            return value
        }

        private fun term(): Double {
            var value = power()
            while (i < s.length && s[i] in "*/%") {
                value = when (s[i++]) {
                    '*' -> value * power()
                    '/' -> value / power()
                    else -> value % power()
                }
            }
            return value
        }

        private fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(base, power())
            }
            return base
        }

        private fun unary(): Double {
            if (i < s.length && s[i] == '-') {
                i++
                return -unary()
            }
            if (i < s.length && s[i] == '(') {
                i++
                val value = expression()
                require(i < s.length && s[i] == ')')
                i++
                return value
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }
}

/** A page of Android settings reachable by typing one of its keywords. */
data class SettingShortcut(val label: String, val action: String, val keywords: List<String>)

val SETTING_SHORTCUTS = listOf(
    SettingShortcut("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, listOf("wifi", "wi-fi", "wlan", "internet")),
    SettingShortcut("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth", "bt", "pair")),
    SettingShortcut("Display", Settings.ACTION_DISPLAY_SETTINGS, listOf("display", "brightness", "screen", "dark mode")),
    SettingShortcut("Sound", Settings.ACTION_SOUND_SETTINGS, listOf("sound", "volume", "audio", "ringtone")),
    SettingShortcut("Battery", Intent.ACTION_POWER_USAGE_SUMMARY, listOf("battery", "power", "charging")),
    SettingShortcut("Apps", Settings.ACTION_APPLICATION_SETTINGS, listOf("apps", "applications", "uninstall")),
    SettingShortcut("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("storage", "space", "disk")),
    SettingShortcut("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("location", "gps")),
    SettingShortcut("Network", Settings.ACTION_WIRELESS_SETTINGS, listOf("network", "mobile", "hotspot", "vpn")),
    SettingShortcut("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("airplane", "flight")),
    SettingShortcut("Date & time", Settings.ACTION_DATE_SETTINGS, listOf("date", "time", "timezone")),
    SettingShortcut("Language", Settings.ACTION_LOCALE_SETTINGS, listOf("language", "locale")),
    SettingShortcut("Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS, listOf("keyboard", "input")),
    SettingShortcut("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("accessibility")),
    SettingShortcut("Security", Settings.ACTION_SECURITY_SETTINGS, listOf("security", "lock", "password", "fingerprint")),
    SettingShortcut("Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, listOf("default", "launcher", "home app", "assistant")),
    SettingShortcut("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, listOf("developer", "adb", "usb debugging")),
    SettingShortcut("Settings", Settings.ACTION_SETTINGS, listOf("settings")),
)

/** Settings pages whose keywords start with the query, once at least two letters are typed. */
fun settingMatches(query: String): List<SettingShortcut> {
    val needle = query.trim().lowercase()
    if (needle.length < 2) return emptyList()
    return SETTING_SHORTCUTS.filter { shortcut -> shortcut.keywords.any { it.startsWith(needle) } }
}

/** An AI app the launcher can hand questions to, and open as an assistant. */
data class AiApp(val name: String, val packageName: String)

/** Known AI apps, in the order offered. Only the installed ones are shown. */
val AI_APPS = listOf(
    AiApp("Claude", "com.anthropic.claude"),
    AiApp("ChatGPT", "com.openai.chatgpt"),
    AiApp("Gemini", "com.google.android.apps.bard"),
    AiApp("Grok", "ai.x.grok"),
    AiApp("Grok Bot", "ai.x.grok.bot"),
)
