package com.meshx.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshx.app.core.AppController
import com.meshx.app.core.NetworkView
import com.meshx.app.core.Screen
import com.meshx.app.core.UiState
import com.meshx.app.mesh.Geo
import com.meshx.app.model.NodeInfo
import com.meshx.app.model.PacketLog
import com.meshx.app.model.RoutingMode
import com.meshx.app.ui.components.Avatar
import com.meshx.app.ui.components.Card
import com.meshx.app.ui.components.Chip
import com.meshx.app.ui.components.EmptyState
import com.meshx.app.ui.components.LinkPill
import com.meshx.app.ui.components.SectionTitle
import com.meshx.app.ui.components.SoftButton
import com.meshx.app.ui.components.Tag
import com.meshx.app.ui.components.TopBar
import com.meshx.app.ui.components.agoLabel
import com.meshx.app.ui.components.bytesLabel
import com.meshx.app.ui.components.clockLabel
import com.meshx.app.ui.components.hopsLabel
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshIcons
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

@Composable
fun NetworkScreen(state: UiState, app: AppController) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Network") { LinkPill(state) { app.open(Screen.Connect) } }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (view in NetworkView.entries) Chip(view.label, view == state.networkView, onClick = { app.setNetworkView(view) })
        }
        when (state.networkView) {
            NetworkView.Topology -> TopologyView(state, app)
            NetworkView.Map -> MapView(state)
            NetworkView.Traffic -> TrafficView(state, app)
        }
    }
}

// ---------------------------------------------------------------------- topology

