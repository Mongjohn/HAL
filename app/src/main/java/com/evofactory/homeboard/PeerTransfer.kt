package com.evofactory.homeboard

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "HomeBoard"
private const val PEER_APP = "homeboard-peer"
private const val PEER_PORT = 47811
private const val ANNOUNCE_INTERVAL_MS = 1_000L
private const val PEER_TIMEOUT_MS = 3_500L
private const val LISTEN_TIMEOUT_MS = 250
private const val CONNECT_TIMEOUT_MS = 3_000
private const val REPLY_TIMEOUT_MS = 10_000
private const val RECONNECT_DELAY_MS = 1_000L
private const val MAX_HANDSHAKE_BYTES = 1024
private const val MAX_SETTINGS_BYTES = 4 * 1024 * 1024
private const val GONE = "gone"
private const val ACK_OK = "ok"
private const val ACK_BAD = "bad"

/** The TV exporting lists the TVs importing, and the other way round */
enum class PeerRole(val wire: String, val other: String) {
    Export("export", "import"),
    Import("import", "export"),
}

/** Another TV on the network with its export or import screen open */
data class Peer(val id: String, val name: String, val address: InetAddress, val port: Int, val choseUs: Boolean)

private fun tvName(context: Context) =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

/** Every address that reaches the other TVs on the networks this TV is on */
private fun broadcastAddresses(): List<InetAddress> {
    val found = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network -> network.interfaceAddresses.mapNotNull { it.broadcast } }
    }.getOrDefault(emptyList())
    return (found + InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))).distinct()
}

/**
 * While an export or import screen is open: says so on the network every second and keeps [peers]
 * up to date, so TVs appear and leave as their screens open and close. Two TVs connect once each has
 * chosen the other. The TV importing only accepts a connection from the TV it chose.
 */
class PeerLink(context: Context, private val role: PeerRole) {
    private val appContext = context.applicationContext
    private val id = UUID.randomUUID().toString()
    val name: String = tvName(appContext)

    var peers by mutableStateOf<List<Peer>>(emptyList())
        private set
    var chosenId by mutableStateOf<String?>(null)
    /** The connection from the chosen TV, on the importing side */
    var incoming by mutableStateOf<PeerSession?>(null)
        private set
    /** What the chosen TV sent, on the importing side */
    var received by mutableStateOf<SettingsFile?>(null)
        private set

    /** The chosen TV, once it has chosen this one too */
    val connectedPeer: Peer? get() = peers.firstOrNull { it.id == chosenId && it.choseUs }

