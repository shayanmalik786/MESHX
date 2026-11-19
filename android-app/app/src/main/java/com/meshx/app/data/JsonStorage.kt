package com.meshx.app.data

import android.content.Context
import com.meshx.app.model.ChatMessage
import com.meshx.app.model.Contact
import com.meshx.app.model.ContactSource
import com.meshx.app.model.LinkSpec
import com.meshx.app.model.MsgStatus
import com.meshx.app.model.NodeInfo
import com.meshx.app.model.Profile
import com.meshx.app.model.SavedData
import com.meshx.app.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Keeps the profile, contacts, chats and known nodes in one small JSON file inside the app's private folder. */
class JsonStorage(context: Context) {
    private val file = File(context.filesDir, "meshx.json")
    private val writer = Executors.newSingleThreadExecutor()

    fun load(): SavedData? = try {
        if (file.exists()) decode(JSONObject(file.readText())) else null
    } catch (e: Exception) {
        null // a broken file must never stop the app from starting
    }

    fun save(data: SavedData) {
        val text = encode(data).toString()
        writer.execute {
            try {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(text)
                if (!temp.renameTo(file)) file.writeText(text)
            } catch (e: Exception) {
                // storage full or similar: the next save tries again
            }
        }
    }

    private fun encode(data: SavedData): JSONObject {
        val p = data.profile
        val root = JSONObject()
        root.put("version", 2)
        root.put("nextMsgId", data.nextMsgId)
        root.put(
            "profile",
            JSONObject().put("name", p.name).put("onboarded", p.onboarded).put("theme", p.theme.name)
                .put("shareLocation", p.shareLocation).put("phoneNodeId", p.phoneNodeId)
                .put("wifiHost", p.wifiHost).put("wifiPort", p.wifiPort),
        )
        val contacts = JSONArray()
        for (c in data.contacts) {
            contacts.put(JSONObject().put("id", c.id).put("name", c.name).put("source", c.source.name).put("addedAt", c.addedAt))
        }
        root.put("contacts", contacts)
        val messages = JSONArray()
        for (m in data.messages) {
            messages.put(
                JSONObject().put("key", m.key).put("msgId", m.msgId).put("peer", m.peer).put("from", m.from)
                    .put("outgoing", m.outgoing).put("text", m.text).put("time", m.time).put("status", m.status.name)
                    .put("hops", m.hops).put("rttMs", m.rttMs).put("rssi", m.rssi).put("snr", m.snr.toDouble())
                    .put("failReason", m.failReason),
            )
        }
        root.put("messages", messages)
        val nodes = JSONArray()
        for (n in data.nodes) {
            val node = JSONObject().put("id", n.id).put("name", n.name).put("lastSeenAt", n.lastSeenAt)
                .put("battery", n.battery).put("fw", n.fw).put("posAt", n.posAt)
            if (n.lat != null && n.lon != null) node.put("lat", n.lat).put("lon", n.lon)
            nodes.put(node)
        }
        root.put("nodes", nodes)
        val link = JSONObject()
        when (val spec = data.lastLink) {
            is LinkSpec.Ble -> link.put("kind", "ble").put("address", spec.address).put("name", spec.name)
            is LinkSpec.Wifi -> link.put("kind", "wifi").put("host", spec.host).put("port", spec.port)
            is LinkSpec.Phone -> link.put("kind", "phone").put("nodeId", spec.nodeId).put("star", spec.star)
            LinkSpec.Demo -> link.put("kind", "demo")
            null -> link.put("kind", "none")
        }
        root.put("lastLink", link)
        return root
    }

    private fun decode(root: JSONObject): SavedData {
        val p = root.optJSONObject("profile") ?: JSONObject()
        val profile = Profile(
            name = p.optString("name"),
            onboarded = p.optBoolean("onboarded"),
            theme = ThemeMode.entries.firstOrNull { it.name == p.optString("theme") } ?: ThemeMode.System,
            shareLocation = p.optBoolean("shareLocation"),
            phoneNodeId = p.optInt("phoneNodeId"),
            wifiHost = p.optString("wifiHost", "192.168.4.1"),
            wifiPort = p.optInt("wifiPort", 4403),
        )
        val contacts = ArrayList<Contact>()
        val contactList = root.optJSONArray("contacts") ?: JSONArray()
        for (i in 0 until contactList.length()) {
            val c = contactList.getJSONObject(i)
            contacts.add(
                Contact(
                    id = c.getInt("id"), name = c.optString("name"),
                    source = ContactSource.entries.firstOrNull { it.name == c.optString("source") } ?: ContactSource.Manual,
                    addedAt = c.optLong("addedAt"),
                ),
            )
        }
        val messages = ArrayList<ChatMessage>()
        val messageList = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messageList.length()) {
            val m = messageList.getJSONObject(i)
            messages.add(
                ChatMessage(
                    key = m.getLong("key"), msgId = m.optInt("msgId"), peer = m.getInt("peer"), from = m.optInt("from"),
                    outgoing = m.optBoolean("outgoing"), text = m.optString("text"), time = m.optLong("time"),
                    status = MsgStatus.entries.firstOrNull { it.name == m.optString("status") } ?: MsgStatus.Failed,
                    hops = m.optInt("hops"), rttMs = m.optInt("rttMs"), rssi = m.optInt("rssi"),
                    snr = m.optDouble("snr", 0.0).toFloat(), failReason = m.optString("failReason"),
                ),
            )
        }
        val nodes = ArrayList<NodeInfo>()
        val nodeList = root.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nodeList.length()) {
            val n = nodeList.getJSONObject(i)
            nodes.add(
                NodeInfo(
                    id = n.getInt("id"), name = n.optString("name"), lastSeenAt = n.optLong("lastSeenAt"),
                    battery = n.optInt("battery", -1), fw = n.optString("fw"),
                    lat = if (n.has("lat")) n.getDouble("lat") else null,
                    lon = if (n.has("lon")) n.getDouble("lon") else null,
                    posAt = n.optLong("posAt"),
                ),
            )
        }
        val l = root.optJSONObject("lastLink") ?: JSONObject()
        val lastLink = when (l.optString("kind")) {
            "ble" -> LinkSpec.Ble(l.optString("address"), l.optString("name"))
            "wifi" -> LinkSpec.Wifi(l.optString("host"), l.optInt("port", 4403))
            "phone" -> LinkSpec.Phone(l.optInt("nodeId", 1), l.optBoolean("star"))
            "demo" -> LinkSpec.Demo
            else -> null
        }
        return SavedData(profile, contacts, messages, nodes, root.optInt("nextMsgId", 1), lastLink)
    }
}
