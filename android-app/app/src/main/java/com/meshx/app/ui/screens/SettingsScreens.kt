package com.meshx.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshx.app.core.AppController
import com.meshx.app.core.ConfirmAction
import com.meshx.app.core.OtaPhase
import com.meshx.app.core.Screen
import com.meshx.app.core.Sheet
import com.meshx.app.core.UiState
import com.meshx.app.model.LinkState
import com.meshx.app.model.ThemeMode
import com.meshx.app.protocol.ConfigKeys
import com.meshx.app.protocol.Protocol
import com.meshx.app.ui.components.Card
import com.meshx.app.ui.components.Chip
import com.meshx.app.ui.components.EmptyState
import com.meshx.app.ui.components.MeshSwitch
import com.meshx.app.ui.components.PrimaryButton
import com.meshx.app.ui.components.ProgressBar
import com.meshx.app.ui.components.SectionTitle
import com.meshx.app.ui.components.SettingRow
import com.meshx.app.ui.components.SoftButton
import com.meshx.app.ui.components.TopBar
import com.meshx.app.ui.components.bytesLabel
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshIcons

@Composable
fun SettingsScreen(state: UiState, app: AppController) {
    val c = Mesh.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Me")
            Card {
                SettingRow(MeshIcons.User, "Name", state.profile.name, onClick = { app.showSheet(Sheet.EditSetting("name")) })
                SettingRow(MeshIcons.QrCode, "My contact code", "Let someone scan it to add you", onClick = { app.showMyQr() })
                SettingRow(MeshIcons.Locate, "Share my location", "Sends the phone's GPS position to the mesh, so others see where my node is") {
                    MeshSwitch(state.profile.shareLocation, { app.setShareLocation(it) })
                }
            }

            SectionTitle("Connection")
            Card {
                val spec = state.link.spec
                SettingRow(
                    MeshIcons.Radio,
                    when (state.link.state) {
                        LinkState.Connected -> "Connected as node ${state.myId}"
                        LinkState.Connecting -> "Connecting"
                        LinkState.Lost -> "Reconnecting"
                        LinkState.Idle -> "Not connected"
                    },
                    listOfNotNull(spec?.transport?.label, state.link.note.takeIf { it.isNotEmpty() }).joinToString(" · "),
                    onClick = { app.open(Screen.Connect) },
                )
                if (state.link.state == LinkState.Idle && spec != null) {
                    SoftButton("Connect again", { app.reconnect() }, Modifier.padding(top = 6.dp), icon = MeshIcons.Refresh)
                }
                SettingRow(
                    MeshIcons.Cpu, "Node settings",
                    if (state.connected) "Wi-Fi name and password, radio, network key, firmware" else "Connect to a node first",
                    onClick = { app.open(Screen.NodeSettings) },
                )
            }

            if (state.isDemo && state.connected) {
                SectionTitle("Demo tools")
                Card {
                    Text("Switch a node off and watch the Nodes and Network tabs. The mesh finds another route after a few seconds.", color = c.muted, fontSize = 13.sp)
                    for (id in 2..4) {
                        SettingRow(MeshIcons.Power, state.nameOf(id), "Node $id") { MeshSwitch(id !in state.demoOff, { app.setDemoPower(id, it) }) }
                    }
                }
            }

            SectionTitle("Appearance")
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in ThemeMode.entries) Chip(mode.label, state.profile.theme == mode, onClick = { app.setTheme(mode) })
                }
            }

            SectionTitle("Data")
            Card {
                SettingRow(
                    MeshIcons.Trash, "Delete chats and contacts", "Removes them from this phone only",
                    onClick = {
                        app.showSheet(Sheet.Confirm("Delete everything?", "All chats and contacts are removed from this phone. This cannot be undone.", "Delete", ConfirmAction.ClearEverything))
                    },
                )
            }

            SectionTitle("About")
            Card {
                SettingRow(MeshIcons.Info, "MeshX 0.2", "Off-grid LoRa mesh messenger · protocol version ${Protocol.VERSION}")
            }
        }
    }
}

