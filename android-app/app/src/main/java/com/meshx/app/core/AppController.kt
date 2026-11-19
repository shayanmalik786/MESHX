package com.meshx.app.core

import com.meshx.app.mesh.DemoControls
import com.meshx.app.mesh.LinkListener
import com.meshx.app.mesh.NodeLink
import com.meshx.app.mesh.SimLink
import com.meshx.app.mesh.TimerHandle
import com.meshx.app.model.BROADCAST_ID
import com.meshx.app.model.BleDevice
import com.meshx.app.model.ChatMessage
import com.meshx.app.model.Contact
import com.meshx.app.model.ContactSource
import com.meshx.app.model.LinkSpec
import com.meshx.app.model.LinkState
import com.meshx.app.model.MAX_NODE_ID
import com.meshx.app.model.MeshLink
import com.meshx.app.model.MsgStatus
import com.meshx.app.model.NodeInfo
import com.meshx.app.model.PacketLog
import com.meshx.app.model.PingResult
import com.meshx.app.model.RoutingMode
import com.meshx.app.model.SavedData
import com.meshx.app.model.ThemeMode
import com.meshx.app.protocol.ConfigKeys
import com.meshx.app.protocol.NodeFrame
import com.meshx.app.protocol.Protocol
import com.meshx.app.protocol.QrCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.zip.CRC32

/**
 * The brain of the app. It owns the state the screens draw, turns what the user does into
 * protocol lines for the node, and turns the node's lines back into state.
 *
 * It has no Android code in it, so the unit tests can run the complete app logic against the
 * demo mesh. Every function must be called on the main thread.
 */
