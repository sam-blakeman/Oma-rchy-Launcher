package dev.samblakeman.omarchylauncher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * One wallpaper belonging to a theme: bundled in the APK, a file on the device, or one of
 * Omarchy's own wallpapers that is downloaded into [Remote.cache] the first time it's shown.
 */
sealed interface Wallpaper {
    data class Asset(val path: String) : Wallpaper
    data class OnDisk(val file: File) : Wallpaper
    data class Remote(val url: String, val cache: File) : Wallpaper
}

/** An Omarchy theme reduced to the handful of colours the launcher draws with. */
data class Theme(
    val id: String,
    val name: String,
    val user: Boolean,
    val light: Boolean,
    val bg: Color,
    val barBg: Color,
    val fg: Color,
    val dim: Color,
    val border: Color,
    val accent: Color,
    val backgrounds: List<Wallpaper>,
) {
    val title: String
        get() = name.split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    companion object {
        /** Used before themes have loaded, and if the theme folder is somehow empty. */
        val Fallback by lazy { parse("fallback", "tokyo-night", false, "", emptyList()) }

        /**
         * Reads an Omarchy colors.toml. Handles both the current format (mode, muted,
         * dark_foreground, ...) and the older one that only has accent, foreground,
         * background and color0-15, filling the gaps by blending.
         */
        fun parse(id: String, name: String, user: Boolean, toml: String, backgrounds: List<Wallpaper>): Theme {
            val values = LINE.findAll(toml).associate { it.groupValues[1] to it.groupValues[2] }
            fun color(vararg keys: String): Color? = keys.firstNotNullOfOrNull { values[it]?.let(::parseHex) }

            val bg = color("background") ?: Color(0xFF1A1B26)
            val fg = color("foreground") ?: Color(0xFFA9B1D6)
            return Theme(
                id = id,
                name = name,
                user = user,
                light = values["mode"]?.let { it == "light" } ?: (bg.luminance() > 0.5f),
                bg = bg,
                barBg = color("dark_background") ?: bg,
                fg = fg,
                dim = color("dark_foreground") ?: lerp(fg, bg, 0.5f),
                border = color("muted") ?: lerp(fg, bg, 0.7f),
                accent = color("accent", "blue", "color4") ?: fg,
                backgrounds = backgrounds,
            )
        }

        private val LINE = Regex("""(?m)^\s*([A-Za-z0-9_]+)\s*=\s*"([^"]*)"""")

        private fun parseHex(raw: String): Color? {
            val hex = raw.trim().removePrefix("#")
            val rgb = when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("")
                6 -> hex
                8 -> hex.take(6) // rrggbbaa: ignore alpha
                else -> return null
            }
            return rgb.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        }
    }
}

class ThemeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)

    /** User themes live here, one folder each, laid out like an Omarchy theme. */
    val userDir: File = File(context.getExternalFilesDir(null), "themes").apply { mkdirs() }

    var currentId: String
        get() = prefs.getString("current", null) ?: "stock:tokyo-night"
        set(value) = prefs.edit().putString("current", value).apply()

    fun backgroundIndex(theme: Theme): Int = prefs.getInt("bg:${theme.id}", 0)

    fun setBackgroundIndex(theme: Theme, index: Int) = prefs.edit().putInt("bg:${theme.id}", index).apply()

    suspend fun loadAll(): List<Theme> = withContext(Dispatchers.IO) {
        val assets = context.assets
        val stock = assets.list("themes").orEmpty().mapNotNull { name ->
            val toml = runCatching {
                assets.open("themes/$name/colors.toml").use { it.readBytes().decodeToString() }
            }.getOrNull() ?: return@mapNotNull null
            val bundled = assets.list("themes/$name/backgrounds").orEmpty().sorted()
                .map { Wallpaper.Asset("themes/$name/backgrounds/$it") }
            Theme.parse("stock:$name", name, false, toml, bundled.ifEmpty { remoteWallpapers(name) })
        }
        val user = userDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                val toml = File(dir, "colors.toml").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
                val backgrounds = File(dir, "backgrounds").listFiles().orEmpty()
                    .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                    .sortedBy { it.name }
                    .map { Wallpaper.OnDisk(it) }
                Theme.parse("user:${dir.name}", dir.name, true, toml, backgrounds)
            }
        (stock + user).sortedBy { it.name }
    }

    /** Downloaded Omarchy wallpapers, one folder per theme, each with a list.txt of what exists. */
    private val remoteDir = File(context.filesDir, "wallpapers")

    private fun remoteWallpapers(name: String): List<Wallpaper> {
        val dir = File(remoteDir, name)
        val list = File(dir, "list.txt").takeIf { it.isFile } ?: return emptyList()
        return list.readLines().mapNotNull { line ->
            val (file, url) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            Wallpaper.Remote(url, File(dir, file))
        }
    }

    /**
     * For a stock theme with no wallpapers yet, fetches the list of its wallpapers from
     * Omarchy's GitHub repo and returns the theme with them filled in, or null if that
     * isn't possible right now (offline, not a stock theme, or not in the repo).
     */
    suspend fun fetchStockWallpapers(theme: Theme): Theme? = withContext(Dispatchers.IO) {
        if (theme.user || theme.backgrounds.isNotEmpty()) return@withContext null
        runCatching {
            val connection = URL("$OMARCHY_API/themes/${theme.name}/backgrounds").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Oma-rchy-Launcher")
            try {
                if (connection.responseCode != 200) return@runCatching null
                val entries = org.json.JSONArray(connection.inputStream.bufferedReader().readText())
                val lines = (0 until entries.length()).map { entries.getJSONObject(it) }
                    .filter { it.optString("type") == "file" }
                    .map { it.getString("name") to it.getString("download_url") }
                    .filter { (name, _) -> name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
                    .sortedBy { it.first }
                    .map { (name, url) -> "$name\t$url" }
                if (lines.isEmpty()) return@runCatching null
                val dir = File(remoteDir, theme.name).apply { mkdirs() }
                File(dir, "list.txt").writeText(lines.joinToString("\n"))
                theme.copy(backgrounds = remoteWallpapers(theme.name))
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** Makes sure a wallpaper is readable on the device, downloading it first if needed. */
    private fun local(wallpaper: Wallpaper): Wallpaper? {
        if (wallpaper !is Wallpaper.Remote) return wallpaper
        if (!wallpaper.cache.isFile) {
            val partial = File(wallpaper.cache.path + ".part")
            val connection = URL(wallpaper.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            try {
                if (connection.responseCode != 200) return null
                connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                if (!partial.renameTo(wallpaper.cache)) return null
            } catch (e: java.io.IOException) {
                return null
            } finally {
                partial.delete()
                connection.disconnect()
            }
        }
        return Wallpaper.OnDisk(wallpaper.cache)
    }

    private fun open(wallpaper: Wallpaper): InputStream = when (wallpaper) {
        is Wallpaper.Asset -> context.assets.open(wallpaper.path)
        is Wallpaper.OnDisk -> wallpaper.file.inputStream()
        is Wallpaper.Remote -> error("download it first")
    }

    /** Decodes a wallpaper, downsampled so huge originals don't exhaust memory. */
    suspend fun loadWallpaper(wallpaper: Wallpaper, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        val ready = local(wallpaper) ?: return@withContext null
        fun open(): InputStream = open(ready)
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
            open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
    }

    /**
     * Sets Android's home and lock screen wallpaper to a theme's background, or to the
     * theme's plain background colour when it has no images. Returns whether it worked.
     */
    suspend fun setSystemWallpaper(theme: Theme, index: Int): Boolean = withContext(Dispatchers.IO) {
        val manager = WallpaperManager.getInstance(context)
        val both = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        runCatching {
            val wallpaper = theme.backgrounds.getOrNull(index.mod(theme.backgrounds.size.coerceAtLeast(1)))
            when (val ready = wallpaper?.let(::local)) {
                null -> if (wallpaper != null) error("wallpaper unavailable") else {
                    val plain = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    plain.eraseColor(theme.bg.toArgb())
                    manager.setBitmap(plain, null, true, both)
                }
                else -> open(ready).use { manager.setStream(it, null, true, both) }
            }
        }.isSuccess
    }

    /**
     * Installs a theme from a GitHub repo, the same way `omarchy theme install` takes a
     * git URL. Downloads the repo's default branch as a zip and keeps only colors.toml
     * and the top-level images in backgrounds/. Returns the installed theme's id.
     */
    suspend fun installFromGithub(rawUrl: String): String = withContext(Dispatchers.IO) {
        val match = GITHUB_REPO.find(rawUrl.trim()) ?: error("not a GitHub repo URL")
        val (owner, repo) = match.destructured
        val name = repo.removePrefix("omarchy-").removeSuffix("-theme").lowercase()

        val connection = URL("https://github.com/$owner/$repo/archive/HEAD.zip").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val staging = File(userDir, ".$name.partial")
        try {
            if (connection.responseCode != 200) error("GitHub answered ${connection.responseCode}")
            staging.deleteRecursively()
            File(staging, "backgrounds").mkdirs()
            var hasColors = false
            ZipInputStream(connection.inputStream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // Entries look like "<repo>-<branch>/backgrounds/1.jpg"; drop the root folder.
                    val path = entry.name.split('/').drop(1)
                    val target = when {
                        path == listOf("colors.toml") -> File(staging, "colors.toml").also { hasColors = true }
                        path.size == 2 && path[0] == "backgrounds" &&
                            path[1].substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS ->
                            File(staging, "backgrounds/${path[1]}")
                        else -> continue
                    }
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            if (!hasColors) error("no colors.toml in that repo")
            val destination = File(userDir, name)
            destination.deleteRecursively()
            if (!staging.renameTo(destination)) error("could not save the theme")
            "user:$name"
        } finally {
            staging.deleteRecursively()
            connection.disconnect()
        }
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        const val OMARCHY_API = "https://api.github.com/repos/omacom/omarchy/contents"
        val GITHUB_REPO = Regex("""github\.com[/:]([\w.-]+)/([\w.-]+?)(?:\.git)?/?$""")
    }
}
