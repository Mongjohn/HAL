package com.evofactory.hal

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

private const val EditorPort = 8765
private const val EditorMaxBytes = 4 * 1024 * 1024

private class DashboardEditorServer(
    link: LinkAddress,
    private val context: Context,
    private val api: (String, JSONObject?) -> JSONObject?,
) : NanoHTTPD(link.address.hostAddress, EditorPort) {
    private val address = link.address.hostAddress
    private val localBytes = link.address.address
    private val prefixLength = link.prefixLength

    private fun isLocal(peer: InetAddress): Boolean {
        val bytes = peer.address
        return bytes.size == localBytes.size && bytes.indices.all { index ->
            val bits = (prefixLength - index * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            (bytes[index].toInt() and mask) == (localBytes[index].toInt() and mask)
        }
    }
    val url get() = "http://$address:$listeningPort"
    @Volatile private var closed = false

    fun close() {
        if (closed) return
        closed = true
        stop()
    }

    override fun serve(session: IHTTPSession): Response {
        fun reply(status: Response.Status, message: String, mime: String = "application/json") =
            newFixedLengthResponse(status, "$mime; charset=utf-8", message).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
                addHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'")
                closeConnection(true)
            }
        fun error(status: Response.Status, message: String) =
            reply(status, JSONObject().put("error", message).toString())

        val headers = session.headers
        val peer = runCatching { InetAddress.getByName(session.remoteIpAddress) }.getOrNull()
        if (closed || peer == null || !isLocal(peer) || headers["host"] != "$address:$listeningPort" ||
            headers["origin"]?.let { it != url } == true ||
            headers["sec-fetch-site"] == "cross-site"
        ) return error(Response.Status.FORBIDDEN, "Open the address shown on the TV.")

        val asset = when (session.uri) {
            "/" -> "index.html" to "text/html"
            "/editor.js" -> "editor.js" to "application/javascript"
            "/editor.css" -> "editor.css" to "text/css"
            else -> null
        }
        if (asset != null && session.method == Method.GET) {
            return reply(Response.Status.OK, context.assets.open("dashboard-editor/${asset.first}").bufferedReader().use { it.readText() }, asset.second)
        }
        val read = session.method == Method.GET && session.uri in listOf("/api/state", "/api/catalog")
        val write = session.method == Method.POST && session.uri in listOf("/api/apply", "/api/restore")
        if (!read && !write) return error(Response.Status.NOT_FOUND, "Page not found.")

        val body = if (write) {
            if (headers["origin"] != url || headers["content-type"]?.substringBefore(';') != "application/json" ||
                headers.containsKey("transfer-encoding") ||
                (headers["content-length"]?.toIntOrNull() ?: 0) !in 1..EditorMaxBytes
            ) return error(Response.Status.BAD_REQUEST, "Invalid editor request.")
            try {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                JSONObject(files.getValue("postData"))
            } catch (_: Exception) {
                return error(Response.Status.BAD_REQUEST, "Invalid dashboard data.")
            }
        } else null
        return try {
            val result = runBlocking {
                withTimeoutOrNull(5000) {
                    withContext(Dispatchers.Main) { if (closed) null else api(session.uri, body) }
                }
            }
            if (result == null) error(Response.Status.CONFLICT, "The layout changed. Reload the editor and try again.")
            else reply(Response.Status.OK, result.toString())
        } catch (_: Exception) {
            error(Response.Status.BAD_REQUEST, "The dashboard could not be saved. Check the card positions and sizes.")
        }
    }
}

private fun editorAddress(context: Context): LinkAddress? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return connectivity.allNetworks.asSequence().filter { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@filter false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
        .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
}

