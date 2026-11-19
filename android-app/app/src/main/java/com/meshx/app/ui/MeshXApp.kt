package com.meshx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshx.app.core.AppController
import com.meshx.app.core.ConfirmAction
import com.meshx.app.core.Screen
import com.meshx.app.core.Sheet
import com.meshx.app.core.Tab
import com.meshx.app.core.UiState
import com.meshx.app.model.BROADCAST_ID
import com.meshx.app.model.ContactSource
import com.meshx.app.protocol.ConfigKeys
import com.meshx.app.ui.components.Avatar
import com.meshx.app.ui.components.Field
import com.meshx.app.ui.components.PrimaryButton
import com.meshx.app.ui.components.QrImage
import com.meshx.app.ui.components.SoftButton
import com.meshx.app.ui.screens.ChatScreen
import com.meshx.app.ui.screens.ChatsScreen
import com.meshx.app.ui.screens.ConnectScreen
import com.meshx.app.ui.screens.ContactsScreen
import com.meshx.app.ui.screens.NetworkScreen
import com.meshx.app.ui.screens.NodeDetailScreen
import com.meshx.app.ui.screens.NodeSettingsScreen
import com.meshx.app.ui.screens.NodesScreen
import com.meshx.app.ui.screens.SettingsScreen
import com.meshx.app.ui.screens.WelcomeScreen
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshIcons

/** The whole user interface. It draws one UiState and sends every tap to the AppController. */
@Composable
fun MeshXApp(state: UiState, app: AppController) {
    val c = Mesh.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        when (val screen = state.screen) {
            Screen.Welcome -> WelcomeScreen(app)
            Screen.Connect -> ConnectScreen(state, app)
            Screen.Main -> MainScreen(state, app)
            is Screen.Chat -> ChatScreen(state, screen.peer, app)
            is Screen.NodeDetail -> NodeDetailScreen(state, screen.id, app)
            Screen.NodeSettings -> NodeSettingsScreen(state, app)
        }
        if (state.notice.isNotEmpty()) {
            Text(
                state.notice, color = c.surface, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 84.dp)
                    .clip(RoundedCornerShape(14.dp)).background(c.ink).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        val sheet = state.sheet
        if (sheet != null) SheetHost(state, sheet, app)
    }
}

@Composable
private fun MainScreen(state: UiState, app: AppController) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                Tab.Chats -> ChatsScreen(state, app)
                Tab.Contacts -> ContactsScreen(state, app)
                Tab.Nodes -> NodesScreen(state, app)
                Tab.Network -> NetworkScreen(state, app)
                Tab.Settings -> SettingsScreen(state, app)
            }
        }
        BottomBar(state, app)
    }
}

