package com.evofactory.homeboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "Network"
private const val DETAILS_REFRESH_MS = 1_000L
private const val NO_SIGNAL_RSSI = -127
private const val LOOPBACK = "127.0.0.1"
private const val ADB_TCP_PORT = 5555
private const val ADB_PROBE_TIMEOUT_MS = 500
private const val ADB_PROBE_INTERVAL_MS = 5_000L
private const val WIRELESS_DEBUGGING_SERVICE = "_adb-tls-connect._tcp"

enum class NetworkKind(val label: String, val icon: String, val brokenIcon: String) {
    Ethernet("Ethernet", "ethernet", "ethernet-off"),
    Wifi("Wi-Fi", "wifi", "wifi-off"),
    Other("Network", "lan", "lan-disconnect"),
}

/** The TV's connection. [problem] says why it can't be used. Without a network, [kind] is the last one used. */
data class NetworkStatus(val kind: NetworkKind, val problem: String?, val details: List<Pair<String, String>>)

/** This network's IPv4 addresses, including a self-assigned one */
fun ConnectivityManager.ipv4Addresses(network: Network): List<Inet4Address> =
    getLinkProperties(network)?.linkAddresses.orEmpty()
        .map { it.address }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }

fun Context.readNetworkStatus(): NetworkStatus {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val prefs = LauncherPrefs(this)
    val network = connectivity.activeNetwork
    val capabilities = network?.let(connectivity::getNetworkCapabilities)
    val link = network?.let(connectivity::getLinkProperties)
    if (network == null || capabilities == null || link == null) return noNetworkStatus(prefs)

    val kind = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
        else -> NetworkKind.Other
    }
    if (prefs.lastNetworkKind != kind.name) prefs.lastNetworkKind = kind.name

    val ipv4 = link.linkAddresses.filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
    val usable = ipv4.firstOrNull { !it.address.isLinkLocalAddress }
    val ipv6 = link.linkAddresses.map { it.address }.filterIsInstance<Inet6Address>()
        .firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
    val problem = when {
        usable != null || ipv6 != null -> null
        ipv4.isNotEmpty() -> "Self-assigned address: no answer from DHCP"
        else -> "Connected, but no IP address"
    }
    val shown = usable ?: ipv4.firstOrNull()
    val details = buildList {
        add("IP address" to (shown?.address?.hostAddress ?: "None"))
        shown?.let { add("Subnet mask" to subnetMask(it.prefixLength)) }
        gateway(link)?.let { add("Gateway" to it) }
        link.dnsServers.mapNotNull { it.hostAddress }.takeIf { it.isNotEmpty() }?.let { add("DNS servers" to it.joinToString(", ")) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            link.dhcpServerAddress?.hostAddress?.let { add("DHCP server" to it) }
        }
        ipv6?.hostAddress?.let { add("IPv6 address" to it) }
        val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        add("Internet" to if (internet) "Yes" else "Not confirmed")
        if (kind == NetworkKind.Wifi) addAll(wifiDetails())
        link.interfaceName?.let { add("Interface" to it) }
    }
    return NetworkStatus(kind, problem, details)
}

/** Android doesn't say whether a cable is plugged in, and Ethernet without an address looks the same */
private fun Context.noNetworkStatus(prefs: LauncherPrefs): NetworkStatus {
    val wifiOn = getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    val kind = NetworkKind.entries.firstOrNull { it.name == prefs.lastNetworkKind } ?: NetworkKind.Other
    val problem = when {
        kind == NetworkKind.Ethernet -> "No cable connected, or no answer from the network"
        kind == NetworkKind.Wifi && !wifiOn -> "Wi-Fi is off"
        kind == NetworkKind.Wifi -> "Not connected to a Wi-Fi network"
        else -> "Not connected to a network"
    }
    return NetworkStatus(kind, problem, listOf("Wi-Fi" to if (wifiOn) "On" else "Off"))
}

/** Android hides the network's name from apps without the location permission */
@Suppress("DEPRECATION")
private fun Context.wifiDetails(): List<Pair<String, String>> {
    val info = getSystemService(WifiManager::class.java)?.connectionInfo ?: return emptyList()
    return buildList {
        if (info.rssi > NO_SIGNAL_RSSI) add("Signal" to "${info.rssi} dBm")
        if (info.linkSpeed > 0) add("Link speed" to "${info.linkSpeed} Mbps")
        if (info.frequency > 0) add("Frequency" to "${info.frequency} MHz")
    }
}

private fun gateway(link: LinkProperties): String? =
    link.routes.filter { it.isDefaultRoute && it.hasGateway() }
        .let { routes -> routes.firstOrNull { it.gateway is Inet4Address } ?: routes.firstOrNull() }
        ?.gateway?.hostAddress

private fun subnetMask(prefixLength: Int): String {
    val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
    return listOf(24, 16, 8, 0).joinToString(".") { (mask ushr it and 255).toString() }
}