@Composable
internal fun DashboardEditor(
    haServer: HaServer,
    layouts: () -> DashboardLayouts,
    canRestore: () -> Boolean,
    legacyDisplay: HaDisplay,
    legacyEntities: List<String>,
    cameraVisible: Boolean,
    installedApps: List<InstalledApp>,
    save: (DashboardLayouts) -> Unit,
    restore: () -> Unit,
    showInfo: Boolean,
    onDismissInfo: () -> Unit,
    onStartFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val apps by rememberUpdatedState(installedApps)
    val readLayouts by rememberUpdatedState(layouts)
    val readCanRestore by rememberUpdatedState(canRestore)
    val currentSave by rememberUpdatedState(save)
    val currentRestore by rememberUpdatedState(restore)
    var editor by remember { mutableStateOf<DashboardEditorServer?>(null) }
    var revision by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var catalog by remember { mutableStateOf<List<HaEntity>>(emptyList()) }
    var templates by remember { mutableStateOf<List<Pair<HaDashboardInfo, HaDashboard>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }

    fun initial(home: Boolean): DashboardLayout {
        val key = if (home) legacyDisplay.homeDashboard else legacyDisplay.dashboard
        val settings = if (home) legacyDisplay.homeLayout else legacyDisplay.layout
        val selection = if (home) legacyEntities.filterNot(::isHaAction) else legacyEntities
        val board = templates.firstOrNull { it.first.key == key }?.second
        return seedDashboardLayout(
            board, settings.selectionCards(selection, catalog.associateBy { it.id }),
            if (home) 1 else 3, settings.twoPerRow, !home && legacyDisplay.bottomRow,
        )
    }

    fun state() = JSONObject().put("revision", revision).put("canRestore", readCanRestore())
        .put("camera", cameraVisible)
        .put("apps", JSONArray(apps.map { JSONObject().put("id", it.key).put("name", it.label) }))
        .put("assistant", (readLayouts().assistant ?: initial(false)).toJson())
        .put("home", (readLayouts().home ?: initial(true)).toJson())

    DisposableEffect(Unit) {
        val previousKeepScreenOn = view.keepScreenOn
        val address = editorAddress(context)
        var listener: DashboardEditorServer? = null
        if (address == null) onStartFailed("Connect this TV to Wi-Fi or Ethernet, then try again.")
        else {
            val server = DashboardEditorServer(address, context.applicationContext) { path, body ->
                when (path) {
                    "/api/state" -> if (loading) JSONObject().put("loading", true) else state()
                    "/api/catalog" -> JSONObject().put("loading", loading).put("notice", catalogError)
                        .put("entities", JSONArray(catalog.map { entity ->
                            JSONObject().put("id", entity.id).put("name", entity.name)
                                .put("area", entity.area).put("domain", entity.domain)
                        }))
                        .put("templates", JSONArray(templates.map { (info, board) ->
                            JSONObject().put("title", info.title)
                                .put("layout", seedDashboardLayout(board, emptyList(), 3, false).toJson())
                                .put("skipped", JSONArray(board.skipped))
                        }))
                    else -> {
                        if (body == null || body.optString("revision") != revision) null else {
                            if (path == "/api/restore") {
                                check(readCanRestore())
                                currentRestore()
                            } else {
                                val layout = dashboardLayoutFromJson(body.getJSONObject("layout"))
                                val previous = readLayouts()
                                val claimsFocus = layout.pages.any { page -> page.cards.any { it.focusOnHome } }
                                val next = when (body.getString("surface")) {
                                    "assistant" -> previous.copy(assistant = layout, home = previous.home?.let { if (claimsFocus) it.withoutHomeFocus() else it })
                                    "home" -> previous.copy(home = layout.copy(bottomRow = false, appsConfigured = true), assistant = previous.assistant?.let { if (claimsFocus) it.withoutHomeFocus() else it })
                                    else -> throw IllegalArgumentException("Unknown surface")
                                }
                                currentSave(next)
                            }
                            revision = UUID.randomUUID().toString()
                            JSONObject().put("revision", revision).put("applied", path == "/api/apply")
                        }
                    }
                }
            }
            try {
                server.start(5000, true)
                listener = server
                editor = server
                view.keepScreenOn = true
            } catch (_: Exception) {
                server.close()
                onStartFailed("Could not open dashboard editor port $EditorPort.")
            }
        }
        onDispose {
            listener?.let {
                it.close()
                view.keepScreenOn = previousKeepScreenOn
            }
        }
    }
    LaunchedEffect(editor, haServer) {
        if (editor == null) return@LaunchedEffect
        loading = true
        catalog = emptyList()
        templates = emptyList()
        catalogError = null
        try {
            if (haServer.isSet) withTimeoutOrNull(25_000) {
                haSession(haServer) {
                    catalog = getStates()
                    val boards = dashboards()
                    for (info in boards.sortedBy { it.key !in legacyDisplay.dashboards }) {
                        val dashboard = parseDashboard(dashboardConfig(info.urlPath)) ?: continue
                        templates = templates + (info to dashboard)
                    }
                }
                true
            } ?: run { catalogError = "Home Assistant did not finish responding." }
            else catalogError = "Connect Home Assistant on the TV to browse entities."
        } catch (e: HaException) {
            catalogError = e.message ?: "Home Assistant is unavailable."
        } finally {
            loading = false
        }
    }

    if (showInfo) editor?.let { DashboardEditorInfo(it.url, onDismissInfo) }
}

@Composable
private fun DashboardEditorInfo(url: String, onDismiss: () -> Unit) {
    TopDialog(
        title = "Dashboard editor",
        onDismiss = onDismiss,
        buttons = listOf(DialogButton("Close", primary = true, onClick = onDismiss)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Open this address on a device on the same network.")
            Text(url)
            Text("Use Settings → Enable Web Editor to turn it off.")
            val qr = remember(url) { QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 49, 49) }
            Canvas(Modifier.size(160.dp)) {
                drawRect(Color.White)
                val cell = (size.minDimension / qr.width).toInt().toFloat().coerceAtLeast(1f)
                val offset = (size.minDimension - qr.width * cell) / 2
                for (y in 0 until qr.height) for (x in 0 until qr.width) {
                    if (qr[x, y]) drawRect(Color.Black, Offset(offset + x * cell, offset + y * cell), Size(cell, cell))
                }
            }
        }
    }
}
