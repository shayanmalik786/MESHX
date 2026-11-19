package com.meshx.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshx.app.core.AppController
import com.meshx.app.core.ConfirmAction
import com.meshx.app.core.Screen
import com.meshx.app.core.Sheet
import com.meshx.app.core.UiState
import com.meshx.app.model.BROADCAST_ID
import com.meshx.app.model.ChatMessage
import com.meshx.app.model.MsgStatus
import com.meshx.app.protocol.Protocol
import com.meshx.app.ui.components.Avatar
import com.meshx.app.ui.components.EmptyState
import com.meshx.app.ui.components.Fab
import com.meshx.app.ui.components.IconAction
import com.meshx.app.ui.components.LinkPill
import com.meshx.app.ui.components.TopBar
import com.meshx.app.ui.components.clockLabel
import com.meshx.app.ui.components.hopsLabel
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshIcons
import java.util.Locale

@Composable
fun ChatsScreen(state: UiState, app: AppController) {
    val c = Mesh.colors
    val conversations = state.conversations
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Chats") { LinkPill(state) { app.open(Screen.Connect) } }
            if (conversations.isEmpty()) {
                EmptyState(MeshIcons.Chats, "No messages yet", "Write to one person, or to everyone on the mesh at once.")
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(conversations, key = { it.peer }) { conversation ->
                    val peer = conversation.peer
                    val last = conversation.last
                    Row(
                        Modifier.fillMaxWidth().clickable { app.openChat(peer) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(state.nameOf(peer), peer, 48.dp, if (peer == BROADCAST_ID) null else state.nodes[peer]?.online == true)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    state.nameOf(peer), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Text(clockLabel(last.time), color = c.muted, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (last.outgoing) {
                                    StatusIcon(last.status, c.muted)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    last.text, color = c.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (conversation.unread > 0) {
                                    Box(Modifier.size(20.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                                        Text(
                                            if (conversation.unread > 9) "9+" else conversation.unread.toString(),
                                            color = c.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Fab("New message", MeshIcons.Plus, { app.showSheet(Sheet.NewChat) }, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun StatusIcon(status: MsgStatus, tint: Color) {
    val c = Mesh.colors
    when (status) {
        MsgStatus.Sending -> Icon(MeshIcons.Clock, "Sending", tint = tint, modifier = Modifier.size(14.dp))
        MsgStatus.Sent -> Icon(MeshIcons.Check, "Sent", tint = tint, modifier = Modifier.size(14.dp))
        MsgStatus.Delivered -> Icon(MeshIcons.CheckCheck, "Delivered", tint = tint, modifier = Modifier.size(14.dp))
        MsgStatus.Failed -> Icon(MeshIcons.Alert, "Not delivered", tint = c.danger, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun ChatScreen(state: UiState, peer: Int, app: AppController) {
    val c = Mesh.colors
    val messages = state.chat(peer)
    val node = state.nodes[peer]
    var draft by remember(peer) { mutableStateOf("") }
    val subtitle = when {
        peer == BROADCAST_ID -> "Goes to every node, without delivery report"
        node == null -> "Node $peer · not heard yet"
        node.online -> "Node $peer · online · ${hopsLabel(node.hops)}"
        else -> "Node $peer · offline"
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(state.nameOf(peer), subtitle, onBack = { app.back() }) {
            if (peer != BROADCAST_ID && state.contact(peer) == null) {
                IconAction(MeshIcons.UserPlus, "Add contact", { app.showSheet(Sheet.ContactEditor(peer, node?.name ?: "")) })
            }
            if (peer != BROADCAST_ID) IconAction(MeshIcons.Info, "Node details", { app.open(Screen.NodeDetail(peer)) })
            if (messages.isNotEmpty()) {
                IconAction(MeshIcons.Trash, "Delete chat", {
                    app.showSheet(Sheet.Confirm("Delete this chat?", "All messages with ${state.nameOf(peer)} are removed from this phone.", "Delete", ConfirmAction.DeleteChat(peer)))
                })
            }
        }
        // newest message at the bottom, and the list stays there when new ones arrive
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages.asReversed(), key = { it.key }) { message -> Bubble(state, message, peer == BROADCAST_ID, app) }
        }
        if (messages.isEmpty()) {
            Text(
                "Messages are short, up to ${Protocol.MAX_TEXT_BYTES} bytes, because the radio link is slow.",
                color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        if (!state.connected) {
            Text(
                "Not connected to a node. Tap to connect.", color = c.danger, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { app.open(Screen.Connect) }.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            val bytes = Protocol.utf8Length(draft)
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(c.surfaceAlt).border(1.dp, c.outline, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (draft.isEmpty()) Text("Message", color = c.muted, fontSize = 15.sp)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = Protocol.truncateUtf8(it, Protocol.MAX_TEXT_BYTES) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.primary),
                    maxLines = 4,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bytes > 110) Text("$bytes/${Protocol.MAX_TEXT_BYTES}", color = c.muted, fontSize = 10.sp)
                val ready = draft.isNotBlank()
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(if (ready) c.primary else c.outline)
                        .clickable(enabled = ready) {
                            app.sendMessage(peer, draft)
                            draft = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MeshIcons.Send, "Send", tint = if (ready) c.onPrimary else c.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Bubble(state: UiState, message: ChatMessage, group: Boolean, app: AppController) {
    val c = Mesh.colors
    val mine = message.outgoing
    val failed = message.status == MsgStatus.Failed
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        val shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp)
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape).background(if (mine) c.primary else c.bubbleIn)
                .clickable(enabled = failed) { app.retryMessage(message.key) }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (group && !mine) {
                Text(state.nameOf(message.from), color = c.primaryDeep, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(message.text, color = if (mine) c.onPrimary else c.ink, fontSize = 15.sp)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = if (mine) c.onPrimary.copy(alpha = 0.8f) else c.muted
                val details = buildString {
                    append(clockLabel(message.time))
                    if (message.hops > 0) append(" · ").append(hopsLabel(message.hops))
                    if (mine && message.rttMs > 0) append(" · ").append(String.format(Locale.US, "%.1f s", message.rttMs / 1000f))
                }
                Text(details, color = tint, fontSize = 11.sp)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    StatusIcon(message.status, tint)
                }
            }
        }
        if (failed) {
            Text(
                "${message.failReason.ifEmpty { "Not delivered" }} Tap the message to try again.",
                color = c.danger, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, end = 4.dp).widthIn(max = 300.dp),
            )
        }
    }
}
