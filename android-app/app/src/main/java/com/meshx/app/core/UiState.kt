
package com.meshx.app.core

import com.meshx.app.model.BROADCAST_ID
import com.meshx.app.model.BleDevice
import com.meshx.app.model.ChatMessage
import com.meshx.app.model.Contact
import com.meshx.app.model.LinkSpec
import com.meshx.app.model.LinkState
import com.meshx.app.model.MeshLink
import com.meshx.app.model.NodeInfo
import com.meshx.app.model.PacketLog
import com.meshx.app.model.PingResult
import com.meshx.app.model.Profile
import com.meshx.app.model.RoutingMode
import com.meshx.app.protocol.ConfigKeys

enum class Tab(val label: String) {
    Chats("Chats"), Contacts("Contacts"), Nodes("Nodes"), Network("Network"), Settings("Settings")
}

enum class NetworkView(val label: String) { Topology("Topology"), Map("Map"), Traffic("Traffic") }

/** Full screens. The app keeps a back stack of these. */
sealed class Screen {
    object Welcome : Screen()
    object Main : Screen()
    object Connect : Screen()
    data class Chat(val peer: Int) : Screen()
    data class NodeDetail(val id: Int) : Screen()
    object NodeSettings : Screen()
}

/** Panels that slide over the current screen. */
sealed class Sheet {
    object NewChat : Sheet()
    data class ContactEditor(val id: Int?, val name: String) : Sheet()
    data class ShowQr(val title: String, val payload: String, val caption: String) : Sheet()
    data class EditSetting(val key: String) : Sheet()
    data class Confirm(val title: String, val text: String, val button: String, val action: ConfirmAction) : Sheet()
}

sealed class ConfirmAction {
    data class DeleteChat(val peer: Int) : ConfirmAction()
    data class RemoveContact(val id: Int) : ConfirmAction()
    data class ForgetNode(val id: Int) : ConfirmAction()
    data class ApplyNetworkKey(val key: String) : ConfirmAction()
    object RebootNode : ConfirmAction()
    object ClearEverything : ConfirmAction()
}

data class LinkStatus(
    val state: LinkState = LinkState.Idle,
    val spec: LinkSpec? = null,
    val note: String = "",      // extra status from the link, for example "2 phones connected"
    val error: String = "",     // why the last connection failed
)

data class ScanStatus(
    val scanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val error: String = "",
)

/** A problem that stops a feature, with the phone setting that fixes it. */
data class Blocker(val text: String, val panel: SystemPanel)

enum class OtaPhase { Idle, Ready, Sending, Verifying, Done, Failed }

data class OtaStatus(
    val phase: OtaPhase = OtaPhase.Idle,
    val fileName: String = "",
    val size: Int = 0,
    val sent: Int = 0,
    val message: String = "",
) {
    val progress: Float get() = if (size == 0) 0f else sent.toFloat() / size
    val busy: Boolean get() = phase == OtaPhase.Sending || phase == OtaPhase.Verifying
}

data class TrafficStats(
    val packets: Int = 0,
    val bytes: Long = 0,
    val byKind: Map<String, Int> = emptyMap(),
    val perSecond: List<Int> = List(30) { 0 },     // packets in each of the last 30 seconds, newest last
)
// changes by Fatima
data class TestRun(
    val target: Int = 0,
    val mode: RoutingMode = RoutingMode.Adaptive,
    val total: Int = 0,
    val sent: Int = 0,
    val delivered: Int = 0,
    val failed: Int = 0,
    val results: List<String> = emptyList()
)
//
data class RouteInfo(val path: List<Int> = emptyList(), val flood: Boolean = false)

data class Conversation(val peer: Int, val last: ChatMessage, val unread: Int)

/** One immutable picture of the whole app. The screens only draw this. */
data class UiState(
    val loaded: Boolean = false,
    val now: Long = 0,
    val profile: Profile = Profile(),

    // navigation
    val stack: List<Screen> = listOf(Screen.Welcome),
    val tab: Tab = Tab.Chats,
    val networkView: NetworkView = NetworkView.Topology,
    val sheet: Sheet? = null,
    val notice: String = "",
    val noticeId: Long = 0,

    // connection
    val link: LinkStatus = LinkStatus(),
    val scan: ScanStatus = ScanStatus(),
    val blocker: Blocker? = null,

    // my node and the mesh
    val myId: Int = 0,
    val myBattery: Int = -1,
    val myFw: String = "",
    val nodes: Map<Int, NodeInfo> = emptyMap(),
    val links: List<MeshLink> = emptyList(),
    val routes: Map<Int, RouteInfo> = emptyMap(),
    val routeTarget: Int = 0,
    val routingMode: RoutingMode = RoutingMode.Adaptive,
    val history: Map<Int, List<Int>> = emptyMap(),      // recent route scores per node, for the charts
    val pings: Map<Int, PingResult> = emptyMap(),       // by destination
    val demoOff: Set<Int> = emptySet(),                 // demo nodes that are switched off

    // people and messages
    val contacts: List<Contact> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val unread: Map<Int, Int> = emptyMap(),

    // traffic monitor
    val traffic: List<PacketLog> = emptyList(),
    val trafficPaused: Boolean = false,
    val stats: TrafficStats = TrafficStats(),
    //changes by Fatima
    val test: TestRun? = null,
    //

    // node settings
    val config: Map<String, String> = emptyMap(),
    val configBusy: String = "",
    val configError: String = "",
    val ota: OtaStatus = OtaStatus(),
) {
    val screen: Screen get() = stack.last()
    val connected: Boolean get() = link.state == LinkState.Connected
    val isDemo: Boolean get() = link.spec is LinkSpec.Demo
    val myNode: NodeInfo? get() = nodes[myId]

    /** All other nodes, the ones that are online first. */
    val otherNodes: List<NodeInfo>
        get() = nodes.values.filter { it.id != myId }.sortedWith(compareBy({ !it.online }, { it.id }))

    val onlineCount: Int get() = nodes.values.count { it.online && it.id != myId }

    /** Nodes that were heard on the mesh and are not saved as contacts yet. */
    val heardList: List<NodeInfo>
        get() = otherNodes.filter { node -> contacts.none { it.id == node.id } }

    fun contact(id: Int): Contact? = contacts.firstOrNull { it.id == id }

    /** The best name we have: contact name, then the name the node announces, then its number. */
    fun nameOf(id: Int): String = when {
        id == BROADCAST_ID -> "Everyone"
        id == myId && myId != 0 -> profile.name.ifBlank { "Me" }
        else -> contact(id)?.name?.takeIf { it.isNotBlank() }
            ?: nodes[id]?.name?.takeIf { it.isNotBlank() }
            ?: "Node $id"
    }

    val conversations: List<Conversation>
        get() = messages.groupBy { it.peer }
            .map { (peer, list) -> Conversation(peer, list.last(), unread[peer] ?: 0) }
            .sortedByDescending { it.last.time }

    fun chat(peer: Int): List<ChatMessage> = messages.filter { it.peer == peer }

    val unreadTotal: Int get() = unread.values.sum()

    fun has(key: String): Boolean = config.containsKey(key)
    val otaSupported: Boolean get() = config[ConfigKeys.OTA] == "yes"
}