class AppController(private val device: Device) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** The visible activity, when there is one. */
    var host: Host? = null

    private val timers = device.timers
    private val s: UiState get() = _state.value

    private var link: NodeLink? = null
    private var linkGeneration = 0
    private var lastLink: LinkSpec? = null
    private var helloTimer: TimerHandle? = null
    private var retryTimer: TimerHandle? = null
    private var retries = 0
    private var retrying = false
    private var scanTimer: TimerHandle? = null
    private var saveTimer: TimerHandle? = null
    private var noticeTimer: TimerHandle? = null
    private var configTimer: TimerHandle? = null
    private var ticks = 0

    private var nextMsgId = 1
    private var nextKey = 0L
    private var nextPingId = 1
    private var packetSeq = 0L
    private val sentAt = HashMap<Long, Long>() // message key -> time it was handed to the node

    private var pendingNeed: Need? = null
    private var afterPermission: (() -> Unit)? = null

    private var firmware: ByteArray? = null
    private var otaChunk = 0
    private var otaSeq = 0
    private var otaOffset = 0
    private var otaTimer: TimerHandle? = null
    private var lastFixSentAt = 0L

    private inline fun update(change: (UiState) -> UiState) {
        _state.value = change(_state.value)
    }

    // ------------------------------------------------------------------ start and save

    /** Loads the saved data and reconnects to the node that was used last time. */
    fun start() {
        if (s.loaded) return
        val saved = device.load() ?: SavedData()
        nextMsgId = saved.nextMsgId.coerceIn(1, MAX_MSG_ID)
        nextKey = maxOf(device.now(), saved.messages.maxOfOrNull { it.key } ?: 0L)
        lastLink = saved.lastLink
        update { st ->
            st.copy(
                loaded = true,
                now = device.now(),
                profile = saved.profile,
                contacts = saved.contacts,
                // a message that was still on its way when the app closed never got an answer
                messages = saved.messages.map {
                    if (it.status == MsgStatus.Sending || (it.status == MsgStatus.Sent && it.peer != BROADCAST_ID)) {
                        it.copy(status = MsgStatus.Failed, failReason = "The app was closed")
                    } else {
                        it
                    }
                },
                nodes = saved.nodes.associate { it.id to it.copy(online = false, hops = 0, score = 0, rssi = 0) },
                stack = listOf(if (saved.profile.onboarded) Screen.Main else Screen.Welcome),
                link = LinkStatus(spec = saved.lastLink),
            )
        }
        tickLoop()
        val last = saved.lastLink
        if (saved.profile.onboarded && last != null && canConnectSilently(last)) connect(last)
    }

    private fun canConnectSilently(spec: LinkSpec): Boolean = when (spec) {
        is LinkSpec.Demo, is LinkSpec.Wifi -> true
        is LinkSpec.Ble -> device.problem(Need.BleConnect) == null
        is LinkSpec.Phone -> device.problem(Need.PhoneMesh) == null
    }

    private fun scheduleSave() {
        saveTimer?.cancel()
        saveTimer = timers.after(700) { saveNow() }
    }

    /** Writes everything to storage now. The activity calls this when the app goes to the background. */
    fun saveNow() {
        saveTimer?.cancel()
        saveTimer = null
        val st = s
        if (!st.loaded) return
        device.save(
            SavedData(
                profile = st.profile,
                contacts = st.contacts,
                messages = st.messages.takeLast(MAX_MESSAGES),
                nodes = st.nodes.values.filter { it.id != st.myId },
                nextMsgId = nextMsgId,
                lastLink = lastLink,
            ),
        )
    }

    /** Called when the app process is going away: keep the data, drop the connection. */
    fun shutdown() {
        saveNow()
        closeLink()
    }

    private fun tickLoop() {
        timers.after(1000) {
            ticks++
            val wall = device.now()
            update { st ->
                var out = st.copy(now = wall, stats = st.stats.copy(perSecond = st.stats.perSecond.drop(1) + 0))
                if (ticks % 3 == 0 && st.connected) {
                    val history = HashMap(st.history)
                    for (node in st.nodes.values) {
                        if (node.id == st.myId) continue
                        history[node.id] = ((history[node.id] ?: emptyList()) + (if (node.online) node.score else 0)).takeLast(HISTORY_POINTS)
                    }
                    out = out.copy(history = history)
                }
                out
            }
            expireMessages()
            tickLoop()
        }
    }

    // ------------------------------------------------------------------ navigation

    fun open(screen: Screen) {
        update { it.copy(stack = it.stack + screen, sheet = null) }
        if (screen is Screen.Chat) markRead(screen.peer)
        if (screen == Screen.NodeSettings && s.connected) send(Protocol.get())
    }

    /** Handles the back button. Returns false when the app should close. */
    fun back(): Boolean {
        val st = s
        return when {
            st.sheet != null -> {
                closeSheet()
                true
            }
            st.stack.size > 1 -> {
                if (st.screen == Screen.Connect) stopScan()
                update { it.copy(stack = it.stack.dropLast(1), blocker = null) }
                true
            }
            st.screen == Screen.Main && st.tab != Tab.Chats -> {
                update { it.copy(tab = Tab.Chats) }
                true
            }
            else -> false
        }
    }

    fun selectTab(tab: Tab) = update { it.copy(tab = tab) }
    fun setNetworkView(view: NetworkView) = update { it.copy(networkView = view) }
    fun showSheet(sheet: Sheet) = update { it.copy(sheet = sheet, configError = "") }
    fun closeSheet() = update { it.copy(sheet = null, configError = "") }

    fun notice(text: String) {
        update { it.copy(notice = text, noticeId = it.noticeId + 1) }
        noticeTimer?.cancel()
        noticeTimer = timers.after(2800) { update { it.copy(notice = "") } }
    }

    fun openPanel(panel: SystemPanel) {
        host?.openPanel(panel)
    }

    // ------------------------------------------------------------------ profile

    fun finishWelcome(name: String) {
        val clean = Protocol.cleanName(name)
        if (clean.isEmpty()) return
        update { st ->
            st.copy(
                profile = st.profile.copy(
                    name = clean,
                    onboarded = true,
                    phoneNodeId = if (st.profile.phoneNodeId == 0) 100 + (device.now() % 900).toInt() else st.profile.phoneNodeId,
                ),
                stack = listOf(Screen.Main, Screen.Connect),
            )
        }
        scheduleSave()
    }

    fun setName(name: String) {
        val clean = Protocol.cleanName(name)
        if (clean.isEmpty() || clean == s.profile.name) return
        update { it.copy(profile = it.profile.copy(name = clean)) }
        // HELLO also carries the name, so the node announces the new one to the mesh
        if (s.connected) send(Protocol.hello(clean))
        scheduleSave()
    }

    fun setTheme(theme: ThemeMode) {
        update { it.copy(profile = it.profile.copy(theme = theme)) }
        scheduleSave()
    }

    fun setShareLocation(on: Boolean) {
        if (!on) {
            update { it.copy(profile = it.profile.copy(shareLocation = false)) }
            device.stopLocation()
            scheduleSave()
            return
        }
        ensure(Need.Location) {
            update { it.copy(profile = it.profile.copy(shareLocation = true)) }
            scheduleSave()
            applyLocationSharing()
        }
    }

    private fun applyLocationSharing() {
        val st = s
        if (st.profile.shareLocation && st.connected && !st.isDemo) {
            lastFixSentAt = 0
            val started = device.startLocation { lat, lon ->
                val now = timers.now()
                if (s.connected && !s.isDemo && (lastFixSentAt == 0L || now - lastFixSentAt >= 15_000)) {
                    lastFixSentAt = now
                    send(Protocol.pos(lat, lon))
                }
            }
            if (!started) notice("Location is not available on this phone")
        } else {
            device.stopLocation()
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun ensure(need: Need, action: () -> Unit) {
        val h = host
        if (h == null) {
            // no screen to ask on: go ahead only when nothing is in the way
            if (device.problem(need) == null) action()
            return
        }
        pendingNeed = need
        afterPermission = action
        h.requestPermission(need)
    }

    /** The activity reports the answer to a permission request. */
    fun onPermission(need: Need, granted: Boolean) {
        if (need != pendingNeed) return
        val action = afterPermission
        pendingNeed = null
        afterPermission = null
        if (!granted) {
            val what = when (need) {
                Need.BleScan, Need.BleConnect -> "Bluetooth (Nearby devices)"
                Need.PhoneMesh -> "Nearby devices and Location"
                Need.Location -> "Location"
            }
            update { it.copy(blocker = Blocker("MeshX needs the $what permission for this. You can allow it in the app settings.", SystemPanel.AppDetails)) }
            return
        }
        val problem = device.problem(need)
        if (problem != null) {
            update { it.copy(blocker = problem) }
            return
        }
        update { it.copy(blocker = null) }
        action?.invoke()
    }

    // ------------------------------------------------------------------ connecting

    fun startScan() {
        ensure(Need.BleScan) {
            update { it.copy(scan = ScanStatus(scanning = true)) }
            device.startBleScan(
                onFound = { found ->
                    update { st ->
                        val others = st.scan.devices.filter { it.address != found.address }
                        st.copy(scan = st.scan.copy(devices = (others + found).sortedByDescending { it.rssi }))
                    }
                },
                onError = { reason ->
                    scanTimer?.cancel()
                    update { it.copy(scan = it.scan.copy(scanning = false, error = reason)) }
                },
            )
            scanTimer?.cancel()
            scanTimer = timers.after(SCAN_MS) { stopScan() }
        }
    }

    fun stopScan() {
        scanTimer?.cancel()
        scanTimer = null
        if (s.scan.scanning) {
            device.stopBleScan()
            update { it.copy(scan = it.scan.copy(scanning = false)) }
        }
    }

    fun connectBle(found: BleDevice) {
        stopScan()
        ensure(Need.BleConnect) { connect(LinkSpec.Ble(found.address, found.name)) }
    }

    /** Returns a problem with the input, or null when the connection was started. */
    fun connectWifi(host: String, portText: String): String? {
        val cleanHost = host.trim()
        val port = portText.trim().toIntOrNull()
        if (cleanHost.isEmpty() || cleanHost.contains(' ')) return "Enter the address of the node"
        if (port == null || port !in 1..65535) return "The port is a number from 1 to 65535"
        update { it.copy(profile = it.profile.copy(wifiHost = cleanHost, wifiPort = port)) }
        scheduleSave()
        connect(LinkSpec.Wifi(cleanHost, port))
        return null
    }

    /** Returns a problem with the input, or null when the connection was started. */
    fun connectPhone(idText: String, star: Boolean): String? {
        val id = idText.trim().toIntOrNull()
        if (id == null || id !in 1..MAX_NODE_ID) return "Choose a number from 1 to $MAX_NODE_ID"
        update { it.copy(profile = it.profile.copy(phoneNodeId = id)) }
        scheduleSave()
        ensure(Need.PhoneMesh) { connect(LinkSpec.Phone(id, star)) }
        return null
    }

    fun connectDemo() = connect(LinkSpec.Demo)

    /** Connects again to the node that was used last. */
    fun reconnect() {
        when (val spec = s.link.spec) {
            null -> open(Screen.Connect)
            is LinkSpec.Ble -> ensure(Need.BleConnect) { connect(spec) }
            is LinkSpec.Phone -> ensure(Need.PhoneMesh) { connect(spec) }
            else -> connect(spec)
        }
    }

    fun disconnect() {
        val spec = s.link.spec
        closeLink()
        lastLink = null
        failUnfinished("Disconnected")
        update { st ->
            st.copy(link = LinkStatus(LinkState.Idle, spec), nodes = st.nodes.mapValues { it.value.copy(online = false, hops = 0) })
        }
        scheduleSave()
    }

    private fun closeLink() {
        linkGeneration++
        helloTimer?.cancel()
        retryTimer?.cancel()
        retrying = false
        link?.close()
        link = null
        device.stopLocation()
        if (s.ota.busy) otaFailed("Connection closed")
    }

    private fun connect(spec: LinkSpec, isRetry: Boolean = false) {
        closeLink()
        if (!isRetry) retries = 0
        retrying = isRetry
        val generation = linkGeneration
        update { st ->
            st.copy(
                link = LinkStatus(LinkState.Connecting, spec),
                blocker = null,
                links = emptyList(),
                routes = emptyMap(),
                history = emptyMap(),
                pings = emptyMap(),
                demoOff = emptySet(),
                traffic = emptyList(),
                stats = TrafficStats(),
                config = emptyMap(),
                configBusy = "",
                configError = "",
                nodes = st.nodes.mapValues { it.value.copy(online = false, hops = 0, score = 0, rssi = 0) },
            )
        }
        val created = if (spec is LinkSpec.Demo) {
            SimLink(timers, s.profile.name, greet = s.messages.isEmpty())
        } else {
            device.openLink(spec, s.profile.name)
        }
        link = created
        created.open(object : LinkListener {
            override fun onLinkUp() {
                if (generation != linkGeneration) return
                send(Protocol.hello(s.profile.name))
                helloTimer?.cancel()
                helloTimer = timers.after(HELLO_TIMEOUT_MS) {
                    if (generation == linkGeneration && !s.connected) {
                        linkDown("The node did not answer. Is it running the MeshX firmware?")
                    }
                }
            }

            override fun onLinkLine(line: String) {
                if (generation == linkGeneration) onLine(line)
            }

            override fun onLinkDown(reason: String) {
                if (generation == linkGeneration) linkDown(reason)
            }

            override fun onLinkNote(note: String) {
                if (generation == linkGeneration) update { it.copy(link = it.link.copy(note = note)) }
            }
        })
    }

    private fun linkDown(reason: String) {
        val spec = s.link.spec
        val wasConnected = s.connected
        linkGeneration++
        helloTimer?.cancel()
        link?.close()
        link = null
        device.stopLocation()
        failUnfinished("Connection lost")
        if (s.ota.busy) otaFailed("Connection lost")
        val offline = s.nodes.mapValues { it.value.copy(online = false, hops = 0) }
        val hardware = spec is LinkSpec.Ble || spec is LinkSpec.Wifi
        if (spec != null && hardware && (wasConnected || retrying) && retries < RETRY_DELAYS_MS.size) {
            // a hardware link that drops is usually back in a few seconds (node reboot, out of range)
            val delay = RETRY_DELAYS_MS[retries]
            retries++
            update { it.copy(link = LinkStatus(LinkState.Lost, spec, error = reason), nodes = offline) }
            retryTimer = timers.after(delay) { connect(spec, isRetry = true) }
        } else {
            retrying = false
            update { it.copy(link = LinkStatus(LinkState.Idle, spec, error = reason), nodes = offline) }
        }
    }

    private fun send(line: String) {
        link?.send(line)
    }

    // ------------------------------------------------------------------ lines from the node

    private fun onLine(line: String) {
        val frame = Protocol.parseNodeFrame(line) ?: return
        val now = device.now()
        when (frame) {
            is NodeFrame.Me -> update { st ->
                val nodes = HashMap(st.nodes)
                if (st.myId != 0 && st.myId != frame.id) nodes.remove(st.myId)
                val old = nodes[frame.id] ?: NodeInfo(frame.id)
                nodes[frame.id] = old.copy(
                    name = frame.name, online = true, lastSeenAt = now, battery = frame.battery, fw = frame.fw, hops = 0,
                )
                st.copy(myId = frame.id, myBattery = frame.battery, myFw = frame.fw, nodes = nodes)
            }

            NodeFrame.Ready -> onReady()

            is NodeFrame.Sent -> updateMessage(frame.msgId) {
                if (it.status == MsgStatus.Sending) it.copy(status = MsgStatus.Sent) else it
            }

            is NodeFrame.Ack -> updateMessage(frame.msgId) {
                it.copy(status = MsgStatus.Delivered, hops = frame.hops, rttMs = frame.rttMs, failReason = "")
            }

            is NodeFrame.Fail -> updateMessage(frame.msgId) {
                it.copy(status = MsgStatus.Failed, failReason = friendlyFailure(frame.reason))
            }

            is NodeFrame.Rx -> {
                val peer = if (frame.to == BROADCAST_ID) BROADCAST_ID else frame.from
                val message = ChatMessage(
                    key = ++nextKey, msgId = frame.msgId, peer = peer, from = frame.from, outgoing = false,
                    text = frame.text, time = now, status = MsgStatus.Delivered, hops = frame.hops,
                    rssi = frame.rssi, snr = frame.snr,
                )
                update { st ->
                    val reading = st.screen == Screen.Chat(peer) && st.sheet == null
                    st.copy(
                        messages = (st.messages + message).takeLast(MAX_MESSAGES),
                        unread = if (reading) st.unread else st.unread + (peer to (st.unread[peer] ?: 0) + 1),
                    )
                }
                scheduleSave()
            }

            is NodeFrame.Node -> update { st ->
                val old = st.nodes[frame.id] ?: NodeInfo(frame.id)
                val online = frame.lastSeenSec == 0L
                st.copy(
                    nodes = st.nodes + (
                        frame.id to old.copy(
                            name = frame.name.ifBlank { old.name },
                            online = online,
                            lastSeenAt = if (online) now else now - frame.lastSeenSec * 1000,
                            rssi = frame.rssi, snr = frame.snr,
                            battery = if (frame.battery >= 0) frame.battery else old.battery,
                            loss = frame.loss, score = frame.score, hops = frame.hops, nextHop = frame.nextHop,
                        )
                        ),
                )
            }

            is NodeFrame.Link -> update { st ->
                val others = st.links.filterNot { it.a == frame.a && it.b == frame.b }
                st.copy(links = if (frame.score > 0) others + MeshLink(frame.a, frame.b, frame.score) else others)
            }

            is NodeFrame.Route -> update { it.copy(routes = it.routes + (frame.dst to RouteInfo(frame.path, frame.flood))) }

            is NodeFrame.Pos -> update { st ->
                val old = st.nodes[frame.id] ?: NodeInfo(frame.id)
                st.copy(nodes = st.nodes + (frame.id to old.copy(lat = frame.lat, lon = frame.lon, posAt = now - frame.ageSec * 1000)))
            }

            is NodeFrame.Stat -> update { st ->
                val old = st.nodes[frame.id] ?: NodeInfo(frame.id)
                st.copy(
                    nodes = st.nodes + (
                        frame.id to old.copy(
                            uptimeSec = frame.uptimeSec, tx = frame.tx, rx = frame.rx, fwd = frame.fwd, drop = frame.drop,
                            phone = frame.phone, fw = frame.fw.ifBlank { old.fw },
                        )
                        ),
                )
            }

            is NodeFrame.Pkt -> update { st ->
                val log = PacketLog(++packetSeq, now, frame.kind, frame.src, frame.dst, frame.via, frame.hops, frame.rssi, frame.size)
                val perSecond = st.stats.perSecond.toMutableList()
                perSecond[perSecond.size - 1] = perSecond.last() + 1
                st.copy(
                    traffic = if (st.trafficPaused) st.traffic else (st.traffic + log).takeLast(MAX_PACKETS),
                    stats = TrafficStats(
                        packets = st.stats.packets + 1,
                        bytes = st.stats.bytes + frame.size,
                        byKind = st.stats.byKind + (frame.kind to (st.stats.byKind[frame.kind] ?: 0) + 1),
                        perSecond = perSecond,
                    ),
                )
            }

            is NodeFrame.Cfg -> update { st ->
                st.copy(
                    config = st.config + (frame.key to frame.value),
                    routingMode = if (frame.key == ConfigKeys.ROUTE) RoutingMode.fromKey(frame.value) ?: st.routingMode else st.routingMode,
                )
            }

            is NodeFrame.Ok -> if (frame.what == s.configBusy) {
                configTimer?.cancel()
                update { st ->
                    st.copy(configBusy = "", configError = "", sheet = if (st.sheet == Sheet.EditSetting(frame.what)) null else st.sheet)
                }
                notice("Saved on the node")
            }

            is NodeFrame.Err -> if (frame.what == s.configBusy) {
                configTimer?.cancel()
                update { it.copy(configBusy = "", configError = frame.reason) }
                if (s.sheet !is Sheet.EditSetting) notice(frame.reason)
            } else if (frame.what != "FRAME") {
                notice(frame.reason)
            }

            is NodeFrame.Pong -> update { st ->
                val waiting = st.pings.values.firstOrNull { it.id == frame.id } ?: return@update st
                st.copy(pings = st.pings + (waiting.dst to waiting.copy(pending = false, rttMs = frame.rttMs, hops = frame.hops)))
            }

            is NodeFrame.OtaReady -> if (s.ota.phase == OtaPhase.Sending) {
                otaChunk = frame.chunk.coerceIn(16, MAX_OTA_CHUNK)
                sendOtaChunk()
            }

            is NodeFrame.OtaAck -> onOtaAck(frame.seq)

            NodeFrame.OtaDone -> {
                otaTimer?.cancel()
                firmware = null
                update { it.copy(ota = it.ota.copy(phase = OtaPhase.Done, sent = it.ota.size, message = "Installed. The node is restarting.")) }
            }

            is NodeFrame.OtaErr -> otaFailed(frame.reason)
        }
    }

    private fun onReady() {
        helloTimer?.cancel()
        val first = !s.connected
        retries = 0
        retrying = false
        lastLink = s.link.spec
        update { st ->
            st.copy(
                link = st.link.copy(state = LinkState.Connected, error = ""),
                stack = if (st.screen == Screen.Connect && st.stack.size > 1) st.stack.dropLast(1) else st.stack,
            )
        }
        send(Protocol.get())
        if (first) {
            stopScan()
            scheduleSave()
            applyLocationSharing()
            notice("Connected as node ${s.myId}")
        }
    }

    private fun friendlyFailure(reason: String): String = when (reason) {
        "timeout" -> "No answer. The node may be off or out of range."
        "bad address" -> "This node number cannot be used"
        "empty" -> "The message was empty"
        else -> reason
    }

    // ------------------------------------------------------------------ messages

    fun openChat(peer: Int) = open(Screen.Chat(peer))

    private fun markRead(peer: Int) {
        if (s.unread.containsKey(peer)) update { it.copy(unread = it.unread - peer) }
    }

    fun sendMessage(peer: Int, text: String) {
        val clean = Protocol.cleanText(text)
        if (clean.isEmpty()) return
        val online = s.connected
        val message = ChatMessage(
            key = ++nextKey, msgId = takeMsgId(), peer = peer, from = s.myId, outgoing = true, text = clean,
            time = device.now(),
            status = if (online) MsgStatus.Sending else MsgStatus.Failed,
            failReason = if (online) "" else "Not connected to a node",
        )
        update { it.copy(messages = (it.messages + message).takeLast(MAX_MESSAGES)) }
        if (online) {
            sentAt[message.key] = timers.now()
            send(Protocol.msg(message.msgId, peer, clean))
        }
        scheduleSave()
    }

    /** Sends a failed message again. */
    fun retryMessage(key: Long) {
        val old = s.messages.firstOrNull { it.key == key && it.outgoing && it.status == MsgStatus.Failed } ?: return
        if (!s.connected) {
            notice("Not connected to a node")
            return
        }
        val msgId = takeMsgId()
        update { st ->
            st.copy(messages = st.messages.map { if (it.key == key) it.copy(msgId = msgId, status = MsgStatus.Sending, failReason = "", time = device.now()) else it })
        }
        sentAt[key] = timers.now()
        send(Protocol.msg(msgId, old.peer, old.text))
        scheduleSave()
    }

    private fun takeMsgId(): Int {
        val id = nextMsgId
        nextMsgId = if (id >= MAX_MSG_ID) 1 else id + 1
        return id
    }

    private fun updateMessage(msgId: Int, change: (ChatMessage) -> ChatMessage) {
        val target = s.messages.lastOrNull {
            it.outgoing && it.msgId == msgId && (it.status == MsgStatus.Sending || it.status == MsgStatus.Sent)
        } ?: return
        val changed = change(target)
        update { st -> st.copy(messages = st.messages.map { if (it.key == target.key) changed else it }) }
        if (changed.status == MsgStatus.Delivered || changed.status == MsgStatus.Failed) sentAt.remove(target.key)
        if (changed.status == MsgStatus.Sent && changed.peer == BROADCAST_ID) sentAt.remove(target.key)
        scheduleSave()
    }

    /** Safety net: a message never stays "sending" forever, even if the node goes silent. */
    private fun expireMessages() {
        if (sentAt.isEmpty()) return
        val now = timers.now()
        val late = sentAt.filter { now - it.value > MESSAGE_TIMEOUT_MS }.keys
        if (late.isEmpty()) return
        late.forEach { sentAt.remove(it) }
        update { st ->
            st.copy(messages = st.messages.map {
                if (it.key in late && it.status != MsgStatus.Delivered) it.copy(status = MsgStatus.Failed, failReason = "The node did not report back") else it
            })
        }
        scheduleSave()
    }

    private fun failUnfinished(reason: String) {
        sentAt.clear()
        update { st ->
            st.copy(messages = st.messages.map {
                if (it.outgoing && (it.status == MsgStatus.Sending || (it.status == MsgStatus.Sent && it.peer != BROADCAST_ID))) {
                    it.copy(status = MsgStatus.Failed, failReason = reason)
                } else {
                    it
                }
            })
        }
    }

    // ------------------------------------------------------------------ contacts

    /** Checks a node number typed by the user. Returns the problem, or null. */
    fun contactIdProblem(idText: String, editing: Int?): String? {
        val id = idText.trim().toIntOrNull()
        return when {
            id == null || id !in 1..MAX_NODE_ID -> "The node number is from 1 to $MAX_NODE_ID"
            id == s.myId -> "That is your own node"
            editing == null && s.contact(id) != null -> "${s.nameOf(id)} is already a contact"
            else -> null
        }
    }

    fun saveContact(id: Int, name: String, source: ContactSource) {
        if (id !in 1..MAX_NODE_ID || id == s.myId) return
        val clean = Protocol.cleanName(name).ifBlank { s.nodes[id]?.name?.takeIf { it.isNotBlank() } ?: "Node $id" }
        update { st ->
            val old = st.contact(id)
            val contact = old?.copy(name = clean) ?: Contact(id, clean, source, device.now())
            st.copy(contacts = (st.contacts.filter { it.id != id } + contact).sortedBy { it.name.lowercase() }, sheet = null)
        }
        notice("$clean saved")
        scheduleSave()
    }

    fun removeContact(id: Int) {
        update { st -> st.copy(contacts = st.contacts.filter { it.id != id }) }
        scheduleSave()
    }

    fun showMyQr() {
        val id = if (s.myId != 0) s.myId else if (s.link.spec is LinkSpec.Phone || s.link.spec == null) s.profile.phoneNodeId else 0
        if (id == 0) {
            notice("Connect to your node first, so the app knows your node number")
            return
        }
        showSheet(
            Sheet.ShowQr(
                title = "My contact code",
                payload = QrCard.Person(id, s.profile.name).encode(),
                caption = "${s.profile.name} · Node $id\nAnother MeshX user scans this to add you.",
            ),
        )
    }

    fun showNetworkKeyQr() {
        val key = s.config[ConfigKeys.NET_KEY] ?: return
        showSheet(Sheet.ShowQr("Network key", QrCard.Network(key).encode(), "Scan this from the node settings of another phone to join the same mesh."))
    }

    fun scanQr() {
        closeSheet()
        host?.scanQr()
    }

    /** The activity reports what the camera read, or null when the user went back. */
    fun onQrScanned(text: String?) {
        if (text == null) return
        when (val card = QrCard.parse(text)) {
            null -> notice("This is not a MeshX code")
            is QrCard.Person -> when {
                card.id == s.myId -> notice("That is your own code")
                else -> saveContact(card.id, card.name, ContactSource.Qr)
            }
            is QrCard.Network -> showSheet(
                Sheet.Confirm(
                    title = "Join this mesh?",
                    text = "Your node will use the scanned network key. It can then only talk to nodes that use the same key.",
                    button = "Use this key",
                    action = ConfirmAction.ApplyNetworkKey(card.key),
                ),
            )
        }
    }

    fun shareText(text: String) {
        host?.shareText(text)
    }

    // ------------------------------------------------------------------ nodes and network

    fun ping(dst: Int) {
        if (!s.connected) {
            notice("Not connected to a node")
            return
        }
        val id = nextPingId++
        update { it.copy(pings = it.pings + (dst to PingResult(id, dst, pending = true))) }
        send(Protocol.ping(id, dst))
        timers.after(PING_TIMEOUT_MS) {
            update { st ->
                val waiting = st.pings[dst]
                if (waiting != null && waiting.id == id && waiting.pending) st.copy(pings = st.pings + (dst to waiting.copy(pending = false))) else st
            }
        }
    }

    fun forgetNode(id: Int) {
        update { st -> st.copy(nodes = st.nodes - id, history = st.history - id, routes = st.routes - id) }
        scheduleSave()
    }

    fun setRoutingMode(mode: RoutingMode) = setConfig(ConfigKeys.ROUTE, mode.key)

    fun selectRouteTarget(id: Int) = update { it.copy(routeTarget = if (it.routeTarget == id) 0 else id) }

    fun setTrafficPaused(paused: Boolean) = update { it.copy(trafficPaused = paused) }

    fun clearTraffic() = update { it.copy(traffic = emptyList(), stats = TrafficStats()) }

    /** Demo only: switches one of the simulated nodes off or on. */
    fun setDemoPower(id: Int, on: Boolean) {
        val demo = link as? DemoControls ?: return
        demo.setNodePower(id, on)
        update { it.copy(demoOff = if (on) it.demoOff - id else it.demoOff + id) }
    }

    // ------------------------------------------------------------------ node settings

    /** Changes one setting on the connected node. The node checks the value again. */
    fun setConfig(key: String, value: String) {
        val problem = ConfigKeys.problem(key, value)
        if (problem != null) {
            update { it.copy(configError = problem) }
            return
        }
        if (!s.connected) {
            update { it.copy(configError = "Not connected to a node") }
            return
        }
        update { it.copy(configBusy = key, configError = "") }
        send(Protocol.set(key, value))
        configTimer?.cancel()
        configTimer = timers.after(CONFIG_TIMEOUT_MS) {
            if (s.configBusy == key) update { it.copy(configBusy = "", configError = "The node did not answer") }
        }
    }

    fun rebootNode() {
        if (s.connected) {
            send(Protocol.REBOOT)
            notice("The node is restarting")
        }
    }

    fun confirm(action: ConfirmAction) {
        closeSheet()
        when (action) {
            is ConfirmAction.DeleteChat -> {
                update { st -> st.copy(messages = st.messages.filter { it.peer != action.peer }, unread = st.unread - action.peer) }
                if (s.screen == Screen.Chat(action.peer)) back()
                scheduleSave()
            }
            is ConfirmAction.RemoveContact -> removeContact(action.id)
            is ConfirmAction.ForgetNode -> {
                forgetNode(action.id)
                if (s.screen == Screen.NodeDetail(action.id)) back()
            }
            is ConfirmAction.ApplyNetworkKey -> setConfig(ConfigKeys.NET_KEY, action.key)
            ConfirmAction.RebootNode -> rebootNode()
            ConfirmAction.ClearEverything -> {
                update { st ->
                    st.copy(messages = emptyList(), unread = emptyMap(), contacts = emptyList(), nodes = st.nodes.filter { it.value.online || it.key == st.myId })
                }
                scheduleSave()
                notice("Chats and contacts were deleted")
            }
        }
    }

    // ------------------------------------------------------------------ firmware update

    fun pickFirmware() {
        host?.pickFirmware()
    }

    /** The activity reports the file the user chose. bytes is null when it could not be read. */
    fun onFirmwarePicked(name: String, bytes: ByteArray?) {
        when {
            bytes == null -> notice("The file could not be read")
            bytes.isEmpty() -> notice("The file is empty")
            bytes.size > MAX_FIRMWARE_BYTES -> notice("The file is larger than 8 MB, that is not a node firmware")
            else -> {
                firmware = bytes
                update { it.copy(ota = OtaStatus(OtaPhase.Ready, Protocol.oneLine(name).replace('|', '_'), bytes.size)) }
            }
        }
    }

    /** Demo only: a pretend firmware file, so the update can be tried without a real one. */
    fun useSampleFirmware() {
        onFirmwarePicked("meshx-node-0.3.0.bin", ByteArray(96 * 1024) { (it * 131 + 7).toByte() })
    }

    fun startOta() {
        val image = firmware ?: return
        if (!s.connected) {
            notice("Not connected to a node")
            return
        }
        if (s.ota.busy) return
        otaSeq = 0
        otaOffset = 0
        otaChunk = 0
        update { it.copy(ota = it.ota.copy(phase = OtaPhase.Sending, sent = 0, message = "Sending")) }
        val crc = CRC32().apply { update(image) }.value
        send(Protocol.otaBegin(image.size, crc, s.ota.fileName))
        otaWatchdog()
    }

    fun cancelOta() {
        if (s.ota.busy) send(Protocol.OTA_ABORT)
        otaTimer?.cancel()
        update { it.copy(ota = if (firmware != null) it.ota.copy(phase = OtaPhase.Ready, sent = 0, message = "") else OtaStatus()) }
    }

    fun clearOta() {
        if (s.ota.busy) return
        firmware = null
        update { it.copy(ota = OtaStatus()) }
    }

    private fun sendOtaChunk() {
        val image = firmware ?: return
        val end = minOf(otaOffset + otaChunk, image.size)
        send(Protocol.otaData(otaSeq, image.copyOfRange(otaOffset, end)))
        otaWatchdog()
    }

    private fun onOtaAck(seq: Int) {
        val image = firmware ?: return
        if (s.ota.phase != OtaPhase.Sending || seq != otaSeq) return
        otaOffset = minOf(otaOffset + otaChunk, image.size)
        otaSeq++
        if (otaOffset >= image.size) {
            update { it.copy(ota = it.ota.copy(phase = OtaPhase.Verifying, sent = image.size, message = "Checking")) }
            send(Protocol.OTA_END)
            otaWatchdog()
        } else {
            // the screen does not need every chunk, a step every few is smooth enough
            if (otaSeq % 4 == 0) update { it.copy(ota = it.ota.copy(sent = otaOffset)) }
            sendOtaChunk()
        }
    }

    private fun otaWatchdog() {
        otaTimer?.cancel()
        otaTimer = timers.after(OTA_TIMEOUT_MS) { if (s.ota.busy) otaFailed("The node stopped answering") }
    }

    private fun otaFailed(reason: String) {
        otaTimer?.cancel()
        update { it.copy(ota = it.ota.copy(phase = OtaPhase.Failed, message = reason)) }
    }

    private companion object {
        const val MAX_MESSAGES = 500
        const val MAX_PACKETS = 150
        const val HISTORY_POINTS = 40
        const val MAX_MSG_ID = 65000
        const val MAX_FIRMWARE_BYTES = 8 * 1024 * 1024
        const val MAX_OTA_CHUNK = 700
        const val HELLO_TIMEOUT_MS = 8_000L
        const val SCAN_MS = 15_000L
        const val MESSAGE_TIMEOUT_MS = 60_000L
        const val PING_TIMEOUT_MS = 20_000L
        const val CONFIG_TIMEOUT_MS = 6_000L
        const val OTA_TIMEOUT_MS = 15_000L
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000)
    }
}
