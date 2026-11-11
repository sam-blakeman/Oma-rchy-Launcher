package dev.samblakeman.omarchylauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Live system readings for the bar's status modules. Icons are Font Awesome
// glyphs from the bundled JetBrains Mono Nerd Font.

data class Battery(val percent: Int, val charging: Boolean) {
    val icon: String
        get() = when {
            charging -> ""
            percent >= 88 -> ""
            percent >= 63 -> ""
            percent >= 38 -> ""
            percent >= 13 -> ""
            else -> ""
        }
}

enum class Link(val icon: String) {
    Wifi(""),
    Wired(""),
    Mobile(""),
    Offline(""),
}

/** Media volume as a percentage. */
data class Volume(val percent: Int) {
    val icon: String
        get() = when {
            percent == 0 -> ""
            percent < 50 -> ""
            else -> ""
        }
}

/** Tracks a system broadcast, re-reading the value each time it fires. */
@Composable
private fun <T> broadcastState(action: String, read: (Context, Intent?) -> T): State<T> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(read(context, null)) }
    DisposableEffect(action) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                state.value = read(context, intent)
            }
        }
        // For sticky broadcasts this also returns the latest value straight away.
        val sticky = context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        if (sticky != null) state.value = read(context, sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

@Composable
fun batteryState(): State<Battery> = broadcastState(Intent.ACTION_BATTERY_CHANGED) { _, intent ->
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    Battery(
        percent = if (level < 0 || scale <= 0) 0 else level * 100 / scale,
        charging = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
    )
}

@Composable
fun volumeState(): State<Volume> = broadcastState("android.media.VOLUME_CHANGED_ACTION") { context, _ ->
    val audio = context.getSystemService(AudioManager::class.java)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    Volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
}

@Composable
fun linkState(): State<Link> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Link.Offline) }
    DisposableEffect(Unit) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                state.value = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Link.Wifi
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link.Wired
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Link.Mobile
                    else -> Link.Wifi
                }
            }

            override fun onLost(network: Network) {
                state.value = Link.Offline
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    return state
}
