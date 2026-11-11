package app.omalauncher

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

data class AppEntry(val key: String, val label: String, val launch: () -> Unit)

sealed interface Command {
    data class Type(val char: Char) : Command
    data object Backspace : Command
    data object Search : Command
    data object Themes : Command
    data object NextBackground : Command
    data object PreviousPage : Command
    data object NextPage : Command
    data object Favourites : Command
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
    val searchFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val favouriteFocus = remember { FocusRequester() }
    val favouriteStore = remember { Favourites(context.applicationContext) }
    var favourites by remember { mutableStateOf(favouriteStore.load()) }
    var favouritesFocused by remember { mutableStateOf(false) }
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

    // Android's own wallpaper follows the launcher's, so the lock screen and recents match.
    fun syncSystemWallpaper(target: Theme, index: Int) {
        scope.launch { store.setSystemWallpaper(target, index) }
    }

    fun nextBackground() {
        if (theme.backgrounds.isEmpty()) return
        backgroundIndex = (backgroundIndex + 1).mod(theme.backgrounds.size)
        store.setBackgroundIndex(theme, backgroundIndex)
        syncSystemWallpaper(theme, backgroundIndex)
    }

    fun togglePin(app: AppEntry) {
        favourites = if (app.key in favourites) favourites - app.key else favourites + app.key
        favouriteStore.save(favourites)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val pinned = remember(apps, favourites) { favourites.mapNotNull { key -> apps.firstOrNull { it.key == key } } }

    LaunchedEffect(Unit) { themes = store.loadAll() }
    LaunchedEffect(homePresses) {
        dismissKeyboard()
        setQuery("")
        pickerOpen = false
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
        activity?.clearPage = !onApps && !pickerOpen
        activity?.arrowsFlipPages = !onApps && !pickerOpen && !favouritesFocused
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
        if (pickerOpen) pickerOpen = false
        else {
            dismissKeyboard()
            // Back first clears a search, then returns to the clear page.
            if (field.text.isNotEmpty()) setQuery("") else if (onApps) flipPage(-1)
        }
    }

    // Typing anywhere searches. On a controller: X search, Y themes, L1/R1 pages, R3 wallpaper.
    val latestCommand by androidx.compose.runtime.rememberUpdatedState<(Command) -> Unit> { command ->
        when (command) {
            Command.Themes -> pickerOpen = !pickerOpen
            Command.NextBackground -> nextBackground()
            Command.Favourites -> runCatching { favouriteFocus.requestFocus() }
            Command.PreviousPage -> if (!pickerOpen) flipPage(-1)
            Command.NextPage -> if (!pickerOpen) flipPage(1)
            else -> if (!pickerOpen) {
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

    val shown = remember(apps, query) {
        val needle = query.trim()
        if (needle.isEmpty()) apps
        else apps.mapNotNull { app ->
            // Names starting with the query first, then word starts, then anywhere.
            val rank = when {
                app.label.startsWith(needle, ignoreCase = true) -> 0
                app.label.split(' ', '-').any { it.startsWith(needle, ignoreCase = true) } -> 1
                app.label.contains(needle, ignoreCase = true) -> 2
                else -> return@mapNotNull null
            }
            rank to app
        }.sortedBy { it.first }.map { it.second }
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

            CompositionLocalProvider(LocalInteractive provides !pickerOpen) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                CompositionLocalProvider(LocalBarFocusable provides onApps) {
                    Bar(
                        backgroundLabel = if (theme.backgrounds.isEmpty()) null
                        else "bg ${backgroundIndex.mod(theme.backgrounds.size) + 1}/${theme.backgrounds.size}",
                        onTheme = { pickerOpen = true },
                        onNextBackground = ::nextBackground,
                    )
                }
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !pickerOpen,
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
                        LocalInteractive provides (!pickerOpen && page == pager.currentPage),
                    ) {
                        val launch: (AppEntry) -> Unit = { app ->
                            app.launch()
                            dismissKeyboard()
                            setQuery("")
                        }
                        if (page % 2 != 0) {
                            AppsPage(
                                apps = shown,
                                total = apps.size,
                                pinnedKeys = favourites,
                                field = field,
                                searchFocus = searchFocus,
                                gridFocus = gridFocus,
                                onQuery = { field = it },
                                onLaunch = launch,
                                onPin = ::togglePin,
                            )
                        } else {
                            ClearPage(
                                favourites = pinned,
                                firstFocus = favouriteFocus,
                                onLaunch = launch,
                                onUnpin = ::togglePin,
                                onFocus = { favouritesFocused = it },
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
                        syncSystemWallpaper(it, store.backgroundIndex(it))
                    },
                    onInstalled = { id ->
                        themes = store.loadAll()
                        themeId = id
                        store.currentId = id
                        themes.firstOrNull { it.id == id }?.let { syncSystemWallpaper(it, store.backgroundIndex(it)) }
                    },
                    onDismiss = { pickerOpen = false },
                )
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
    onPin: (AppEntry) -> Unit,
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
                    onPin = { onPin(app) },
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
private fun Bar(backgroundLabel: String?, onTheme: () -> Unit, onNextBackground: () -> Unit) {
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
    onUnpin: (AppEntry) -> Unit,
    onFocus: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
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
                        onPin = { onUnpin(app) },
                        modifier = (if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .widthIn(max = 240.dp),
                    )
                }
            }
        }
    }
}

/**
 * One app. Tap or Enter opens it; a long press, Ctrl+P or the controller's Start button
 * pins it to the clear page, or unpins it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    app: AppEntry,
    primary: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
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
                val pin = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.ButtonStart || (event.isCtrlPressed && event.key == Key.P))
                if (pin) onPin()
                pin
            }
            .combinedClickable(enabled = LocalInteractive.current, onClick = onClick, onLongClick = onPin)
            .padding(14.dp),
    ) {
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
            .background(if (focused) theme.border else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(if (current) "> " else "  ", style = mono().copy(color = theme.accent))
        // The theme's own background, foreground and accent, as a preview.
        Row(Modifier.border(1.dp, theme.border)) {
            for (swatch in listOf(item.bg, item.fg, item.accent)) {
                Box(Modifier.size(18.dp).background(swatch))
            }
        }
        Spacer(Modifier.width(12.dp))
        BasicText(
            item.title,
            style = mono().copy(color = if (current) theme.accent else theme.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.user) BasicText("added", style = mono(13).copy(color = theme.dim))
    }
}
