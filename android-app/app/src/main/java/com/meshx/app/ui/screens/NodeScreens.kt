package com.meshx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshx.app.core.AppController
import com.meshx.app.core.ConfirmAction
import com.meshx.app.core.Screen
import com.meshx.app.core.Sheet
import com.meshx.app.core.UiState
import com.meshx.app.mesh.Geo
import com.meshx.app.mesh.LinkScore
import com.meshx.app.model.NodeInfo
import com.meshx.app.ui.components.Avatar
import com.meshx.app.ui.components.Card
import com.meshx.app.ui.components.EmptyState
import com.meshx.app.ui.components.LinkPill
import com.meshx.app.ui.components.MeshSwitch
import com.meshx.app.ui.components.PrimaryButton
import com.meshx.app.ui.components.SectionTitle
import com.meshx.app.ui.components.SettingRow
import com.meshx.app.ui.components.SignalBars
import com.meshx.app.ui.components.SoftButton
import com.meshx.app.ui.components.Sparkline
import com.meshx.app.ui.components.Tag
import com.meshx.app.ui.components.TopBar
import com.meshx.app.ui.components.agoLabel
import com.meshx.app.ui.components.hopsLabel
import com.meshx.app.ui.components.uptimeLabel
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshIcons
import java.util.Locale

/** A number with a small label under it. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    val c = Mesh.colors
    Column(modifier) {
        Text(value, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = c.muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** Distance and direction from my node to another one, or null when a position is missing. */
fun distanceLabel(state: UiState, node: NodeInfo): String? {
    val me = state.myNode ?: return null
    val lat1 = me.lat ?: return null
    val lon1 = me.lon ?: return null
    val lat2 = node.lat ?: return null
    val lon2 = node.lon ?: return null
    val meters = Geo.distanceMeters(lat1, lon1, lat2, lon2)
    return "${Geo.formatDistance(meters)} ${Geo.compass(Geo.bearingDegrees(lat1, lon1, lat2, lon2))}"
}