@Composable
private fun BottomBar(state: UiState, app: AppController) {
    val c = Mesh.colors
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
            for (tab in Tab.entries) {
                val selected = tab == state.tab
                val icon: ImageVector = when (tab) {
                    Tab.Chats -> MeshIcons.Chats
                    Tab.Contacts -> MeshIcons.Contacts
                    Tab.Nodes -> MeshIcons.Nodes
                    Tab.Network -> MeshIcons.Network
                    Tab.Settings -> MeshIcons.Settings
                }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { app.selectTab(tab) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.primarySoft else Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Icon(icon, tab.label, tint = if (selected) c.primaryDeep else c.muted, modifier = Modifier.size(22.dp))
                        }
                        if (tab == Tab.Chats && state.unreadTotal > 0) {
                            Box(Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(c.danger), contentAlignment = Alignment.Center) {
                                Text(if (state.unreadTotal > 9) "9+" else state.unreadTotal.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tab.label, fontSize = 11.sp, maxLines = 1,
                        color = if (selected) c.primaryDeep else c.muted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- sheets

/** A panel that comes up from the bottom, over a dark layer. Tapping the dark layer closes it. */
@Composable
private fun SheetHost(state: UiState, sheet: Sheet, app: AppController) {
    val c = Mesh.colors
    val quiet = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(quiet, null) { app.closeSheet() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(c.surface).clickable(remember { MutableInteractionSource() }, null) { }
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline))
            Spacer(Modifier.height(14.dp))
            when (sheet) {
                Sheet.NewChat -> NewChatSheet(state, app)
                is Sheet.ContactEditor -> ContactEditorSheet(state, sheet, app)
                is Sheet.ShowQr -> ShowQrSheet(sheet, app)
                is Sheet.EditSetting -> EditSettingSheet(state, sheet.key, app)
                is Sheet.Confirm -> ConfirmSheet(sheet, app)
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, color = Mesh.colors.ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun NewChatSheet(state: UiState, app: AppController) {
    val c = Mesh.colors
    SheetTitle("New message")
    val people = state.contacts.map { it.id } + state.heardList.map { it.id }
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        for (id in listOf(BROADCAST_ID) + people) {
            val node = state.nodes[id]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { app.openChat(id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(state.nameOf(id), id, 40.dp, if (id == BROADCAST_ID) null else node?.online == true)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(state.nameOf(id), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            id == BROADCAST_ID -> "Every node on the mesh gets the message"
                            node?.online == true -> "Node $id · online"
                            else -> "Node $id · offline"
                        },
                        color = c.muted, fontSize = 12.sp,
                    )
                }
            }
        }
        if (people.isEmpty()) {
            Text("Nodes and contacts appear here once your node hears them.", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun ContactEditorSheet(state: UiState, sheet: Sheet.ContactEditor, app: AppController) {
    val existing = sheet.id?.let { state.contact(it) }
    var idText by remember { mutableStateOf(sheet.id?.toString() ?: "") }
    var name by remember { mutableStateOf(sheet.name) }
    var problem by remember { mutableStateOf("") }
    SheetTitle(if (existing != null) "Edit contact" else "Add contact")
    if (sheet.id == null) {
        Field(idText, { idText = it.filter(Char::isDigit).take(5); problem = "" }, "Node number", number = true, placeholder = "For example 2", error = problem)
        Spacer(Modifier.height(12.dp))
    }
    Field(name, { name = it.take(20) }, "Name", placeholder = "Name of the person")
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (existing != null) {
            SoftButton("Delete", { app.confirm(ConfirmAction.RemoveContact(existing.id)) }, icon = MeshIcons.Trash, danger = true)
        }
        PrimaryButton(
            "Save",
            {
                val found = app.contactIdProblem(idText, sheet.id)
                if (found != null && existing == null) {
                    problem = found
                } else {
                    app.saveContact(idText.toInt(), name, existing?.source ?: if (sheet.id == null) ContactSource.Manual else ContactSource.Heard)
                }
            },
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun ShowQrSheet(sheet: Sheet.ShowQr, app: AppController) {
    val c = Mesh.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SheetTitle(sheet.title)
        QrImage(sheet.payload, Modifier.widthIn(max = 260.dp))
        Spacer(Modifier.height(14.dp))
        Text(sheet.caption, color = c.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Share as text", { app.shareText(sheet.payload) }, icon = MeshIcons.Share)
            PrimaryButton("Done", { app.closeSheet() })
        }
    }
}

private class SettingForm(val title: String, val label: String, val hint: String, val number: Boolean = false, val password: Boolean = false)

private fun settingForm(key: String): SettingForm = when (key) {
    "name" -> SettingForm("Your name", "Name", "Other people see this name on the mesh.")
    ConfigKeys.ID -> SettingForm("Node number", "Number from 1 to 65534", "Every node of a mesh needs its own number.", number = true)
    ConfigKeys.WIFI_SSID -> SettingForm("Wi-Fi name", "SSID", "The name of the Wi-Fi network your node creates.")
    ConfigKeys.WIFI_PASS -> SettingForm("Wi-Fi password", "Password", "At least 8 characters. Leave it empty for an open network.", password = true)
    ConfigKeys.NET_KEY -> SettingForm("Network key", "Key", "All nodes of one mesh must use the same key, 8 to 32 characters.", password = true)
    ConfigKeys.POWER -> SettingForm("Transmit power", "dBm, from 2 to 20", "More power reaches further and uses more battery.", number = true)
    else -> SettingForm(key, "Value", "")
}

@Composable
private fun EditSettingSheet(state: UiState, key: String, app: AppController) {
    val c = Mesh.colors
    val form = settingForm(key)
    var value by remember(key) { mutableStateOf(if (key == "name") state.profile.name else state.config[key] ?: "") }
    val busy = state.configBusy == key
    val save = {
        if (key == "name") {
            app.setName(value)
            app.closeSheet()
        } else {
            app.setConfig(key, value.trim())
        }
    }
    SheetTitle(form.title)
    Field(
        value, { value = if (form.number) it.filter(Char::isDigit).take(5) else it.take(63) }, form.label,
        number = form.number, password = form.password, error = state.configError, onDone = save,
    )
    if (form.hint.isNotEmpty()) Text(form.hint, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(16.dp))
    PrimaryButton(if (busy) "Saving" else "Save", save, Modifier.fillMaxWidth(), enabled = !busy && (key != "name" || value.isNotBlank()))
}

@Composable
private fun ConfirmSheet(sheet: Sheet.Confirm, app: AppController) {
    val c = Mesh.colors
    SheetTitle(sheet.title)
    Text(sheet.text, color = c.muted, fontSize = 14.sp)
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SoftButton("Cancel", { app.closeSheet() }, Modifier.weight(1f))
        PrimaryButton(sheet.button, { app.confirm(sheet.action) }, Modifier.weight(1f), danger = sheet.action !is ConfirmAction.ApplyNetworkKey)
    }
}
