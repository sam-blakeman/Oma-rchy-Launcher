package dev.samblakeman.omarchylauncher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.media.AudioManager
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val LocalTheme = compositionLocalOf { Theme.Fallback }

private val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** Base text style in the current theme's foreground colour. */
@Composable
private fun mono(size: Int = 16) =
    TextStyle(fontFamily = JetBrainsMono, color = LocalTheme.current.fg, fontSize = size.sp)

/** False for everything behind an open overlay, so focus can't wander underneath it. */
private val LocalInteractive = staticCompositionLocalOf { true }

/** False on the clear page, where keys only flip pages, so the bar never shows a focus highlight. */
private val LocalBarFocusable = staticCompositionLocalOf { true }

// Enough pages that swiping never reaches an end in practice. Kept small on purpose:
// a focus search that runs off a page walks every page after it looking for a target.
private const val PAGE_COUNT = 200

/** How many recently opened apps lead the app list. */
private const val RECENT_COUNT = 8

/**
 * One entry in the app list. Usually an app; search also adds actions (a calculator answer,
 * a settings page, a web search), which carry an icon and have no app menu.
 */
data class AppEntry(val key: String, val label: String, val icon: String? = null, val launch: () -> Unit) {
    val packageName: String get() = key.substringBefore('/')
    val isAction: Boolean get() = key.startsWith(ACTION_PREFIX)
}

private const val ACTION_PREFIX = "action:"

sealed interface Command {
    data class Type(val char: Char) : Command
    data object Backspace : Command
    data object Search : Command
    data object Themes : Command
    data object NextBackground : Command
    data object PreviousPage : Command
    data object NextPage : Command
    data object Favourites : Command
    data object Menu : Command
}

/** Which panel, if any, is open over the home screen. */
private sealed interface Overlay {
    data object Menu : Overlay
    data class AppMenu(val app: AppEntry) : Overlay
    data class Rename(val app: AppEntry) : Overlay
    data object Hidden : Overlay
}

