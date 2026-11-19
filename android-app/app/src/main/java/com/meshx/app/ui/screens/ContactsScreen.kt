package com.meshx.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshx.app.core.AppController
import com.meshx.app.core.Sheet
import com.meshx.app.core.UiState
import com.meshx.app.model.ContactSource
import com.meshx.app.ui.components.Avatar
import com.meshx.app.ui.components.Fab
import com.meshx.app.ui.components.IconAction
import com.meshx.app.ui.components.SectionTitle
import com.meshx.app.ui.components.SignalBars
import com.meshx.app.ui.components.SoftButton
import com.meshx.app.ui.components.TopBar
import com.meshx.app.ui.components.agoLabel
import com.meshx.app.ui.components.hopsLabel
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshIcons

@Composable
fun ContactsScreen(state: UiState, app: AppController) {
    val c = Mesh.colors
    val heard = state.heardList
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Contacts")
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
                item {
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Scan a code", { app.scanQr() }, Modifier.weight(1f), icon = MeshIcons.Scan)
                        SoftButton("My code", { app.showMyQr() }, Modifier.weight(1f), icon = MeshIcons.QrCode)
                    }
                }
                if (state.contacts.isEmpty()) {
                    item {
                        Text(
                            "No contacts yet. Add someone from the list below, scan their code, or type their node number.",
                            color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(state.contacts, key = { "c${it.id}" }) { contact ->
                    val node = state.nodes[contact.id]
                    Row(
                        Modifier.fillMaxWidth().clickable { app.openChat(contact.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(contact.name, contact.id, 46.dp, node?.online == true)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    node == null -> "Node ${contact.id} · not heard yet"
                                    node.online -> "Node ${contact.id} · online · ${hopsLabel(node.hops)}"
                                    else -> "Node ${contact.id} · last seen ${agoLabel(node.lastSeenAt, state.now)}"
                                },
                                color = c.muted, fontSize = 13.sp,
                            )
                        }
                        if (node?.online == true) SignalBars(node.score)
                        IconAction(MeshIcons.Pencil, "Edit", { app.showSheet(Sheet.ContactEditor(contact.id, contact.name)) }, c.muted)
                    }
                }
                item {
                    SectionTitle("Heard on the mesh", Modifier.padding(top = 12.dp, bottom = 4.dp))
                    if (heard.isEmpty()) {
                        Text(
                            if (state.connected) "Every node your node hears is already in your contacts." else "Connect to a node to see who is on the mesh.",
                            color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(heard, key = { "h${it.id}" }) { node ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(state.nameOf(node.id), node.id, 46.dp, node.online)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.nameOf(node.id), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (node.online) "Node ${node.id} · ${hopsLabel(node.hops)} away" else "Node ${node.id} · last seen ${agoLabel(node.lastSeenAt, state.now)}",
                                color = c.muted, fontSize = 13.sp,
                            )
                        }
                        SoftButton("Add", { app.saveContact(node.id, node.name, ContactSource.Heard) }, icon = MeshIcons.Plus)
                    }
                }
            }
        }
        Fab("Add contact", MeshIcons.UserPlus, { app.showSheet(Sheet.ContactEditor(null, "")) }, Modifier.align(Alignment.BottomEnd))
    }
}