    /** Runs until cancelled */
    suspend fun run() = withContext(Dispatchers.IO) {
        // Without this, some Wi-Fi chips drop what's broadcast
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("homeboard-peers")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val udp = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = LISTEN_TIMEOUT_MS
            bind(InetSocketAddress(PEER_PORT))
        }
        val server = ServerSocket(0).apply { soTimeout = LISTEN_TIMEOUT_MS }
        try {
            // Starts over when the choice changes, so the other TV hears of it right away
            launch {
                snapshotFlow { chosenId }.collectLatest {
                    while (true) {
                        announce(udp, role.wire, server.localPort)
                        delay(ANNOUNCE_INTERVAL_MS)
                    }
                }
            }
            if (role == PeerRole.Import) {
                launch { serve(server) }
                // Reading from the other TV blocks: closing is what ends it
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        incoming?.close()
                    }
                }
            }
            listen(udp)
        } finally {
            announce(udp, GONE, 0)
            udp.close()
            server.close()
            lock?.release()
        }
    }

    private fun announce(udp: DatagramSocket, mode: String, port: Int) {
        val message = JSONObject()
            .put("app", PEER_APP)
            .put("id", id)
            .put("name", name)
            .put("mode", mode)
            .put("port", port)
            .put("chosen", chosenId.orEmpty())
            .toString()
            .toByteArray()
        broadcastAddresses().forEach { address ->
            runCatching { udp.send(DatagramPacket(message, message.size, address, PEER_PORT)) }
        }
    }

    private suspend fun listen(udp: DatagramSocket) {
        val heard = mutableMapOf<String, Pair<Peer, Long>>()
        val buffer = ByteArray(MAX_HANDSHAKE_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                val json = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull()
                val peerId = json?.optString("id").orEmpty()
                if (json?.optString("app") == PEER_APP && peerId.isNotEmpty() && peerId != id) {
                    if (json.optString("mode") == role.other) {
                        heard[peerId] = Peer(
                            id = peerId,
                            name = json.optString("name").take(MAX_NAME_LENGTH).ifEmpty { "TV" },
                            address = packet.address,
                            port = json.optInt("port"),
                            choseUs = json.optString("chosen") == id,
                        ) to System.currentTimeMillis()
                    } else {
                        heard.remove(peerId)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Nothing heard: still time to drop TVs that went silent
            }
            val now = System.currentTimeMillis()
            heard.values.removeAll { (_, seen) -> now - seen > PEER_TIMEOUT_MS }
            peers = heard.values.map { it.first }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        }
    }

    private suspend fun serve(server: ServerSocket) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            val peer = peers.firstOrNull { it.id == chosenId && it.address == socket.inetAddress }
            val session = peer?.let { runCatching { PeerSession.accept(socket, it) }.getOrNull() }
            if (session == null) {
                socket.close()
                continue
            }
            incoming = session
            try {
                currentCoroutineContext().ensureActive()
                val settings = settingsFromJson(String(session.receive()))
                session.acknowledge(settings != null)
                if (settings != null) received = settings
            } catch (e: IOException) {
                AppLog.i(TAG, "Connection to the other TV closed: ${e.javaClass.simpleName}")
            } finally {
                session.close()
                incoming = null
            }
        }
    }

    /** From the TV exporting to the TV importing */
    fun connect(peer: Peer): PeerSession = PeerSession.connect(id, peer)

    companion object {
        private const val MAX_NAME_LENGTH = 60
    }
}

/**
 * One encrypted connection between two TVs. A key is agreed for this connection only, with
 * nothing sent readable. [code] comes from the key, and both TVs only show the same one if nobody
 * sits in between.
 */
class PeerSession private constructor(
    private val socket: Socket,
    private val key: SecretKey,
    val code: String,
    val peer: Peer,
) : Closeable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    /** True once the other TV has read the settings */
    fun send(settings: String): Boolean {
        writeFrame(output, seal(settings.toByteArray(), "settings"))
        return String(open(readFrame(input, MAX_HANDSHAKE_BYTES), "ack")) == ACK_OK
    }

    /** Waits for the settings, however long the other TV takes to choose them */
    fun receive(): ByteArray {
        socket.soTimeout = 0
        val settings = open(readFrame(input, MAX_SETTINGS_BYTES), "settings")
        socket.soTimeout = REPLY_TIMEOUT_MS
        return settings
    }

    fun acknowledge(ok: Boolean) = writeFrame(output, seal((if (ok) ACK_OK else ACK_BAD).toByteArray(), "ack"))

    override fun close() {
        runCatching { socket.close() }
    }

    private fun seal(plain: ByteArray, label: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, label)
        return nonce + cipher.doFinal(plain)
    }

    private fun open(sealed: ByteArray, label: String): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, sealed.copyOf(NONCE_BYTES), label).doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    } catch (e: GeneralSecurityException) {
        throw IOException("Can't decrypt", e)
    }

    private fun cipher(mode: Int, nonce: ByteArray, label: String) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(label.toByteArray())
    }

    companion object {
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        fun connect(ownId: String, peer: Peer): PeerSession {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(peer.address, peer.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = REPLY_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val keys = newKeys()
                writeFrame(output, ownId.toByteArray())
                writeFrame(output, keys.public.encoded)
                val theirKey = readFrame(input, MAX_HANDSHAKE_BYTES)
                return derive(socket, keys.private, theirKey, keys.public.encoded, theirKey, peer)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        /** Null if it isn't the TV chosen */
        fun accept(socket: Socket, peer: Peer): PeerSession? {
            socket.soTimeout = REPLY_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val theirId = String(readFrame(input, MAX_HANDSHAKE_BYTES))
            val theirKey = readFrame(input, MAX_HANDSHAKE_BYTES)
            if (theirId != peer.id) return null
            val keys = newKeys()
            writeFrame(output, keys.public.encoded)
            return derive(socket, keys.private, theirKey, theirKey, keys.public.encoded, peer)
        }

        private fun newKeys() = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun derive(
            socket: Socket,
            ownPrivate: PrivateKey,
            theirPublic: ByteArray,
            exporterPublic: ByteArray,
            importerPublic: ByteArray,
            peer: Peer,
        ): PeerSession {
            val secret = KeyAgreement.getInstance("ECDH").run {
                init(ownPrivate)
                doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirPublic)), true)
                generateSecret()
            }
            fun digest(label: String) = MessageDigest.getInstance("SHA-256").run {
                update("$PEER_APP:$label".toByteArray())
                update(secret)
                update(exporterPublic)
                digest(importerPublic)
            }
            val code = (ByteBuffer.wrap(digest("code")).int and Int.MAX_VALUE) % 10_000
            return PeerSession(socket, SecretKeySpec(digest("key"), "AES"), "%04d".format(Locale.ROOT, code), peer)
        }
    }
}