class MainActivity : ComponentActivity() {
    // Bumped on every Home press so the UI can reset itself.
    private var homePresses by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Home(homePresses) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePresses++
    }

    /** Keyboard and controller input that no focused element claimed, for the UI to act on. */
    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 32)

    /**
     * Set by the UI while the clear wallpaper page is showing. There, Left and Right flip
     * pages and Enter or A opens the app list, whatever happens to hold focus.
     */
    var arrowsFlipPages = false

    /** Set by the UI while the clear page is showing, whether or not a favourite has focus. */
    var clearPage = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        val flips = left || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode in OPEN_KEYS
        if (arrowsFlipPages && (flips || down)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                commands.tryEmit(
                    when {
                        down -> Command.Favourites // Down steps onto the pinned apps.
                        left -> Command.PreviousPage
                        else -> Command.NextPage
                    },
                )
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Esc and controller B don't reliably arrive as Back, so do it here.
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        val modified = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        val char = event.unicodeChar
        val command = when {
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT -> Command.Menu
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_M -> Command.Menu
            keyCode == KeyEvent.KEYCODE_BUTTON_X -> Command.Search
            keyCode == KeyEvent.KEYCODE_BUTTON_Y -> Command.Themes
            keyCode == KeyEvent.KEYCODE_BUTTON_L1 -> Command.PreviousPage
            keyCode == KeyEvent.KEYCODE_BUTTON_R1 -> Command.NextPage
            keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR -> Command.NextBackground
            // Moving past either end of the favourites row flips the page.
            clearPage && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> Command.PreviousPage
            clearPage && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> Command.NextPage
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_T -> Command.Themes
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_B -> Command.NextBackground
            modified -> null
            keyCode == KeyEvent.KEYCODE_DEL -> Command.Backspace
            char >= 32 && char and KeyCharacterMap.COMBINING_ACCENT == 0 -> Command.Type(char.toChar())
            else -> null
        }
        if (command != null) {
            commands.tryEmit(command)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Our bar replaces Android's status bar; swiping down from the top still reveals it.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Pulls down the notification shade. Uses a hidden API, so it may quietly do nothing. */
    @android.annotation.SuppressLint("WrongConstant")
    fun expandNotifications() {
        runCatching {
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(getSystemService("statusbar"))
        }
    }

    private companion object {
        val OPEN_KEYS = setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A)
    }

    fun openAppInfo(packageName: String) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Searches the web in the default browser. A web-search intent would show a chooser on
     * devices where several apps claim it, so this opens the search page directly.
     */
    fun webSearch(query: String) {
        val url = android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun copyToClipboard(text: String) {
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("result", text))
    }

    fun open(action: String) {
        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun showVolumePanel() {
        getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    /** Dark icons on light themes, light icons on dark ones. */
    fun styleSystemBars(light: Boolean) {
        val style = if (light) {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

private suspend fun loadApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val user = Process.myUserHandle()
    launcherApps.getActivityList(null, user)
        .filter { it.applicationInfo.packageName != context.packageName }
        .map { info ->
            AppEntry(info.componentName.flattenToShortString(), info.label.toString()) {
                launcherApps.startMainActivity(info.componentName, user, null, null)
            }
        }
        .sortedBy { it.label.lowercase() }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Home(homePresses: Long) {
    val context = LocalContext.current
    val store = remember { ThemeStore(context.applicationContext) }

    var themes by remember { mutableStateOf(emptyList<Theme>()) }
    var themeId by remember { mutableStateOf(store.currentId) }
    val theme = themes.firstOrNull { it.id == themeId }
        ?: themes.firstOrNull { it.id == "stock:tokyo-night" }
        ?: themes.firstOrNull()
        ?: Theme.Fallback
    var backgroundIndex by remember(theme.id) { mutableIntStateOf(store.backgroundIndex(theme)) }

    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var field by remember { mutableStateOf(TextFieldValue()) }
    val query = field.text
    var pickerOpen by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // True while any panel is over the home screen; the pages underneath then ignore input.
    val blocked = pickerOpen || overlay != null
    val searchFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val favouriteFocus = remember { FocusRequester() }
    val favouriteStore = remember { Favourites(context.applicationContext) }
    var favourites by remember { mutableStateOf(favouriteStore.load()) }
    var favouritesFocused by remember { mutableStateOf(false) }
    val recentStore = remember { Recents(context.applicationContext) }
    val appPrefs = remember { AppPrefs(context.applicationContext) }
    var hidden by remember { mutableStateOf(appPrefs.hidden()) }
    var names by remember { mutableStateOf(appPrefs.names()) }
    var recents by remember { mutableStateOf(recentStore.load()) }
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = context as? MainActivity
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // An endless strip of pages alternating clear wallpaper (even) and app list (odd),
    // so a swipe in either direction from one always reaches the other.
    val pager = rememberPagerState(initialPage = PAGE_COUNT / 2) { PAGE_COUNT }
    val onApps = pager.currentPage % 2 != 0

    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun setQuery(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
    }


    fun nextBackground() {
        if (theme.backgrounds.isEmpty()) return
        backgroundIndex = (backgroundIndex + 1).mod(theme.backgrounds.size)
        store.setBackgroundIndex(theme, backgroundIndex)
    }

    fun togglePin(app: AppEntry) {
        favourites = if (app.key in favourites) favourites - app.key else favourites + app.key
        favouriteStore.save(favourites)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // What the launcher shows: renamed where the user chose a name, hidden apps left out.
    val visible = remember(apps, hidden, names) {
        apps.filter { it.key !in hidden }
            .map { app -> names[app.key]?.let { app.copy(label = it) } ?: app }
            .sortedBy { it.label.lowercase() }
    }
    val pinned = remember(visible, favourites) { favourites.mapNotNull { key -> visible.firstOrNull { it.key == key } } }

    fun setHidden(app: AppEntry, hide: Boolean) {
        hidden = if (hide) hidden + app.key else hidden - app.key
        appPrefs.setHidden(hidden)
    }

    fun rename(app: AppEntry, name: String) {
        appPrefs.setName(app.key, name)
        names = appPrefs.names()
    }

    LaunchedEffect(Unit) { themes = store.loadAll() }
    // Stock themes' wallpapers come from Omarchy's GitHub repo, listed the first time a theme is used.
    LaunchedEffect(theme.id, themes.isEmpty()) {
        if (themes.isEmpty()) return@LaunchedEffect
        store.fetchStockWallpapers(theme)?.let { updated ->
            themes = themes.map { if (it.id == updated.id) updated else it }
        }
    }
    // Android's own wallpaper follows the launcher's, so the lock screen and recents match.
    // Checked on start and on every change, so a missed or failed update is put right later.
    val screen = LocalConfiguration.current.let { config ->
        val density = LocalDensity.current.density
        (config.screenWidthDp * density).toInt() to (config.screenHeightDp * density).toInt()
    }
    LaunchedEffect(theme.id, backgroundIndex, theme.backgrounds.size, themes.isEmpty(), screen) {
        if (themes.isEmpty()) return@LaunchedEffect
        // A stock theme whose wallpaper list hasn't arrived yet is handled when it does.
        if (!theme.user && theme.backgrounds.isEmpty()) return@LaunchedEffect
        val key = store.systemWallpaperKey(theme, backgroundIndex)
        if (key == store.systemWallpaper) return@LaunchedEffect
        delay(500) // Let quick runs of changes settle before rendering.
        // A download can fail on a patchy connection, so try a few times before waiting
        // for the next start or change.
        for (attempt in 1..3) {
            if (store.setSystemWallpaper(theme, backgroundIndex, screen.first, screen.second)) break
            delay(5_000L * attempt)
        }
    }
    LaunchedEffect(homePresses) {
        dismissKeyboard()
        setQuery("")
        pickerOpen = false
        overlay = null
        apps = loadApps(context)
        // Home always lands on the clear wallpaper page.
        if (pager.currentPage % 2 != 0) {
            val clear = pager.currentPage - 1
            // A focus change can interrupt the slide; if so, finish the move without it.
            runCatching { pager.animateScrollToPage(clear) }
            if (pager.currentPage != clear) pager.scrollToPage(clear)
        }
    }
    // Clear pages are identical, so quietly jump back to the middle of the strip
    // whenever it has drifted far to one side.
    LaunchedEffect(pager.settledPage) {
        val page = pager.settledPage
        if (page % 2 == 0 && kotlin.math.abs(page - PAGE_COUNT / 2) > PAGE_COUNT / 4) pager.scrollToPage(PAGE_COUNT / 2)
    }
    // Leaving the app list, by swipe or otherwise, resets it.
    LaunchedEffect(onApps) {
        if (!onApps) {
            dismissKeyboard()
            setQuery("")
        }
    }
    SideEffect {
        activity?.clearPage = !onApps && !blocked
        activity?.arrowsFlipPages = !onApps && !blocked && !favouritesFocused
    }

    fun flipPage(delta: Int) {
        dismissKeyboard()
        scope.launch {
            pager.animateScrollToPage(pager.currentPage + delta)
            // Arriving at the app list by key or controller, start on the first app.
            // In touch mode this request is ignored.
            if (pager.currentPage % 2 != 0) runCatching { gridFocus.requestFocus() }
        }
    }
    LaunchedEffect(theme.light) { activity?.styleSystemBars(theme.light) }
    BackHandler {
        if (overlay != null) overlay = null
        else if (pickerOpen) pickerOpen = false
        else {
            dismissKeyboard()
            // Back first clears a search, then returns to the clear page.
            if (field.text.isNotEmpty()) setQuery("") else if (onApps) flipPage(-1)
        }
    }

    // Typing anywhere searches. On a controller: X search, Y themes, L1/R1 pages, R3 wallpaper.
    val latestCommand by androidx.compose.runtime.rememberUpdatedState<(Command) -> Unit> { command ->
        when (command) {
            Command.Menu -> {
                pickerOpen = false
                overlay = if (overlay == Overlay.Menu) null else Overlay.Menu
            }
            Command.Themes -> {
                overlay = null
                pickerOpen = !pickerOpen
            }
            Command.NextBackground -> nextBackground()
            Command.Favourites -> runCatching { favouriteFocus.requestFocus() }
            Command.PreviousPage -> if (!blocked) flipPage(-1)
            Command.NextPage -> if (!blocked) flipPage(1)
            else -> if (!blocked) {
                when (command) {
                    // Read the live text, not this composition's copy: fast typing can
                    // deliver several keys before the screen redraws.
                    is Command.Type -> if (command.char != ' ' || field.text.isNotEmpty()) setQuery(field.text + command.char)
                    Command.Backspace -> setQuery(field.text.dropLast(1))
                    else -> Unit
                }
                // From the clear page, slide the app list in before focusing its search box.
                scope.launch {
                    if (pager.currentPage % 2 == 0) pager.animateScrollToPage(pager.currentPage + 1)
                    runCatching { searchFocus.requestFocus() }
                    if (command == Command.Search) keyboard?.show()
                }
            }
        }
    }
    LaunchedEffect(activity) { activity?.commands?.collect { latestCommand(it) } }

    val shown = remember(visible, query, recents) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            // The last few apps opened come first, newest first; the rest stay alphabetical.
            val recent = visible.filter { it.key in recents }.sortedByDescending { recents[it.key] }.take(RECENT_COUNT)
            recent + (visible - recent.toSet())
        }
        else {
            val matches = visible.mapNotNull { app ->
                // Names starting with the query first, then word starts, then anywhere.
                val rank = when {
                    app.label.startsWith(needle, ignoreCase = true) -> 0
                    app.label.split(' ', '-').any { it.startsWith(needle, ignoreCase = true) } -> 1
                    app.label.contains(needle, ignoreCase = true) -> 2
                    else -> return@mapNotNull null
                }
                rank to app
            }.sortedBy { it.first }.map { it.second }

            // A sum comes first, so Enter copies its answer. Settings pages follow the apps,
            // and a web search is always last, so Enter falls through to it when nothing matches.
            val answer = Calculator.evaluate(needle)?.let { Calculator.format(it) }?.let { result ->
                AppEntry(ACTION_PREFIX + "calc", "= $result", "\uf1ec") { activity?.copyToClipboard(result) }
            }
            val settings = settingMatches(needle).map { shortcut ->
                AppEntry(ACTION_PREFIX + "setting:" + shortcut.action, shortcut.label + " settings", "\uf013") {
                    activity?.open(shortcut.action)
                }
            }
            val web = AppEntry(ACTION_PREFIX + "web", "Search web for \"$needle\"", "\uf0ac") { activity?.webSearch(needle) }
            listOfNotNull(answer) + matches + settings + web
        }
    }

    CompositionLocalProvider(LocalTheme provides theme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(theme.bg)
                // Tapping any empty space leaves the search box and closes the keyboard.
                .pointerInput(Unit) { detectTapGestures { dismissKeyboard() } },
        ) {
            WallpaperLayer(store, theme, backgroundIndex)

            CompositionLocalProvider(LocalInteractive provides !blocked) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                CompositionLocalProvider(LocalBarFocusable provides onApps) {
                    Bar(
                        backgroundLabel = if (theme.backgrounds.isEmpty()) null
                        else "bg ${backgroundIndex.mod(theme.backgrounds.size) + 1}/${theme.backgrounds.size}",
                        onMenu = { overlay = Overlay.Menu },
                        onTheme = { pickerOpen = true },
                        onNextBackground = ::nextBackground,
                    )
                }
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !blocked,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            // Arrow and Tab focus must not leave a page sideways: the search
                            // would run through every other page. Page flips handle that instead.
                            .focusProperties {
                                exit = { direction ->
                                    if (direction == FocusDirection.Up || direction == FocusDirection.Down) {
                                        FocusRequester.Default
                                    } else {
                                        FocusRequester.Cancel
                                    }
                                }
                            }
                            .focusGroup(),
                    ) {
                    // Only the page in view takes input. Otherwise focus can land on an app
                    // in the page sliding away, and the pager scrolls back to show it.
                    CompositionLocalProvider(
                        LocalInteractive provides (!blocked && page == pager.currentPage),
                    ) {
                        val launch: (AppEntry) -> Unit = { app ->
                            app.launch()
                            if (!app.isAction) {
                                recentStore.record(app.key)
                                recents = recentStore.load()
                            }
                            dismissKeyboard()
                            setQuery("")
                        }
                        if (page % 2 != 0) {
                            AppsPage(
                                apps = shown,
                                total = visible.size,
                                pinnedKeys = favourites,
                                field = field,
                                searchFocus = searchFocus,
                                gridFocus = gridFocus,
                                onQuery = { field = it },
                                onLaunch = launch,
                                onMenu = { overlay = Overlay.AppMenu(it) },
                            )
                        } else {
                            ClearPage(
                                favourites = pinned,
                                firstFocus = favouriteFocus,
                                onLaunch = launch,
                                onMenu = { overlay = Overlay.AppMenu(it) },
                                onFocus = { favouritesFocused = it },
                                onSwipeDown = { activity?.expandNotifications() },
                                onSwipeUp = { flipPage(1) },
                            )
                        }
                    }
                    }
                }
            }
            }

            if (pickerOpen) {
                ThemePicker(
                    themes = themes,
                    currentId = theme.id,
                    store = store,
                    onPick = {
                        themeId = it.id
                        store.currentId = it.id
                        pickerOpen = false
                    },
                    onInstalled = { id ->
                        themes = store.loadAll()
                        themeId = id
                        store.currentId = id
                    },
                    onDismiss = { pickerOpen = false },
                )
            }

            when (val open = overlay) {
                null -> Unit
                Overlay.Menu -> MenuPanel(
                    title = "menu",
                    onDismiss = { overlay = null },
                    items = listOf(
                        MenuItem("\uf00a", "Apps", "L1 / R1") {
                            overlay = null
                            if (!onApps) flipPage(1)
                        },
                        MenuItem("\uf002", "Search", "type / X") {
                            overlay = null
                            latestCommand(Command.Search)
                        },
                        MenuItem("\uf1fc", "Theme", "ctrl+t / Y") {
                            overlay = null
                            pickerOpen = true
                        },
                        MenuItem("\uf03e", "Next wallpaper", "ctrl+b / R3") { nextBackground() },
                        MenuItem("\uf0f3", "Notifications") {
                            overlay = null
                            activity?.expandNotifications()
                        },
                        MenuItem("\uf1eb", "Wi-Fi") {
                            overlay = null
                            activity?.open(Settings.ACTION_WIFI_SETTINGS)
                        },
                        MenuItem("\uf070", "Hidden apps", if (hidden.isEmpty()) null else "${hidden.size}") {
                            overlay = Overlay.Hidden
                        },
                        MenuItem("\uf013", "Android settings") {
                            overlay = null
                            activity?.open(Settings.ACTION_SETTINGS)
                        },
                    ),
                )
                is Overlay.AppMenu -> {
                    val app = open.app
                    val isPinned = app.key in favourites
                    MenuPanel(
                        title = app.label,
                        onDismiss = { overlay = null },
                        items = listOf(
                            MenuItem("\uf04b", "Open") {
                                overlay = null
                                recentStore.record(app.key)
                                recents = recentStore.load()
                                app.launch()
                            },
                            MenuItem("\uf08d", if (isPinned) "Unpin" else "Pin to home", "ctrl+p") {
                                togglePin(app)
                                overlay = null
                            },
                            MenuItem("\uf044", "Rename") { overlay = Overlay.Rename(app) },
                            MenuItem("\uf070", "Hide") {
                                setHidden(app, true)
                                overlay = null
                            },
                            MenuItem("\uf05a", "App info") {
                                overlay = null
                                activity?.openAppInfo(app.packageName)
                            },
                        ),
                    )
                }
                is Overlay.Rename -> RenamePanel(
                    app = open.app,
                    original = apps.firstOrNull { it.key == open.app.key }?.label ?: open.app.label,
                    onSave = { name ->
                        rename(open.app, name)
                        overlay = null
                    },
                    onDismiss = { overlay = null },
                )
                Overlay.Hidden -> {
                    val hiddenApps = apps.filter { it.key in hidden }
                        .map { app -> names[app.key]?.let { app.copy(label = it) } ?: app }
                        .sortedBy { it.label.lowercase() }
                    MenuPanel(
                        title = if (hiddenApps.isEmpty()) "no hidden apps" else "hidden apps, select to show",
                        onDismiss = { overlay = null },
                        items = hiddenApps.map { app ->
                            MenuItem("\uf06e", app.label) { setHidden(app, false) }
                        },
                    )
                }
            }
        }
    }
}

