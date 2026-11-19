package com.meshx.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshx.app.core.AppController
import com.meshx.app.core.SystemPanel
import com.meshx.app.core.UiState
import com.meshx.app.model.LinkSpec
import com.meshx.app.model.LinkState
import com.meshx.app.ui.components.Card
import com.meshx.app.ui.components.Field
import com.meshx.app.ui.components.MeshSwitch
import com.meshx.app.ui.components.PrimaryButton
import com.meshx.app.ui.components.SignalBars
import com.meshx.app.ui.components.SoftButton
import com.meshx.app.ui.components.TopBar
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshIcons

@Composable
fun WelcomeScreen(app: AppController) {
    val c = Mesh.colors
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
            Icon(MeshIcons.Nodes, null, tint = c.onPrimary, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("MeshX", color = c.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Send messages without internet or mobile network. Your phone talks to a small radio node, and the nodes pass messages along.",
            color = c.muted, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Field(name, { name = it.take(20) }, "Your name", placeholder = "How others will see you", onDone = { app.finishWelcome(name) })
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Get started", { app.finishWelcome(name) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
    }
}

@Composable
fun ConnectScreen(state: UiState, app: AppController) {
    val c = Mesh.colors
    var host by remember { mutableStateOf(state.profile.wifiHost) }
    var port by remember { mutableStateOf(state.profile.wifiPort.toString()) }
    var wifiProblem by remember { mutableStateOf("") }
    var phoneId by remember { mutableStateOf(state.profile.phoneNodeId.takeIf { it > 0 }?.toString() ?: "") }
    var star by remember { mutableStateOf(false) }
    var phoneProblem by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        TopBar("Connect", "Choose how this phone reaches the mesh", onBack = if (state.stack.size > 1) ({ app.back() }) else null)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val spec = state.link.spec
            if (state.link.state != LinkState.Idle && spec != null) {
                Card {
                    val target = when (spec) {
                        is LinkSpec.Ble -> spec.name
                        is LinkSpec.Wifi -> "${spec.host}:${spec.port}"
                        is LinkSpec.Phone -> "phones nearby as node ${spec.nodeId}"
                        LinkSpec.Demo -> "the demo mesh"
                    }
                    Text(
                        when (state.link.state) {
                            LinkState.Connected -> "Connected to $target"
                            LinkState.Lost -> "Connection lost, trying again"
                            else -> "Connecting to $target"
                        },
                        color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    val detail = state.link.note.ifEmpty { state.link.error }
                    if (detail.isNotEmpty()) Text(detail, color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(10.dp))
                    SoftButton("Disconnect", { app.disconnect() }, icon = MeshIcons.Unplug, danger = true)
                }
            } else if (state.link.error.isNotEmpty()) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(MeshIcons.Alert, null, tint = c.danger, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(state.link.error, color = c.ink, fontSize = 14.sp)
                    }
                }
            }
            val blocker = state.blocker
            if (blocker != null) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(MeshIcons.Alert, null, tint = c.warning, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(blocker.text, color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    SoftButton("Open settings", { app.openPanel(blocker.panel) })
                }
            }

            Option(MeshIcons.Bluetooth, "MeshX node over Bluetooth", "For your LoRa node. Stand close to it and scan.") {
                SoftButton(
                    if (state.scan.scanning) "Scanning, tap to stop" else "Scan for nodes",
                    { if (state.scan.scanning) app.stopScan() else app.startScan() },
                    icon = MeshIcons.Refresh,
                )
                if (state.scan.error.isNotEmpty()) Text(state.scan.error, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                for (found in state.scan.devices) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt)
                            .clickable { app.connectBle(found) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(found.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("${found.address} · ${found.rssi} dBm", color = c.muted, fontSize = 12.sp)
                        }
                        SignalBars(((found.rssi + 100) * 2).coerceIn(1, 100))
                    }
                }
                if (!state.scan.scanning && state.scan.devices.isEmpty() && state.scan.error.isEmpty()) {
                    Text("Nodes are listed here when their name starts with MeshX.", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            Option(MeshIcons.Wifi, "MeshX node over Wi-Fi", "First join the Wi-Fi network of the node in the phone settings.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(host, { host = it.trim(); wifiProblem = "" }, "Address", Modifier.weight(2f))
                    Field(port, { port = it.filter(Char::isDigit).take(5); wifiProblem = "" }, "Port", Modifier.weight(1f), number = true)
                }
                if (wifiProblem.isNotEmpty()) Text(wifiProblem, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Wi-Fi settings", { app.openPanel(SystemPanel.Wifi) })
                    PrimaryButton("Connect", { wifiProblem = app.connectWifi(host, port) ?: "" }, Modifier.weight(1f))
                }
            }

            Option(MeshIcons.Phone, "Phone to phone", "No hardware needed. Phones nearby connect over Bluetooth and Wi-Fi and pass messages along.") {
                Field(phoneId, { phoneId = it.filter(Char::isDigit).take(5); phoneProblem = "" }, "My node number", number = true, error = phoneProblem)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fast mode", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Wi-Fi Direct with one phone as the hub", color = c.muted, fontSize = 12.sp)
                    }
                    MeshSwitch(star, { star = it })
                }
                PrimaryButton("Start", { phoneProblem = app.connectPhone(phoneId, star) ?: "" }, Modifier.fillMaxWidth())
            }

            Option(MeshIcons.Flask, "Demo mesh", "Four simulated nodes that behave like real ones. Good for learning the app and for presentations.") {
                PrimaryButton("Start demo", { app.connectDemo() }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, text: String, content: @Composable () -> Unit) {
    val c = Mesh.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.primarySoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.primaryDeep, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(text, color = c.muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}