/** Top bar button: Ethernet or Wi-Fi, crossed out and in red when the connection can't be used. Follows changes live. */
@Composable
fun NetworkButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.readNetworkStatus()) }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() {
                val new = context.readNetworkStatus()
                if (new.kind != status.kind || new.problem != status.problem) {
                    AppLog.i(TAG, "${new.kind.label}: ${new.problem ?: "connected"}")
                }
                status = new
            }

            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = refresh()
        }
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    val broken = status.problem != null
    val name = if (broken) "${status.kind.label}: no connection" else status.kind.label
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(SettingsButtonSize)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = name },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = colors.surfaceVariant,
                contentColor = if (broken) ErrorColor else colors.onSurfaceVariant,
                focusedContainerColor = colors.onSurface,
                focusedContentColor = colors.inverseOnSurface,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MdiIcon(if (broken) status.kind.brokenIcon else status.kind.icon, color = LocalContentColor.current)
            }
        }
        if (focused) TopBarButtonLabel(name)
    }
}

/** Whether something answers on the fixed port that `adb tcpip` opens. Apps can't read adb's own settings for it. */
private suspend fun isAdbPortOpen(): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, ADB_TCP_PORT), ADB_PROBE_TIMEOUT_MS) }
        true
    } catch (e: IOException) {
        false
    }
}

/**
 * The port wireless debugging is on right now, which changes each time. Android 11+ announces it on
 * the local network, along with every other TV's: only this TV's own addresses count.
 */
@Composable
private fun wirelessDebuggingPort(): Int? {
    val context = LocalContext.current
    var port by remember { mutableStateOf<Int?>(null) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    DisposableEffect(context) {
        val nsd = context.getSystemService(NsdManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val waiting = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var portName: String? = null

        // Only one can resolve at a time
        fun resolveNext() {
            if (resolving) return
            val service = waiting.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val ours = connectivity.activeNetwork?.let { network ->
                            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().any { it.address == info.host }
                        } == true
                        if (ours) {
                            portName = info.serviceName
                            port = info.port
                        }
                        resolveNext()
                    }
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    AppLog.w(TAG, "Couldn't resolve ${info.serviceName}: error $errorCode")
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                main.post {
                    waiting += service
                    resolveNext()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                main.post {
                    if (service.serviceName == portName) {
                        portName = null
                        port = null
                    }
                }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(TAG, "Couldn't look for wireless debugging: error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(TAG, "Couldn't stop looking for wireless debugging: error $errorCode")
            }
        }
        nsd.discoverServices(WIRELESS_DEBUGGING_SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery)
        onDispose {
            runCatching { nsd.stopServiceDiscovery(discovery) }
                .onFailure { AppLog.w(TAG, "Couldn't stop looking for wireless debugging", it) }
        }
    }
    return port
}

/** The connection in detail, kept current while open */
@Composable
fun NetworkStatusDialog(frigate: FrigateFeed?, onOpenPing: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val status by produceState(context.readNetworkStatus(), context) {
        while (true) {
            value = context.readNetworkStatus()
            delay(DETAILS_REFRESH_MS)
        }
    }
    val adbPortOpen by produceState<Boolean?>(null) {
        while (true) {
            value = isAdbPortOpen()
            delay(ADB_PROBE_INTERVAL_MS)
        }
    }
    val wirelessPort = wirelessDebuggingPort()
    val ping = remember { FocusRequester() }
    TopDialog(title = "Network status", onDismiss = onDismiss) {
        NetworkRow("Connection", status.kind.label)
        NetworkRow("Status", status.problem ?: "Connected", if (status.problem != null) ErrorColor else Color.Unspecified)
        status.details.forEach { (label, value) -> NetworkRow(label, value) }
        NetworkRow(
            "adb port",
            when (adbPortOpen) {
                null -> "Checking…"
                true -> "$ADB_TCP_PORT"
                false -> "$ADB_TCP_PORT not open"
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NetworkRow("Wireless debugging", wirelessPort?.let { "Port $it" } ?: "Not found")
        }
        frigate?.let { FrigateRows(it) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onOpenPing, modifier = Modifier.focusRequester(ping)) { Text("Ping utility") }
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }
        LaunchedEffect(Unit) { ping.requestFocus() }
    }
}

@Composable
private fun FrigateRows(feed: FrigateFeed) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val lastQuery = feed.lastQueryAt
    val failed = feed.failedQueries
    NetworkRow(
        "Last Frigate query",
        when {
            lastQuery != null -> "${elapsedText(now - lastQuery)} ago, $failed failed since"
            failed > 0 -> "None worked yet, $failed failed"
            else -> "None yet"
        },
        if (failed > 0) ErrorColor else Color.Unspecified,
    )
    val connectedAt = feed.socketConnectedAt
    val problem = feed.socketProblem
    NetworkRow(
        "Frigate WebSocket",
        when {
            connectedAt != null -> "Connected for ${elapsedText(now - connectedAt)}"
            problem != null -> "Not connected: $problem"
            lastQuery != null -> "Connecting…"
            else -> "Waiting for a query to work"
        },
        if (connectedAt == null && problem != null) ErrorColor else Color.Unspecified,
    )
}

private fun elapsedText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        else -> "${seconds / 86400} d ${seconds % 86400 / 3600} h"
    }
}

@Composable
private fun NetworkRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(NetworkLabelWidth),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val NetworkLabelWidth = 180.dp