private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
}

private fun readFrame(input: DataInputStream, maxBytes: Int): ByteArray {
    val size = input.readInt()
    if (size !in 0..maxBytes) throw IOException("Bad frame size")
    return ByteArray(size).also { input.readFully(it) }
}

@Composable
private fun rememberPeerLink(role: PeerRole): PeerLink {
    val context = LocalContext.current
    val link = remember { PeerLink(context, role) }
    LaunchedEffect(link) {
        try {
            link.run()
        } catch (e: IOException) {
            AppLog.w(TAG, "Can't look for other TVs: ${e.javaClass.simpleName}")
            Toast.makeText(context, "Can't look for other TVs on the network", Toast.LENGTH_LONG).show()
        }
    }
    return link
}

/** The other TVs in the opposite mode, changing as they come and go. Pressing one chooses it. */
@Composable
private fun PeerList(
    title: String,
    link: PeerLink,
    hint: String,
    fileLabel: String,
    onFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val peers = link.peers
    val keys = peers.map { it.id }
    val rows = remember { ListFocus() }
    val fileButton = remember { FocusRequester() }
    val close = remember { FocusRequester() }

    TopDialog(
        title = title,
        info = link.name,
        onDismiss = onDismiss,
        actions = {
            OutlinedButton(
                onClick = onFile,
                modifier = Modifier.focusRequester(fileButton).focusProperties {
                    left = rows.current(keys)
                    down = rows.current(keys)
                },
            ) { Text(fileLabel) }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(close).focusProperties {
                    right = rows.current(keys)
                    down = rows.current(keys)
                },
            ) { Text("Close") }
        },
    ) {
        if (peers.isEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            items(peers, key = { it.id }) { peer ->
                val chosen = peer.id == link.chosenId
                ListItem(
                    selected = false,
                    onClick = { link.chosenId = if (chosen) null else peer.id },
                    headlineContent = { Text(peer.name) },
                    supportingContent = {
                        Text(
                            when {
                                chosen -> "Waiting for this TV to select ${link.name}"
                                peer.choseUs -> "Wants to connect. Select it to accept."
                                else -> peer.address.hostAddress.orEmpty()
                            },
                        )
                    },
                    leadingContent = { MdiIcon(if (chosen) "mdi:check" else "mdi:television", size = 24.dp) },
                    modifier = rows.row(peer.id).jumpToActions(
                        left = close,
                        right = fileButton,
                        down = if (peer.id == keys.last()) fileButton else null,
                    ),
                )
            }
        }
        // To the list as soon as there's something in it, and off it when the last one leaves
        LaunchedEffect(peers.isEmpty()) {
            runCatching { if (peers.isEmpty()) close.requestFocus() else rows.current(keys).requestFocus() }
        }
    }
}

