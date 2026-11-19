package com.meshx.app.model

/** Node id 0 means "everyone" (broadcast). Real nodes use 1..MAX_NODE_ID. */
const val BROADCAST_ID = 0
const val MAX_NODE_ID = 65534

/** The four ways the app can reach the mesh. */
enum class Transport(val label: String) {
    Ble("Bluetooth"),
    Wifi("Wi-Fi"),
    Phone("Phone to phone"),
    Demo("Demo mesh"),
}

/** What to connect to. Saved so the app can reconnect next time. */
sealed class LinkSpec {
    abstract val transport: Transport

    data class Ble(val address: String, val name: String) : LinkSpec() {
        override val transport get() = Transport.Ble
    }

    data class Wifi(val host: String, val port: Int) : LinkSpec() {
        override val transport get() = Transport.Wifi
    }

    /** No hardware: this phone becomes a node and talks to other phones nearby. */
    data class Phone(val nodeId: Int, val star: Boolean) : LinkSpec() {
        override val transport get() = Transport.Phone
    }

    object Demo : LinkSpec() {
        override val transport get() = Transport.Demo
    }
}

enum class LinkState { Idle, Connecting, Connected, Lost }

enum class RoutingMode(val key: String, val label: String, val blurb: String) {
    Adaptive("adaptive", "Adaptive", "Uses the link score to avoid weak links"),
    Shortest("shortest", "Shortest hop", "Fewest hops, ignores link quality"),
    Flood("flood", "Flooding", "Every node repeats every message");

    companion object {
        fun fromKey(key: String): RoutingMode? = entries.firstOrNull { it.key == key }
    }
}

enum class MsgStatus { Sending, Sent, Delivered, Failed }

data class ChatMessage(
    val key: Long,              // unique inside the app
    val msgId: Int,             // id used on the mesh
    val peer: Int,              // conversation: a node id, or BROADCAST_ID
    val from: Int,              // node id of the sender
    val outgoing: Boolean,
    val text: String,
    val time: Long,             // wall clock, ms
    val status: MsgStatus = MsgStatus.Sending,
    val hops: Int = 0,
    val rttMs: Int = 0,
    val rssi: Int = 0,
    val snr: Float = 0f,
    val failReason: String = "",
)

enum class ContactSource(val label: String) { Manual("Added by hand"), Heard("From heard list"), Qr("Scanned QR") }

data class Contact(
    val id: Int,
    val name: String,
    val source: ContactSource = ContactSource.Manual,
    val addedAt: Long = 0,
)

/** Everything the connected node knows about one node of the mesh. */
data class NodeInfo(
    val id: Int,
    val name: String = "",
    val online: Boolean = false,
    val lastSeenAt: Long = 0,       // wall clock of the last time it was heard
    val rssi: Int = 0,              // dBm of the direct link, 0 = not a direct neighbour
    val snr: Float = 0f,
    val battery: Int = -1,          // percent, -1 = unknown
    val loss: Int = 0,              // percent of beacons missed on the direct link
    val score: Int = 0,             // 0..100 quality of the route to this node
    val hops: Int = 0,              // 0 = no route
    val nextHop: Int = 0,
    val uptimeSec: Long = 0,
    val tx: Int = 0,
    val rx: Int = 0,
    val fwd: Int = 0,
    val drop: Int = 0,
    val phone: Boolean = false,     // a phone is connected to that node
    val fw: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val posAt: Long = 0,
) {
    val direct: Boolean get() = online && hops == 1
    val hasPosition: Boolean get() = lat != null && lon != null
}

data class MeshLink(val a: Int, val b: Int, val score: Int)

data class PacketLog(
    val seq: Long,
    val time: Long,
    val kind: String,       // MSG, ACK, BEACON, PING, PONG
    val src: Int,
    val dst: Int,
    val via: Int,           // node that transmitted this copy
    val hops: Int,
    val rssi: Int,          // 0 for our own transmissions
    val size: Int,
)

data class BleDevice(val address: String, val name: String, val rssi: Int)

data class PingResult(val id: Int, val dst: Int, val pending: Boolean, val rttMs: Int = -1, val hops: Int = 0)

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

data class Profile(
    val name: String = "",
    val onboarded: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    val shareLocation: Boolean = false,
    val phoneNodeId: Int = 0,       // node id used in phone-to-phone mode, 0 = not chosen yet
    val wifiHost: String = "192.168.4.1",
    val wifiPort: Int = 4403,
)

/** Everything that is kept between app starts. */
data class SavedData(
    val profile: Profile = Profile(),
    val contacts: List<Contact> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val nodes: List<NodeInfo> = emptyList(),
    val nextMsgId: Int = 1,
    val lastLink: LinkSpec? = null,
)