/** The app list: a search box over a grid, in a panel set inside gaps like a tiled window. */
@Composable
private fun AppsPage(
    apps: List<AppEntry>,
    total: Int,
    pinnedKeys: List<String>,
    field: TextFieldValue,
    searchFocus: FocusRequester,
    gridFocus: FocusRequester,
    onQuery: (TextFieldValue) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    val theme = LocalTheme.current
    val keyboard = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()
    val searching = field.text.isNotBlank()

    // Dragging the grid puts the on-screen keyboard away; a new search starts from the top.
    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions.collect { if (it is DragInteraction.Start) keyboard?.hide() }
    }
    LaunchedEffect(field.text) { gridState.scrollToItem(0) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .background(theme.bg.copy(alpha = 0.88f))
            .border(1.dp, theme.accent)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SearchBox(
            field = field,
            placeholder = "search $total apps",
            focus = searchFocus,
            onChange = onQuery,
            onGo = { apps.firstOrNull()?.let(onLaunch) },
        )
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 220.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(apps) { index, app ->
                // While searching, the first match is what Enter will open.
                AppCell(
                    app,
                    primary = searching && index == 0,
                    pinned = app.key in pinnedKeys,
                    onClick = { onLaunch(app) },
                    onMenu = { if (!app.isAction) onMenu(app) },
                    modifier = (if (index == 0) Modifier.focusRequester(gridFocus) else Modifier).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun WallpaperLayer(store: ThemeStore, theme: Theme, index: Int) {
    val wallpaper = theme.backgrounds.getOrNull(index.mod(theme.backgrounds.size.coerceAtLeast(1)))
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    val bitmap by produceState<android.graphics.Bitmap?>(null, wallpaper) {
        value = wallpaper?.let {
            store.loadWallpaper(it, (config.screenWidthDp * density).toInt(), (config.screenHeightDp * density).toInt())
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Bar(backgroundLabel: String?, onMenu: () -> Unit, onTheme: () -> Unit, onNextBackground: () -> Unit) {
    val theme = LocalTheme.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L * (60 - Calendar.getInstance().get(Calendar.SECOND)).coerceAtLeast(1))
        }
    }
    val clock = remember(now) { SimpleDateFormat("EEE dd MMM  HH:mm", Locale.getDefault()).format(now) }

    Box(
        Modifier
            .fillMaxWidth()
            .background(theme.barBg)
            .padding(horizontal = 12.dp),
    ) {
        val activity = LocalContext.current as? MainActivity
        val battery by batteryState()
        val volume by volumeState()
        val link by linkState()

        Row(Modifier.align(Alignment.CenterStart)) {
            Chip("\uf303", onMenu, color = theme.accent)
            Chip(theme.title, onTheme, color = theme.accent)
            if (backgroundLabel != null) Chip(backgroundLabel, onNextBackground, color = theme.dim)
        }
        // Tapping the clock pulls down notifications, since Android's own bar is hidden.
        Chip(clock, { activity?.expandNotifications() }, Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd)) {
            Chip(link.icon, { activity?.open(Settings.ACTION_WIFI_SETTINGS) })
            Chip("${volume.icon} ${volume.percent}%", { activity?.showVolumePanel() })
            Chip(
                "${battery.icon} ${battery.percent}%",
                { activity?.open(Intent.ACTION_POWER_USAGE_SUMMARY) },
                color = if (battery.percent <= 15 && !battery.charging) theme.accent else theme.fg,
            )
        }
    }
}

/** A piece of tappable bar text that highlights when a keyboard or controller focuses it. */
@Composable
private fun Chip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = LocalTheme.current.fg,
) {
    val theme = LocalTheme.current
    val focusable = LocalBarFocusable.current
    var focused by remember { mutableStateOf(false) }
    BasicText(
        text,
        style = mono().copy(color = if (focused) theme.bg else color),
        modifier = modifier
            .focusProperties { canFocus = focusable }
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) theme.accent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = LocalInteractive.current, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun SearchBox(
    field: TextFieldValue,
    placeholder: String,
    focus: FocusRequester,
    onChange: (TextFieldValue) -> Unit,
    onGo: () -> Unit,
) {
    val theme = LocalTheme.current
    val query = field.text
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BasicTextField(
            value = field,
            onValueChange = onChange,
            enabled = LocalInteractive.current,
            singleLine = true,
            textStyle = mono(18),
            cursorBrush = SolidColor(theme.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .focusRequester(focus)
                // A text field keeps arrow keys for itself; hand Down to the app grid.
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown
                    if (down) focusManager.moveFocus(FocusDirection.Down)
                    down
                }
                .onFocusChanged { focused = it.isFocused }
                .border(1.dp, if (focused) theme.accent else theme.border)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Row {
                    BasicText("> ", style = mono(18).copy(color = theme.accent))
                    Box {
                        if (query.isEmpty()) BasicText(placeholder, style = mono(18).copy(color = theme.dim))
                        inner()
                    }
                }
            },
        )
    }
}