@Composable
private fun CodeLine(session: PeerSession) {
    Text(
        "Both TVs should show the code ${session.code}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(12.dp),
    )
}

/** Choose a TV in import mode, then what to push to it. */
@Composable
fun ExportToTv(file: SettingsFile, onSaveToFile: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link = rememberPeerLink(PeerRole.Export)
    val peer = link.connectedPeer
    var session by remember { mutableStateOf<PeerSession?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(peer?.id) {
        if (peer == null) return@LaunchedEffect
        var connected: PeerSession? = null
        try {
            while (connected == null) {
                connected = withContext(Dispatchers.IO) { runCatching { link.connect(peer) }.onFailure { AppLog.w(TAG, "Can't connect to the other TV", it) }.getOrNull() }
                if (connected == null) delay(RECONNECT_DELAY_MS)
            }
            session = connected
            awaitCancellation()
        } finally {
            connected?.close()
            session = null
        }
    }

    val current = session
    if (current == null) {
        PeerList(
            title = "Export settings",
            link = link,
            hint = "Looking for other TVs. Open HomeBoard settings > Import settings on the other TV and it appears here.",
            fileLabel = "Save to a file",
            onFile = onSaveToFile,
            onDismiss = onDismiss,
        )
    } else {
        ChecklistDialog(
            title = "Send to ${current.peer.name}",
            confirmLabel = "Send",
            file = file,
            onConfirm = { chosen ->
                if (!sending) {
                    sending = true
                    scope.launch {
                        val sent = withContext(Dispatchers.IO) { runCatching { current.send(chosen.toJson()) }.onFailure { AppLog.w(TAG, "Can't send to the other TV", it) }.getOrDefault(false) }
                        sending = false
                        if (sent) {
                            Toast.makeText(context, "Settings sent to ${current.peer.name}", Toast.LENGTH_LONG).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, "Couldn't send the settings to ${current.peer.name}", Toast.LENGTH_LONG).show()
                            link.chosenId = null
                        }
                    }
                }
            },
            onDismiss = { link.chosenId = null },
            above = { CodeLine(current) },
        )
    }
}

/** Choose a TV in export mode, then wait for what it pushes. */
@Composable
fun ImportFromTv(onImport: (SettingsFile) -> Unit, onOpenFile: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = rememberPeerLink(PeerRole.Import)
    val peer = link.connectedPeer
    val session = link.incoming

    // The other TV left or changed its mind
    LaunchedEffect(peer?.id) {
        if (peer == null) withContext(Dispatchers.IO) { link.incoming?.close() }
    }
    LaunchedEffect(link.received) {
        link.received?.let {
            onImport(it)
            Toast.makeText(context, "Settings imported", Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }

    if (session == null) {
        PeerList(
            title = "Import settings",
            link = link,
            hint = "Looking for other TVs. Open HomeBoard settings > Export settings on the other TV and it appears here.",
            fileLabel = "Open a file",
            onFile = onOpenFile,
            onDismiss = onDismiss,
        )
    } else {
        val cancel = remember { FocusRequester() }
        TopDialog(
            title = "Import from ${session.peer.name}",
            onDismiss = { link.chosenId = null },
            actions = {
                OutlinedButton(onClick = { link.chosenId = null }, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
            },
        ) {
            CodeLine(session)
            Text(
                "Waiting for ${session.peer.name} to choose what to send.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
            LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
        }
        // Cancelling here closes the connection, which the sending TV sees through its list
        LaunchedEffect(link.chosenId) {
            if (link.chosenId != session.peer.id) withContext(Dispatchers.IO) { session.close() }
        }
    }
}