@Composable
fun NodesScreen(state: UiState, app: AppController) {
    val c = Mesh.colors
    val others = state.otherNodes
    Column(Modifier.fillMaxSize()) {
        TopBar("Nodes") { LinkPill(state) { app.open(Screen.Connect) } }
        if (!state.connected && others.isEmpty()) {
            EmptyState(MeshIcons.Nodes, "No node connected", "Connect to a node to see every device on the mesh and how well it is reachable.", "Connect") {
                app.open(Screen.Connect)
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val me = state.myNode
                Card {
                    Row {
                        Stat("${state.onlineCount} of ${others.size}", "nodes online", Modifier.weight(1f))
                        Stat(if (state.myBattery >= 0) "${state.myBattery}%" else "n/a", "my battery", Modifier.weight(1f))
                        Stat(if (me != null && state.connected) uptimeLabel(me.uptimeSec) else "n/a", "my uptime", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (state.connected) "My node is number ${state.myId}, firmware ${state.myFw}. It sent ${me?.tx ?: 0} and heard ${me?.rx ?: 0} packets."
                        else "Not connected. The list shows what was known last time.",
                        color = c.muted, fontSize = 13.sp,
                    )
                }
            }
            items(others, key = { it.id }) { node -> NodeCard(state, node) { app.open(Screen.NodeDetail(node.id)) } }
        }
    }
}

@Composable
private fun NodeCard(state: UiState, node: NodeInfo, onClick: () -> Unit) {
    val c = Mesh.colors
    Card(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(state.nameOf(node.id), node.id, 46.dp, node.online)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        state.nameOf(node.id), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (node.online) Tag(if (node.hops > 0) hopsLabel(node.hops) else "no route") else Tag("offline", c.muted, c.surfaceAlt)
                }
                Text(
                    when {
                        !node.online -> "Node ${node.id} · last seen ${agoLabel(node.lastSeenAt, state.now)}"
                        node.rssi != 0 -> "Node ${node.id} · ${node.rssi} dBm · SNR ${String.format(Locale.US, "%.1f", node.snr)} dB"
                        node.nextHop != 0 -> "Node ${node.id} · through ${state.nameOf(node.nextHop)}"
                        else -> "Node ${node.id}"
                    },
                    color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val extras = listOfNotNull(
                    if (node.battery >= 0) "Battery ${node.battery}%" else null,
                    distanceLabel(state, node),
                )
                if (extras.isNotEmpty()) Text(extras.joinToString(" · "), color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (node.online) {
                Column(horizontalAlignment = Alignment.End) {
                    SignalBars(node.score)
                    Text(node.score.toString(), color = c.score(node.score), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun NodeDetailScreen(state: UiState, id: Int, app: AppController) {
    val c = Mesh.colors
    val node = state.nodes[id] ?: NodeInfo(id)
    val name = state.nameOf(id)
    val contact = state.contact(id)
    Column(Modifier.fillMaxSize()) {
        TopBar(name, "Node $id", onBack = { app.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name, id, 56.dp, node.online)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (node.online) "Online now" else "Offline · last seen ${agoLabel(node.lastSeenAt, state.now)}",
                            color = if (node.online) c.success else c.muted, fontSize = 14.sp,
                        )
                        if (node.name.isNotBlank() && node.name != name) Text("Announces itself as ${node.name}", color = c.muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Message", { app.openChat(id) }, Modifier.weight(1f), icon = MeshIcons.Chats)
                    SoftButton(
                        if (contact == null) "Add contact" else "Edit contact",
                        { app.showSheet(Sheet.ContactEditor(id, contact?.name ?: node.name)) },
                        Modifier.weight(1f), icon = if (contact == null) MeshIcons.UserPlus else MeshIcons.Pencil,
                    )
                }
            }

            SectionTitle("Signal")
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (node.online) node.score.toString() else "0", color = c.score(if (node.online) node.score else 0), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (node.online) LinkScore.word(node.score) else "No link", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Route quality, 0 to 100", color = c.muted, fontSize = 12.sp)
                    }
                    SignalBars(if (node.online) node.score else 0)
                }
                Spacer(Modifier.height(12.dp))
                if (node.online && node.rssi != 0) {
                    Row {
                        Stat("${node.rssi} dBm", "signal (RSSI)", Modifier.weight(1f))
                        Stat(String.format(Locale.US, "%.1f dB", node.snr), "signal to noise", Modifier.weight(1f))
                        Stat("${node.loss}%", "beacons lost", Modifier.weight(1f))
                    }
                } else if (node.online) {
                    Text("This node is not a direct neighbour, so there is no radio measurement. The score is the weakest link on the route.", color = c.muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                Sparkline(state.history[id] ?: emptyList(), color = c.score(node.score))
                Text("Score over the last two minutes", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }

            SectionTitle("Route")
            Card {
                val route = state.routes[id]
                Text(
                    when {
                        !node.online -> "No route while the node is offline"
                        route == null -> "Waiting for the route"
                        route.flood -> "Flooding: every node repeats the message"
                        route.path.isEmpty() -> "No route found"
                        else -> route.path.joinToString("  →  ") { if (it == state.myId) "You" else state.nameOf(it) }
                    },
                    color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                if (node.online && node.hops > 0) {
                    Text("${hopsLabel(node.hops)} · ${state.routingMode.label} routing", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ping = state.pings[id]
                    SoftButton(if (ping?.pending == true) "Testing" else "Test the route", { app.ping(id) }, icon = MeshIcons.Activity, enabled = ping?.pending != true)
                    Spacer(Modifier.width(12.dp))
                    if (ping != null && !ping.pending) {
                        Text(
                            if (ping.rttMs >= 0) "Answer in ${ping.rttMs} ms over ${hopsLabel(ping.hops)}" else "No answer",
                            color = if (ping.rttMs >= 0) c.success else c.danger, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            SectionTitle("Status")
            Card {
                Row {
                    Stat(if (node.battery >= 0) "${node.battery}%" else "n/a", "battery", Modifier.weight(1f))
                    Stat(uptimeLabel(node.uptimeSec), "uptime", Modifier.weight(1f))
                    Stat(node.fw.ifBlank { "n/a" }, "firmware", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat(node.tx.toString(), "sent", Modifier.weight(1f))
                    Stat(node.rx.toString(), "heard", Modifier.weight(1f))
                    Stat(node.fwd.toString(), "forwarded", Modifier.weight(1f))
                    Stat(node.drop.toString(), "dropped", Modifier.weight(1f))
                }
                Text(
                    if (node.phone) "A phone is connected to this node." else "No phone is connected to this node right now.",
                    color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }

            SectionTitle("Position")
            Card {
                val lat = node.lat
                val lon = node.lon
                if (lat == null || lon == null) {
                    Text("This node has not shared a position.", color = c.muted, fontSize = 14.sp)
                } else {
                    Row {
                        Stat(String.format(Locale.US, "%.5f", lat), "latitude", Modifier.weight(1f))
                        Stat(String.format(Locale.US, "%.5f", lon), "longitude", Modifier.weight(1f))
                        Stat(distanceLabel(state, node) ?: "n/a", "from me", Modifier.weight(1f))
                    }
                    Text("Updated ${agoLabel(node.posAt, state.now)}", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            if (state.isDemo && id in 2..4) {
                Card {
                    SettingRow(MeshIcons.Power, "Demo: node power", "Switch this node off to watch the mesh find a new route") {
                        MeshSwitch(id !in state.demoOff, { app.setDemoPower(id, it) })
                    }
                }
            }
            if (!node.online) {
                SoftButton(
                    "Forget this node",
                    { app.showSheet(Sheet.Confirm("Forget $name?", "It is removed from the list. It comes back when your node hears it again.", "Forget", ConfirmAction.ForgetNode(id))) },
                    Modifier.fillMaxWidth(), icon = MeshIcons.Trash, danger = true,
                )
            }
        }
    }
}