/** The clear page: nothing but the wallpaper, with any pinned apps in a row along the bottom. */
@Composable
private fun ClearPage(
    favourites: List<AppEntry>,
    firstFocus: FocusRequester,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onFocus: (Boolean) -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    val enabled = LocalInteractive.current
    Box(
        Modifier
            .fillMaxSize()
            // Swipe down for notifications, up for the app list. Sideways swipes stay with the pager.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onVerticalDrag = { change, amount ->
                        travel += amount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            travel > threshold -> onSwipeDown()
                            travel < -threshold -> onSwipeUp()
                        }
                    },
                )
            }
            .padding(24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .onFocusChanged { onFocus(it.hasFocus) }
                .focusGroup()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            favourites.forEachIndexed { index, app ->
                key(app.key) {
                    AppCell(
                        app,
                        primary = false,
                        pinned = false,
                        solid = true,
                        onClick = { onLaunch(app) },
                        onMenu = { onMenu(app) },
                        modifier = (if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .widthIn(max = 240.dp),
                    )
                }
            }
        }
    }
}

/** One app. Tap or Enter opens it; a long press, Menu key or the controller's Start button opens its menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    app: AppEntry,
    primary: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
) {
    val theme = LocalTheme.current
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused -> theme.accent
        solid -> theme.bg.copy(alpha = 0.88f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .background(background)
            .border(1.dp, if (focused || primary) theme.accent else theme.border)
            .onKeyEvent { event ->
                val menu = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.ButtonStart || event.key == Key.Menu || (event.isCtrlPressed && event.key == Key.P))
                if (menu) onMenu()
                menu
            }
            .combinedClickable(enabled = LocalInteractive.current, onClick = onClick, onLongClick = onMenu)
            .padding(14.dp),
    ) {
        app.icon?.let {
            BasicText("$it  ", style = mono().copy(color = if (focused) theme.bg else theme.accent))
        }
        BasicText(
            app.label,
            style = mono().copy(color = if (focused) theme.bg else if (primary) theme.accent else theme.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Grid cells stretch so the pin sits at the right edge; favourites hug their label.
            modifier = Modifier.weight(1f, fill = !solid),
        )
        if (pinned) {
            BasicText("\uf08d", style = mono().copy(color = if (focused) theme.bg else theme.accent))
        }
    }
}

data class MenuItem(val icon: String, val label: String, val hint: String? = null, val action: () -> Unit)

/** A centred panel over the home screen, styled like an Omarchy menu. Focus stays inside it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Panel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalTheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.bg.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .heightIn(max = 640.dp)
                .background(theme.bg)
                .border(1.dp, theme.accent)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun MenuPanel(title: String, items: List<MenuItem>, onDismiss: () -> Unit) {
    val theme = LocalTheme.current
    val first = remember { FocusRequester() }
    // Puts keyboard and controller focus on the first entry. A no-op in touch mode.
    LaunchedEffect(items.size) { runCatching { first.requestFocus() } }
    Panel(onDismiss) {
        BasicText(title, style = mono().copy(color = theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            items.forEachIndexed { index, item ->
                MenuRow(item, if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) theme.accent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = item.action)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (focused) theme.bg else theme.fg
        BasicText(item.icon, style = mono().copy(color = if (focused) theme.bg else theme.accent), modifier = Modifier.width(32.dp))
        BasicText(item.label, style = mono().copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        item.hint?.let { BasicText(it, style = mono(13).copy(color = if (focused) theme.bg else theme.dim)) }
    }
}

@Composable
private fun RenamePanel(app: AppEntry, original: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalTheme.current
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(app.label, TextRange(0, app.label.length))) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Panel(onDismiss) {
        BasicText("rename $original", style = mono().copy(color = theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            textStyle = mono(18),
            cursorBrush = SolidColor(theme.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(field.text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .border(1.dp, theme.accent)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Row {
            Chip("save", { onSave(field.text) }, color = theme.accent)
            Chip("reset", { onSave("") }, color = theme.dim)
            Chip("cancel", onDismiss, color = theme.dim)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ThemePicker(
    themes: List<Theme>,
    currentId: String,
    store: ThemeStore,
    onPick: (Theme) -> Unit,
    onInstalled: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val currentIndex = themes.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    val currentRow = remember { FocusRequester() }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Puts keyboard and controller focus on the active theme. A no-op in touch mode.
    LaunchedEffect(Unit) { runCatching { currentRow.requestFocus() } }

    fun install() {
        if (busy || url.isBlank()) return
        busy = true
        status = "installing..."
        scope.launch {
            status = runCatching { store.installFromGithub(url) }.fold(
                onSuccess = { id ->
                    onInstalled(id)
                    url = ""
                    "installed ${id.removePrefix("user:")}"
                },
                onFailure = { "failed: ${it.message ?: it.javaClass.simpleName}" },
            )
            busy = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.bg.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxHeight(0.9f)
                .background(theme.bg)
                .border(1.dp, theme.accent)
                // Keep arrow-key and D-pad focus inside the picker.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                // Swallow taps so touching the panel doesn't dismiss it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("theme", style = mono().copy(color = theme.dim))

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(themes, key = { _, item -> item.id }) { index, item ->
                    ThemeRow(
                        item = item,
                        current = item.id == currentId,
                        onClick = { onPick(item) },
                        modifier = if (index == currentIndex) Modifier.focusRequester(currentRow) else Modifier,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    textStyle = mono(14),
                    cursorBrush = SolidColor(theme.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { install() }),
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, theme.border)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (url.isEmpty()) {
                                BasicText("github url of an omarchy theme", style = mono(14).copy(color = theme.dim))
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Chip("install", ::install, color = theme.accent)
            }
            status?.let { BasicText(it, style = mono(14).copy(color = theme.dim)) }
        }
    }
}

@Composable
private fun ThemeRow(item: Theme, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) theme.accent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(if (current) "> " else "  ", style = mono().copy(color = if (focused) theme.bg else theme.accent))
        // The theme's own background, foreground and accent, as a preview.
        Row(Modifier.border(1.dp, theme.border)) {
            for (swatch in listOf(item.bg, item.fg, item.accent)) {
                Box(Modifier.size(18.dp).background(swatch))
            }
        }
        Spacer(Modifier.width(12.dp))
        BasicText(
            item.title,
            style = mono().copy(color = if (focused) theme.bg else if (current) theme.accent else theme.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.user) BasicText("added", style = mono(13).copy(color = if (focused) theme.bg else theme.dim))
    }
}