@Composable
fun NodeSettingsScreen(state: UiState, app: AppController) {
    val c = Mesh.colors
    val config = state.config
    Column(Modifier.fillMaxSize()) {
        TopBar("Node settings", if (state.connected) "Node ${state.myId} · changes are saved on the node" else "", onBack = { app.back() })
        if (!state.connected) {
            EmptyState(MeshIcons.Unplug, "Not connected", "Node settings can be changed while the phone is connected to the node.", "Connect") { app.open(Screen.Connect) }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Identity")
            Card {
                SettingRow(MeshIcons.Nodes, "Node number", config[ConfigKeys.ID] ?: state.myId.toString(), onClick = { app.showSheet(Sheet.EditSetting(ConfigKeys.ID)) })
                SettingRow(MeshIcons.Cpu, "Hardware", config[ConfigKeys.HW] ?: "unknown")
                SettingRow(MeshIcons.Info, "Firmware", config[ConfigKeys.FW] ?: state.myFw)
            }

            if (state.has(ConfigKeys.WIFI)) {
                SectionTitle("Wi-Fi of the node")
                Card {
                    SettingRow(MeshIcons.Wifi, "Wi-Fi access point", "A phone can join the node by Wi-Fi") {
                        MeshSwitch(config[ConfigKeys.WIFI] == "on", { app.setConfig(ConfigKeys.WIFI, if (it) "on" else "off") })
                    }
                    SettingRow(MeshIcons.Pencil, "Network name (SSID)", config[ConfigKeys.WIFI_SSID] ?: "", onClick = { app.showSheet(Sheet.EditSetting(ConfigKeys.WIFI_SSID)) })
                    val pass = config[ConfigKeys.WIFI_PASS] ?: ""
                    SettingRow(
                        MeshIcons.Lock, "Password", if (pass.isEmpty()) "Open network, no password" else "•".repeat(pass.length.coerceAtMost(12)),
                        onClick = { app.showSheet(Sheet.EditSetting(ConfigKeys.WIFI_PASS)) },
                    )
                }
            }

            if (state.has(ConfigKeys.SF)) {
                SectionTitle("Radio")
                Card {
                    Text("Every node of the mesh must use the same spreading factor and bandwidth, or they stop hearing each other.", color = c.muted, fontSize = 13.sp)
                    config[ConfigKeys.FREQ]?.let { Text("Frequency $it MHz", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp)) }
                    Text("Spreading factor (higher reaches further, but is slower)", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (sf in 7..12) Chip("SF$sf", config[ConfigKeys.SF] == sf.toString(), onClick = { app.setConfig(ConfigKeys.SF, sf.toString()) })
                    }
                    Text("Bandwidth", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (bw in listOf("125", "250", "500")) Chip("$bw kHz", config[ConfigKeys.BW] == bw, onClick = { app.setConfig(ConfigKeys.BW, bw) })
                    }
                    Spacer(Modifier.height(6.dp))
                    SettingRow(MeshIcons.Signal, "Transmit power", "${config[ConfigKeys.POWER] ?: "?"} dBm", onClick = { app.showSheet(Sheet.EditSetting(ConfigKeys.POWER)) })
                }
            }

            if (state.has(ConfigKeys.NET_KEY)) {
                SectionTitle("Network key")
                Card {
                    val key = config[ConfigKeys.NET_KEY] ?: ""
                    SettingRow(MeshIcons.Key, "Key of this mesh", "•".repeat(key.length.coerceAtMost(12)) + " · only nodes with the same key can talk", onClick = { app.showSheet(Sheet.EditSetting(ConfigKeys.NET_KEY)) })
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Show as code", { app.showNetworkKeyQr() }, icon = MeshIcons.QrCode)
                        SoftButton("Scan a code", { app.scanQr() }, icon = MeshIcons.Scan)
                    }
                }
            }

            SectionTitle("Firmware update")
            Card {
                if (!state.otaSupported) {
                    Text("This node cannot be updated from the phone.", color = c.muted, fontSize = 14.sp)
                } else {
                    val ota = state.ota
                    Text("Installed version ${config[ConfigKeys.FW] ?: state.myFw}", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    when (ota.phase) {
                        OtaPhase.Idle -> Text("Choose the .bin file of the new firmware. It is sent to the node, checked there and installed.", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        OtaPhase.Ready -> Text("${ota.fileName} · ${bytesLabel(ota.size.toLong())}", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        OtaPhase.Sending, OtaPhase.Verifying -> {
                            Text(
                                if (ota.phase == OtaPhase.Sending) "Sending ${ota.fileName}: ${bytesLabel(ota.sent.toLong())} of ${bytesLabel(ota.size.toLong())}" else "The node is checking the file",
                                color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                            )
                            ProgressBar(ota.progress)
                            Text("Keep the phone close to the node and do not switch it off.", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        OtaPhase.Done -> Text(ota.message, color = c.success, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                        OtaPhase.Failed -> Text("Update failed: ${ota.message}", color = c.danger, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (ota.busy) {
                            SoftButton("Cancel", { app.cancelOta() }, danger = true)
                        } else {
                            SoftButton("Choose file", { app.pickFirmware() }, icon = MeshIcons.FileUp)
                            if (state.isDemo) SoftButton("Use sample file", { app.useSampleFirmware() })
                            if (ota.phase == OtaPhase.Ready || ota.phase == OtaPhase.Failed) PrimaryButton("Start update", { app.startOta() })
                        }
                    }
                }
            }

            SectionTitle("Maintenance")
            Card {
                SettingRow(
                    MeshIcons.Power, "Restart the node", "The connection drops for a few seconds",
                    onClick = { app.showSheet(Sheet.Confirm("Restart the node?", "The node restarts and rejoins the mesh. Messages on their way may be lost.", "Restart", ConfirmAction.RebootNode)) },
                )
            }
            if (state.configError.isNotEmpty() && state.sheet == null) {
                Text(state.configError, color = c.danger, fontSize = 13.sp)
            }
        }
    }
}