@Composable
private fun TopologyView(state: UiState, app: AppController) {
    val c = Mesh.colors
    val me = state.myNode
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Text("How messages find their way", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in RoutingMode.entries) {
                        Chip(mode.label, mode == state.routingMode, onClick = if (state.connected) ({ app.setRoutingMode(mode) }) else null)
                    }
                }
                Text(state.routingMode.blurb, color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            if (me == null) {
                EmptyState(MeshIcons.Network, "No mesh yet", "Connect to a node to see the map of links between all nodes.")
            } else {
                Card(padding = 0.dp) { Graph(state, me, app) }
            }
        }
        item {
            val target = state.routeTarget
            val route = state.routes[target]
            Card {
                if (target == 0 || state.nodes[target] == null) {
                    Text("Tap a node in the picture to see the route to it.", color = c.muted, fontSize = 14.sp)
                } else {
                    Text("Route to ${state.nameOf(target)}", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            route == null || state.nodes[target]?.online != true -> "No route, the node is offline"
                            route.flood -> "Flooding: every node repeats the message until it arrives"
                            route.path.isEmpty() -> "No route found"
                            else -> route.path.joinToString("  →  ") { if (it == state.myId) "You" else state.nameOf(it) }
                        },
                        color = c.ink, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    if (route != null && route.path.size >= 2) {
                        val weakest = route.path.zipWithNext().minOf { (a, b) ->
                            state.links.firstOrNull { (it.a == a && it.b == b) || (it.a == b && it.b == a) }?.score ?: 0
                        }
                        Text("${hopsLabel(route.path.size - 1)} · weakest link scores $weakest", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(c.success, "good link")
                    Legend(c.warning, "fair")
                    Legend(c.danger, "weak")
                    Legend(c.primary, "route")
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = Mesh.colors.muted, fontSize = 12.sp)
    }
}

/** The nodes on a circle, with a line for every link. Tapping a node shows the route to it. */
@Composable
private fun Graph(state: UiState, me: NodeInfo, app: AppController) {
    val c = Mesh.colors
    val nodes = listOf(me) + state.otherNodes.sortedBy { it.id }
    val route = state.routes[state.routeTarget]?.path ?: emptyList()
    val routeEdges = route.zipWithNext().map { (a, b) -> if (a < b) a to b else b to a }.toSet()
    BoxWithConstraints(Modifier.fillMaxWidth().height(320.dp)) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val nodeRadius = with(density) { 22.dp.toPx() }
        val ring = min(w, h) / 2 - with(density) { 58.dp.toPx() }
        val places = HashMap<Int, Offset>()
        nodes.forEachIndexed { i, node ->
            // my node sits on the left, the others follow clockwise
            val angle = PI + 2 * PI * i / nodes.size
            places[node.id] = if (nodes.size == 1) Offset(w / 2, h / 2) else Offset(w / 2 + (ring * cos(angle)).toFloat() * 1.25f, h / 2 + (ring * sin(angle)).toFloat())
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(nodes.map { it.id }) {
                detectTapGestures { tap ->
                    val hit = places.entries.minByOrNull { hypot(it.value.x - tap.x, it.value.y - tap.y) }
                    if (hit != null && hypot(hit.value.x - tap.x, hit.value.y - tap.y) < nodeRadius * 2 && hit.key != state.myId) {
                        app.selectRouteTarget(hit.key)
                    }
                }
            },
        ) {
            for (link in state.links) {
                val a = places[link.a] ?: continue
                val b = places[link.b] ?: continue
                val onRoute = (link.a to link.b) in routeEdges
                if (onRoute) drawLine(c.primary.copy(alpha = 0.35f), a, b, strokeWidth = 12.dp.toPx(), cap = StrokeCap.Round)
                drawLine(c.score(link.score), a, b, strokeWidth = (if (onRoute) 4.dp else 2.5.dp).toPx(), cap = StrokeCap.Round)
            }
            for (node in nodes) {
                val at = places[node.id] ?: continue
                val isMe = node.id == state.myId
                val selected = node.id == state.routeTarget
                drawCircle(if (isMe) c.primary else if (node.online) c.surface else c.surfaceAlt, nodeRadius, at)
                drawCircle(
                    if (isMe || selected) c.primary else if (node.online) c.ink.copy(alpha = 0.55f) else c.outline,
                    nodeRadius, at, style = Stroke(width = (if (selected) 4.dp else 2.dp).toPx()),
                )
            }
        }
        // numbers, names and link scores are normal text placed over the drawing
        for (node in nodes) {
            val at = places[node.id] ?: continue
            val isMe = node.id == state.myId
            Label(at, 44.dp, 44.dp, density.density) {
                Text(node.id.toString(), color = if (isMe) c.onPrimary else if (node.online) c.ink else c.muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Label(Offset(at.x, at.y + nodeRadius + with(density) { 12.dp.toPx() }), 96.dp, 20.dp, density.density) {
                Text(
                    if (isMe) "You" else state.nameOf(node.id), color = if (node.online) c.ink else c.muted, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
        for (link in state.links) {
            val a = places[link.a] ?: continue
            val b = places[link.b] ?: continue
            Label(Offset((a.x + b.x) / 2, (a.y + b.y) / 2), 30.dp, 18.dp, density.density) {
                Text(
                    link.score.toString(), color = c.score(link.score), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.surface).padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Puts a small box with its centre at the given pixel position. */
@Composable
private fun Label(center: Offset, width: Dp, height: Dp, density: Float, content: @Composable () -> Unit) {
    Box(
        Modifier.offset(x = (center.x / density).dp - width / 2, y = (center.y / density).dp - height / 2).size(width, height),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------------------------------------------------------------------- map

@Composable
private fun MapView(state: UiState) {
    val c = Mesh.colors
    val me = state.myNode
    val myLat = me?.lat
    val myLon = me?.lon
    if (me == null || myLat == null || myLon == null) {
        EmptyState(MeshIcons.Locate, "No position yet", "Turn on Share my location in Settings. Other nodes appear here when they share theirs. GPS works without internet.")
        return
    }
    class Spot(val node: NodeInfo, val meters: Double, val bearing: Double)
    val spots = state.otherNodes.mapNotNull { node ->
        val lat = node.lat ?: return@mapNotNull null
        val lon = node.lon ?: return@mapNotNull null
        Spot(node, Geo.distanceMeters(myLat, myLon, lat, lon), Geo.bearingDegrees(myLat, myLon, lat, lon))
    }.sortedBy { it.meters }
    val farthest = spots.maxOfOrNull { it.meters } ?: 100.0
    val range = listOf(100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10_000.0, 25_000.0, 50_000.0).firstOrNull { it >= farthest } ?: farthest
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(padding = 0.dp) {
                BoxWithConstraints(Modifier.fillMaxWidth().height(320.dp)) {
                    val density = LocalDensity.current
                    val w = with(density) { maxWidth.toPx() }
                    val h = with(density) { maxHeight.toPx() }
                    val radius = min(w, h) / 2 - with(density) { 30.dp.toPx() }
                    val centre = Offset(w / 2, h / 2)
                    fun place(spot: Spot): Offset {
                        val r = radius * (spot.meters / range).toFloat()
                        val angle = Math.toRadians(spot.bearing)
                        return Offset(centre.x + r * sin(angle).toFloat(), centre.y - r * cos(angle).toFloat())
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        for (step in 1..3) drawCircle(c.outline, radius * step / 3, centre, style = Stroke(width = 1.5.dp.toPx()))
                        drawLine(c.outline, Offset(centre.x, centre.y - radius), Offset(centre.x, centre.y + radius), strokeWidth = 1.dp.toPx())
                        drawLine(c.outline, Offset(centre.x - radius, centre.y), Offset(centre.x + radius, centre.y), strokeWidth = 1.dp.toPx())
                        for (spot in spots) {
                            val at = place(spot)
                            drawLine(c.score(spot.node.score).copy(alpha = if (spot.node.online) 0.5f else 0.15f), centre, at, strokeWidth = 2.dp.toPx())
                            drawCircle(if (spot.node.online) c.score(spot.node.score) else c.muted, 7.dp.toPx(), at)
                        }
                        drawCircle(c.primary, 9.dp.toPx(), centre)
                        drawCircle(c.surface, 4.dp.toPx(), centre)
                    }
                    Label(Offset(centre.x, centre.y - radius - with(density) { 12.dp.toPx() }), 30.dp, 18.dp, density.density) {
                        Text("N", color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    for (step in 1..3) {
                        Label(Offset(centre.x + with(density) { 24.dp.toPx() }, centre.y + radius * step / 3 - with(density) { 8.dp.toPx() }), 60.dp, 16.dp, density.density) {
                            Text(Geo.formatDistance(range * step / 3), color = c.muted, fontSize = 10.sp)
                        }
                    }
                    for (spot in spots) {
                        val at = place(spot)
                        Label(Offset(at.x, at.y - with(density) { 17.dp.toPx() }), 90.dp, 18.dp, density.density) {
                            Text(
                                state.nameOf(spot.node.id), color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.surface.copy(alpha = 0.85f)).padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        item { SectionTitle("Distance from my node") }
        if (spots.isEmpty()) {
            item { Text("No other node has shared a position yet.", color = c.muted, fontSize = 14.sp) }
        }
        items(spots, key = { it.node.id }) { spot ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.nameOf(spot.node.id), spot.node.id, 40.dp, spot.node.online)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.nameOf(spot.node.id), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Position from ${agoLabel(spot.node.posAt, state.now)}", color = c.muted, fontSize = 12.sp)
                }
                Text("${Geo.formatDistance(spot.meters)} ${Geo.compass(spot.bearing)}", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---------------------------------------------------------------------- traffic

@Composable
private fun TrafficView(state: UiState, app: AppController) {
    val c = Mesh.colors
    val stats = state.stats
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card {
                Row {
                    Stat(stats.packets.toString(), "packets", Modifier.weight(1f))
                    Stat(bytesLabel(stats.bytes), "data", Modifier.weight(1f))
                    Stat((stats.perSecond.sum() * 2).toString(), "per minute", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                val bar = c.primary
                val peak = (stats.perSecond.maxOrNull() ?: 0).coerceAtLeast(4)
                Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                    val slot = size.width / stats.perSecond.size
                    stats.perSecond.forEachIndexed { i, count ->
                        val barHeight = (size.height * count / peak).coerceAtLeast(2.dp.toPx())
                        drawRoundRect(
                            bar.copy(alpha = if (count == 0) 0.2f else 1f), Offset(i * slot + slot * 0.15f, size.height - barHeight),
                            Size(slot * 0.7f, barHeight), CornerRadius(slot * 0.2f),
                        )
                    }
                }
                Text("Packets my node sent or heard in each of the last 30 seconds", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((kind, count) in stats.byKind.entries.sortedByDescending { it.value }) Tag("$kind $count", kindColor(kind), c.surfaceAlt)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton(
                        if (state.trafficPaused) "Resume list" else "Pause list", { app.setTrafficPaused(!state.trafficPaused) },
                        icon = if (state.trafficPaused) MeshIcons.Play else MeshIcons.Pause,
                    )
                    SoftButton("Clear", { app.clearTraffic() }, icon = MeshIcons.Trash)
                }
            }
        }
        if (state.traffic.isEmpty()) {
            item { Text(if (state.connected) "Waiting for packets." else "Connect to a node to watch the traffic.", color = c.muted, fontSize = 14.sp) }
        }
        items(state.traffic.asReversed().take(60), key = { it.seq }) { packet -> PacketRow(state, packet) }
    }
}

@Composable
private fun kindColor(kind: String): Color {
    val c = Mesh.colors
    return when (kind) {
        "MSG" -> c.primaryDeep
        "ACK" -> c.success
        "BEACON" -> c.muted
        else -> c.warning
    }
}

@Composable
private fun PacketRow(state: UiState, packet: PacketLog) {
    val c = Mesh.colors
    val mine = packet.via == state.myId
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(64.dp)) { Tag(packet.kind, kindColor(packet.kind), c.surfaceAlt) }
        Column(Modifier.weight(1f)) {
            Text(
                "${state.nameOf(packet.src)}  →  ${state.nameOf(packet.dst)}", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    mine && packet.src == state.myId -> "sent by my node · ${packet.size} B"
                    mine -> "forwarded by my node · hop ${packet.hops} · ${packet.size} B"
                    else -> "heard from ${state.nameOf(packet.via)} · hop ${packet.hops} · ${packet.rssi} dBm · ${packet.size} B"
                },
                color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(clockLabel(packet.time), color = c.muted, fontSize = 11.sp)
    }
}
