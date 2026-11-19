package com.meshx.app.mesh

import com.meshx.app.model.BROADCAST_ID
import com.meshx.app.model.MAX_NODE_ID
import com.meshx.app.model.RoutingMode
import com.meshx.app.protocol.ConfigKeys
import com.meshx.app.protocol.PhoneFrame
import com.meshx.app.protocol.Protocol
import java.util.zip.CRC32
import kotlin.random.Random

/** All the waiting times of a node, in one place. The demo mesh uses short ones. */
data class NodeTiming(
    val beaconMs: Long = 5000,          // how often a node announces itself
    val jitterMs: Long = 150,           // random wait before repeating a flooded packet
    val hopTimeoutMs: Long = 1500,      // wait for an ACK, per hop of the route
    val floodTimeoutMs: Long = 5000,    // wait for an ACK of a flooded message
    val maxAttempts: Int = 3,           // tries per message
    val reportMs: Long = 1000,          // how often the phone gets fresh tables
    val ttl: Int = 5,                   // hop limit
    val otaChunk: Int = 512,            // firmware bytes per OTA line
    val otaWriteMs: Long = 0,           // pretend time to write one chunk to flash
)

/**
 * A complete MeshX node in software.
 *
 * It does what the firmware of a hardware node does: it sends beacons, measures its links,
 * learns the topology, routes and repeats packets, retries until a message is acknowledged
 * and talks to its phone with the line protocol. The app uses it in two places:
 * the demo mesh runs four of these, and in phone-to-phone mode the phone itself is one.
 *
 * Everything runs on one thread. Inputs are [fromPhone], the radio and timers.
 */
class SoftNode(
    id: Int,
    name: String,
    private val radio: Radio,
    private val timers: Timers,
    private val random: Random,
    private val timing: NodeTiming = NodeTiming(),
    private val hardware: String = "Phone",
    firmware: String = "0.2.0",
    private val battery: () -> Int = { 100 },
    hardwareConfig: Map<String, String> = emptyMap(),
    private val otaSupported: Boolean = false,
) {
    var id: Int = id
        private set
    var name: String = Protocol.cleanName(name)
        private set
    var mode: RoutingMode = RoutingMode.Adaptive
        private set
    var firmware: String = firmware
        private set

    /** Receives the lines for the phone that is attached to this node. */
    var phone: ((String) -> Unit)? = null
        set(value) {
            field = value
            helloDone = false
        }

    private class Neighbor(val id: Int) {
        var rssi = 0f
        var snr = 0f
        var lastHeard = 0L
        var lastBeaconSeq = -1
        var lastBeaconAt = 0L
        val slots = ArrayDeque<Boolean>() // the last beacons: true = heard, false = missed

        fun mark(heard: Boolean) {
            slots.addLast(heard)
            while (slots.size > 10) slots.removeFirst()
        }

        fun loss(): Float = if (slots.isEmpty()) 0f else slots.count { !it }.toFloat() / slots.size
    }

    /** What another node last told the mesh about itself. */
    private class Record(val id: Int) {
        var seq = -1
        var heardAt = 0L
        var name = ""
        var battery = -1
        var uptimeSec = 0L
        var tx = 0
        var rx = 0
        var fwd = 0
        var drop = 0
        var phone = false
        var fw = ""
        var lat: Double? = null
        var lon: Double? = null
        var posAt = 0L
        var links: Map<Int, Int> = emptyMap() // neighbour id -> score measured by that node
    }

    private class Pending(val kind: PacketKind, val msgId: Int, val dst: Int, val body: String, val startedAt: Long) {
        var attempt = 0
        var timer: TimerHandle? = null
    }

    private val settings = LinkedHashMap(hardwareConfig)
    private val neighbors = HashMap<Int, Neighbor>()
    private val records = HashMap<Int, Record>()
    private val pending = HashMap<Long, Pending>()
    private val seen = RecentSet(400)        // packets already handled, one entry per attempt
    private val delivered = RecentSet(200)   // messages already given to the phone
    private val reported = HashMap<String, String>()
    private val steadyValues = HashMap<String, Int>()
    private var reportedLinks: Set<Long> = emptySet()

    private var running = false
    private var epoch = 0
    private var helloDone = false
    private var bootAt = 0L
    private var beaconSeq = 0
    private var beaconTimer: TimerHandle? = null
    private var tx = 0
    private var rx = 0
    private var fwd = 0
    private var drop = 0
    private var lat: Double? = null
    private var lon: Double? = null

    private var otaSize = -1
    private var otaCrc = 0L
    private var otaLabel = ""
    private var otaGot = 0
    private var otaNext = 0
    private val otaSum = CRC32()

    // ------------------------------------------------------------------ life cycle

    fun start() {
        if (running) return
        running = true
        radio.onReceive = { bytes, rssi, snr -> onRadio(bytes, rssi, snr) }
        boot()
    }

    fun stop() {
        running = false
        epoch++
        radio.onReceive = null
    }

    private fun boot() {
        epoch++
        bootAt = timers.now()
        beaconSeq = random.nextInt(1, 20000)
        tx = 0; rx = 0; fwd = 0; drop = 0
        neighbors.clear()
        records.clear()
        pending.clear()
        seen.clear()
        delivered.clear()
        reported.clear()
        steadyValues.clear()
        reportedLinks = emptySet()
        otaSize = -1
        scheduleBeacon(200 + random.nextLong(0, 600))
        reportLoop()
    }

    private fun later(delayMs: Long, action: () -> Unit): TimerHandle {
        val bornIn = epoch
        return timers.after(delayMs) { if (running && bornIn == epoch) action() }
    }

    private fun uptimeSec(): Long = (timers.now() - bootAt) / 1000

    // ------------------------------------------------------------------ radio side

    private fun onRadio(bytes: ByteArray, rssi: Int, snr: Float) {
        if (!running) return
        val packet = Packet.decode(bytes)
        if (packet == null) {
            drop++
            return
        }
        if (packet.via == id) return
        rx++
        val neighbor = hear(packet.via, rssi, snr)
        toPhone(Protocol.pkt(packet.kind.label, packet.src, packet.dst, packet.via, packet.hops, rssi, bytes.size))
        if (packet.src == id) return // our own packet, repeated by a neighbour
        when (packet.kind) {
            PacketKind.Beacon -> onBeacon(packet, neighbor)
            PacketKind.Msg, PacketKind.Ping -> onData(packet, rssi, snr)
            PacketKind.Ack, PacketKind.Pong -> onReply(packet)
        }
    }

    /** Every packet we hear tells us something about the link to the node that sent it. */
    private fun hear(from: Int, rssi: Int, snr: Float): Neighbor {
        val neighbor = neighbors.getOrPut(from) { Neighbor(from) }
        if (rssi != 0) {
            if (neighbor.rssi == 0f) {
                neighbor.rssi = rssi.toFloat()
                neighbor.snr = snr
            } else {
                neighbor.rssi += SMOOTHING * (rssi - neighbor.rssi)
                neighbor.snr += SMOOTHING * (snr - neighbor.snr)
            }
        }
        neighbor.lastHeard = timers.now()
        return neighbor
    }

    private fun onBeacon(packet: Packet, neighbor: Neighbor) {
        val now = timers.now()
        if (packet.via == packet.src) {
            // Heard first hand: the beacon number shows how many beacons we missed.
            val last = neighbor.lastBeaconSeq
            if (last < 0 || packet.id < last - 20) {
                neighbor.slots.clear()
                neighbor.mark(true)
                neighbor.lastBeaconSeq = packet.id
            } else if (packet.id > last) {
                repeat((packet.id - last - 1).coerceAtMost(8)) { neighbor.mark(false) }
                neighbor.mark(true)
                neighbor.lastBeaconSeq = packet.id
            }
            neighbor.lastBeaconAt = now
        }
        if (!seen.add(seenKey(packet))) return
        val info = packet.body.split(';', limit = 12)
        if (info.size != 12) {
            drop++
            return
        }
        val record = records.getOrPut(packet.src) { Record(packet.src) }
        val uptime = info[1].toLongOrNull() ?: 0L
        val stale = now - record.heardAt > timing.beaconMs * 5 / 2
        val rebooted = uptime + 3 * timing.beaconMs / 1000 < record.uptimeSec
        if (packet.id > record.seq || stale || rebooted) {
            record.seq = packet.id
            record.heardAt = now
            record.battery = info[0].toIntOrNull() ?: -1
            record.uptimeSec = uptime
            record.tx = info[2].toIntOrNull() ?: 0
            record.rx = info[3].toIntOrNull() ?: 0
            record.fwd = info[4].toIntOrNull() ?: 0
            record.drop = info[5].toIntOrNull() ?: 0
            record.phone = info[6] == "1"
            record.fw = info[7]
            val newLat = info[8].toDoubleOrNull()
            val newLon = info[9].toDoubleOrNull()
            if (newLat != null && newLon != null) {
                record.lat = newLat
                record.lon = newLon
                record.posAt = now
            }
            record.links = parseLinks(info[10])
            record.name = info[11]
        }
        if (packet.ttl > 1) {
            floodLater(packet.copy(ttl = packet.ttl - 1, hops = packet.hops + 1, next = 0))
        }
    }

    private fun parseLinks(text: String): Map<Int, Int> {
        if (text.isEmpty()) return emptyMap()
        val out = HashMap<Int, Int>()
        for (pair in text.split(',')) {
            val other = pair.substringBefore(':').toIntOrNull() ?: continue
            val score = pair.substringAfter(':', "").toIntOrNull() ?: continue
            out[other] = score.coerceIn(1, 100)
        }
        return out
    }

    private fun onData(packet: Packet, rssi: Int, snr: Float) {
        val forMe = packet.dst == id
        val forAll = packet.dst == BROADCAST_ID
        // A routed packet names the node that handles it next. Everyone else ignores it,
        // even the destination, so a packet really follows the route that was chosen.
        if (packet.next != 0 && packet.next != id) return
        if (!seen.add(seenKey(packet))) return
        if (forMe || forAll) {
            // A retry of something we already have is not shown twice.
            val first = delivered.add(deliveredKey(packet))
            if (first && packet.kind == PacketKind.Msg) {
                toPhone(Protocol.rx(packet.id, packet.src, packet.dst, packet.hops, rssi, snr, packet.body))
            }
        }
        if (forMe) {
            // Answer every attempt: the earlier answer may have been lost on the way back.
            val reply = if (packet.kind == PacketKind.Msg) PacketKind.Ack else PacketKind.Pong
            val route = if (packet.mode == RoutingMode.Flood) emptyList() else path(packet.src, packet.mode, edges(timers.now()))
            transmit(
                Packet(
                    kind = reply, src = id, dst = packet.src, id = packet.id, attempt = packet.attempt,
                    ttl = timing.ttl, via = id, next = route.getOrElse(1) { 0 }, mode = packet.mode,
                    body = packet.hops.toString(),
                ),
            )
            return
        }
        forward(packet)
    }

    private fun onReply(packet: Packet) {
        val forMe = packet.dst == id
        if (packet.next != 0 && packet.next != id) return
        if (!seen.add(seenKey(packet))) return
        if (!forMe) {
            forward(packet)
            return
        }
        val asked = if (packet.kind == PacketKind.Ack) PacketKind.Msg else PacketKind.Ping
        val waiting = pending.remove(pendingKey(asked, packet.id)) ?: return // a late copy
        waiting.timer?.cancel()
        val rtt = (timers.now() - waiting.startedAt).toInt()
        val hops = packet.body.toIntOrNull() ?: packet.hops
        if (asked == PacketKind.Msg) toPhone(Protocol.ack(packet.id, hops, rtt)) else toPhone(Protocol.pong(packet.id, rtt, hops))
    }

    /** Passes a packet on towards its destination. */
    private fun forward(packet: Packet) {
        if (packet.ttl <= 1) {
            drop++
            return
        }
        val out = packet.copy(ttl = packet.ttl - 1, hops = packet.hops + 1)
        if (packet.dst == BROADCAST_ID || packet.next == 0) {
            floodLater(out.copy(next = 0))
            return
        }
        val route = path(packet.dst, packet.mode, edges(timers.now()))
        if (route.size < 2) {
            floodLater(out.copy(next = 0)) // no route known from here: flood the rest of the way
        } else {
            fwd++
            transmit(out.copy(next = route[1]))
        }
    }

    /** Flooded packets wait a random moment so neighbours do not all repeat at the same time. */
    private fun floodLater(packet: Packet) {
        later(20 + random.nextLong(0, timing.jitterMs.coerceAtLeast(1))) {
            fwd++
            transmit(packet)
        }
    }

    private fun transmit(packet: Packet) {
        val out = packet.copy(via = id)
        val bytes = out.encode()
        tx++
        toPhone(Protocol.pkt(out.kind.label, out.src, out.dst, id, out.hops, 0, bytes.size))
        radio.transmit(bytes)
    }

    // ------------------------------------------------------------------ beacons

    private fun scheduleBeacon(delayMs: Long) {
        beaconTimer?.cancel()
        beaconTimer = later(delayMs) { beacon() }
    }

    private fun beacon() {
        val now = timers.now()
        beaconSeq++
        // A neighbour that has been silent for more than one and a half periods missed a beacon.
        for (neighbor in neighbors.values) {
            if (neighbor.lastBeaconSeq >= 0 && now - neighbor.lastBeaconAt > timing.beaconMs * 3 / 2) {
                neighbor.mark(false)
                neighbor.lastBeaconSeq++
                neighbor.lastBeaconAt = now
            }
        }
        val links = neighbors.values.filter { alive(it, now) }.sortedBy { it.id }
            .joinToString(",") { "${it.id}:${scoreOf(it)}" }
        val body = listOf(
            battery().toString(), uptimeSec().toString(), tx.toString(), rx.toString(), fwd.toString(), drop.toString(),
            if (phone != null && helloDone) "1" else "0", firmware,
            lat?.let { Protocol.coord(it) } ?: "", lon?.let { Protocol.coord(it) } ?: "", links, name,
        ).joinToString(";")
        transmit(
            Packet(
                kind = PacketKind.Beacon, src = id, dst = BROADCAST_ID, id = beaconSeq,
                ttl = timing.ttl, via = id, mode = RoutingMode.Flood, body = body,
            ),
        )
        scheduleBeacon(timing.beaconMs * 9 / 10 + random.nextLong(0, timing.beaconMs / 5 + 1))
    }

    private fun alive(neighbor: Neighbor, now: Long) = now - neighbor.lastHeard <= timing.beaconMs * 7 / 2
    private fun alive(record: Record, now: Long) = now - record.heardAt <= timing.beaconMs * 9 / 2

    private fun scoreOf(neighbor: Neighbor): Int =
        LinkScore.score(neighbor.rssi, neighbor.snr, neighbor.loss(), records[neighbor.id]?.battery ?: -1)

    // ------------------------------------------------------------------ routing

    /** Every link we know about, with one score per link (the weaker direction counts). */
    private fun edges(now: Long): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (neighbor in neighbors.values) {
            if (!alive(neighbor, now)) continue
            val mine = scoreOf(neighbor)
            val theirs = records[neighbor.id]?.takeIf { alive(it, now) }?.links?.get(id)
            out[edgeKey(id, neighbor.id)] = if (theirs != null) minOf(mine, theirs) else mine
        }
        for (record in records.values) {
            if (!alive(record, now)) continue
            for ((other, score) in record.links) {
                if (other == id) continue
                val otherRecord = records[other] ?: continue
                if (!alive(otherRecord, now)) continue
                val back = otherRecord.links[record.id]
                out[edgeKey(record.id, other)] = if (back != null) minOf(score, back) else score
            }
        }
        return out
    }

    /**
     * Best path from this node to dst as a list of node ids, empty when there is none.
     * Adaptive: lowest total link cost (weak links are expensive), then fewest hops.
     * Shortest: fewest hops, then lowest cost.
     */
    private fun path(dst: Int, mode: RoutingMode, edges: Map<Long, Int>): List<Int> {
        if (dst == id) return emptyList()
        val graph = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((key, score) in edges) {
            val a = (key shr 16).toInt()
            val b = (key and 0xFFFF).toInt()
            graph.getOrPut(a) { ArrayList() }.add(b to score)
            graph.getOrPut(b) { ArrayList() }.add(a to score)
        }
        if (dst !in graph || id !in graph) return emptyList()
        val hopsFirst = mode != RoutingMode.Adaptive
        val cost = HashMap<Int, Float>()
        val hops = HashMap<Int, Int>()
        val previous = HashMap<Int, Int>()
        val done = HashSet<Int>()
        cost[id] = 0f
        hops[id] = 0

        fun better(c1: Float, h1: Int, c2: Float, h2: Int): Boolean =
            if (hopsFirst) h1 < h2 || (h1 == h2 && c1 < c2) else c1 < c2 || (c1 == c2 && h1 < h2)

        while (true) {
            var best = -1
            for (node in cost.keys) {
                if (node in done) continue
                if (best == -1 || better(cost.getValue(node), hops.getValue(node), cost.getValue(best), hops.getValue(best))) {
                    best = node
                }
            }
            if (best == -1 || best == dst) break
            done.add(best)
            for ((other, score) in graph[best].orEmpty()) {
                if (other in done) continue
                val c = cost.getValue(best) + LinkScore.cost(score)
                val h = hops.getValue(best) + 1
                val known = cost[other]
                if (known == null || better(c, h, known, hops.getValue(other))) {
                    cost[other] = c
                    hops[other] = h
                    previous[other] = best
                }
            }
        }
        if (dst !in previous) return emptyList()
        val route = ArrayList<Int>()
        var at = dst
        while (at != id) {
            route.add(at)
            at = previous[at] ?: return emptyList()
        }
        route.add(id)
        route.reverse()
        return route
    }

    // ------------------------------------------------------------------ sending

    private fun attempt(p: Pending) {
        p.attempt++
        val tries = if (p.kind == PacketKind.Msg) timing.maxAttempts else 1
        // The last retry is flooded, in case the route itself is the problem.
        val flood = mode == RoutingMode.Flood || (p.attempt > 1 && p.attempt == tries)
        val route = if (flood) emptyList() else path(p.dst, mode, edges(timers.now()))
        val next = route.getOrElse(1) { 0 }
        transmit(
            Packet(
                kind = p.kind, src = id, dst = p.dst, id = p.msgId, attempt = p.attempt,
                ttl = timing.ttl, via = id, next = next, mode = mode, body = p.body,
            ),
        )
        val wait = if (next == 0) timing.floodTimeoutMs else timing.hopTimeoutMs * (route.size - 1)
        p.timer = later(wait) {
            val key = pendingKey(p.kind, p.msgId)
            if (pending[key] === p) {
                if (p.attempt < tries) {
                    attempt(p)
                } else {
                    pending.remove(key)
                    if (p.kind == PacketKind.Msg) toPhone(Protocol.fail(p.msgId, "timeout")) else toPhone(Protocol.pong(p.msgId, -1, 0))
                }
            }
        }
    }

    // ------------------------------------------------------------------ phone side

    private fun toPhone(line: String) {
        if (helloDone) phone?.invoke(line)
    }

    /** A line from the phone that is attached to this node. */
    fun fromPhone(line: String) {
        if (!running) return
        val frame = Protocol.parsePhoneFrame(line)
        if (frame is PhoneFrame.Hello) {
            if (frame.name.isNotBlank()) name = Protocol.cleanName(frame.name)
            helloDone = true
            reported.clear()
            reportedLinks = emptySet()
            report()
            toPhone(Protocol.READY)
            scheduleBeacon(50) // tell the mesh about the new name right away
            return
        }
        if (!helloDone) return
        when (frame) {
            null -> toPhone(Protocol.err("FRAME", "not understood"))
            is PhoneFrame.Hello -> Unit
            is PhoneFrame.Msg -> onPhoneMsg(frame)
            is PhoneFrame.Set -> onSet(frame.key, frame.value)
            is PhoneFrame.Get -> onGet(frame.key)
            is PhoneFrame.Pos -> {
                lat = frame.lat
                lon = frame.lon
            }
            is PhoneFrame.Ping -> {
                if (frame.dst !in 1..MAX_NODE_ID || frame.dst == id) {
                    toPhone(Protocol.pong(frame.id, -1, 0))
                } else {
                    val p = Pending(PacketKind.Ping, frame.id, frame.dst, "", timers.now())
                    pending[pendingKey(p.kind, p.msgId)] = p
                    attempt(p)
                }
            }
            is PhoneFrame.OtaBegin -> otaBegin(frame)
            is PhoneFrame.OtaData -> otaData(frame)
            PhoneFrame.OtaEnd -> otaEnd()
            PhoneFrame.OtaAbort -> {
                otaSize = -1
                toPhone(Protocol.ok("OTA"))
            }
            PhoneFrame.Reboot -> {
                toPhone(Protocol.ok("REBOOT"))
                later(300) { reboot() }
            }
        }
    }

    private fun onPhoneMsg(frame: PhoneFrame.Msg) {
        val text = Protocol.cleanText(frame.text)
        if (frame.to !in 0..MAX_NODE_ID || frame.to == id) {
            toPhone(Protocol.fail(frame.msgId, "bad address"))
            return
        }
        if (text.isEmpty()) {
            toPhone(Protocol.fail(frame.msgId, "empty"))
            return
        }
        if (frame.to == BROADCAST_ID) {
            // Messages for everyone are flooded and not acknowledged.
            transmit(
                Packet(
                    kind = PacketKind.Msg, src = id, dst = BROADCAST_ID, id = frame.msgId,
                    ttl = timing.ttl, via = id, mode = RoutingMode.Flood, body = text,
                ),
            )
            toPhone(Protocol.sent(frame.msgId))
            return
        }
        val p = Pending(PacketKind.Msg, frame.msgId, frame.to, text, timers.now())
        pending[pendingKey(p.kind, p.msgId)] = p
        attempt(p)
        toPhone(Protocol.sent(frame.msgId))
    }

    private fun onSet(key: String, value: String) {
        val problem = when {
            key == ConfigKeys.ID || key == ConfigKeys.ROUTE -> ConfigKeys.problem(key, value)
            key in ConfigKeys.readOnly -> "This value cannot be changed"
            key !in settings -> "This node does not have that setting"
            else -> ConfigKeys.problem(key, value)
        }
        if (problem != null) {
            toPhone(Protocol.err(key, problem))
            return
        }
        when (key) {
            ConfigKeys.ID -> {
                id = value.toInt()
                records.remove(id)
                neighbors.remove(id)
                reported.clear()
                reportedLinks = emptySet()
            }
            ConfigKeys.ROUTE -> mode = RoutingMode.fromKey(value) ?: mode
            else -> settings[key] = value
        }
        toPhone(Protocol.cfg(key, value))
        toPhone(Protocol.ok(key))
        if (key == ConfigKeys.ID || key == ConfigKeys.ROUTE) report()
    }

    private fun onGet(key: String) {
        val all = configLines()
        if (key == "*") {
            all.forEach { toPhone(it) }
            toPhone(Protocol.ok("GET"))
        } else {
            val line = all.firstOrNull { it.startsWith("CFG|$key|") }
            toPhone(line ?: Protocol.err(key, "Unknown setting"))
        }
    }

    private fun configLines(): List<String> {
        val lines = ArrayList<String>()
        lines.add(Protocol.cfg(ConfigKeys.ID, id.toString()))
        lines.add(Protocol.cfg(ConfigKeys.ROUTE, mode.key))
        for ((key, value) in settings) lines.add(Protocol.cfg(key, value))
        lines.add(Protocol.cfg(ConfigKeys.HW, hardware))
        lines.add(Protocol.cfg(ConfigKeys.FW, firmware))
        lines.add(Protocol.cfg(ConfigKeys.OTA, if (otaSupported) "yes" else "no"))
        return lines
    }

    private fun reboot() {
        boot()
        if (helloDone) {
            report()
            toPhone(Protocol.cfg(ConfigKeys.FW, firmware))
            toPhone(Protocol.READY)
        }
    }

    // ------------------------------------------------------------------ firmware update

    private fun otaBegin(frame: PhoneFrame.OtaBegin) {
        when {
            !otaSupported -> toPhone(Protocol.otaErr("This node cannot be updated from the phone"))
            frame.size <= 0 || frame.size > MAX_FIRMWARE_BYTES -> toPhone(Protocol.otaErr("Bad file size"))
            else -> {
                otaSize = frame.size
                otaCrc = frame.crc
                otaLabel = frame.label
                otaGot = 0
                otaNext = 0
                otaSum.reset()
                toPhone(Protocol.otaReady(timing.otaChunk))
            }
        }
    }

    private fun otaData(frame: PhoneFrame.OtaData) {
        when {
            otaSize < 0 -> toPhone(Protocol.otaErr("Update was not started"))
            frame.seq != otaNext -> {
                otaSize = -1
                toPhone(Protocol.otaErr("Chunk out of order"))
            }
            otaGot + frame.data.size > otaSize -> {
                otaSize = -1
                toPhone(Protocol.otaErr("More data than announced"))
            }
            else -> {
                otaSum.update(frame.data)
                otaGot += frame.data.size
                otaNext++
                val seq = frame.seq
                if (timing.otaWriteMs > 0) later(timing.otaWriteMs) { toPhone(Protocol.otaAck(seq)) } else toPhone(Protocol.otaAck(seq))
            }
        }
    }

    private fun otaEnd() {
        when {
            otaSize < 0 -> toPhone(Protocol.otaErr("Update was not started"))
            otaGot != otaSize -> {
                otaSize = -1
                toPhone(Protocol.otaErr("File is incomplete"))
            }
            otaSum.value != otaCrc -> {
                otaSize = -1
                toPhone(Protocol.otaErr("Checksum does not match"))
            }
            else -> {
                otaSize = -1
                toPhone(Protocol.OTA_DONE)
                val version = VERSION_IN_NAME.find(otaLabel)?.value
                later(1500) {
                    if (version != null) firmware = version
                    reboot()
                }
            }
        }
    }

    // ------------------------------------------------------------------ reports for the phone

    private fun reportLoop() {
        later(timing.reportMs) {
            report()
            reportLoop()
        }
    }

    /** Sends the phone every table line that changed since the last report. */
    private fun report() {
        if (!helloDone || phone == null) return
        val now = timers.now()
        val edges = edges(now)
        val shown = if (mode == RoutingMode.Flood) RoutingMode.Shortest else mode
        emit("me", Protocol.me(id, battery(), firmware, name))
        emit("s$id", Protocol.stat(id, uptimeSec(), tx, rx, fwd, drop, true, firmware))
        val myLat = lat
        val myLon = lon
        if (myLat != null && myLon != null) emit("p$id", Protocol.posOf(id, myLat, myLon, 0))

        for (record in records.values.sortedBy { it.id }) {
            if (record.id == id) continue
            val online = alive(record, now)
            val route = if (online) path(record.id, shown, edges) else emptyList()
            val neighbor = neighbors[record.id]?.takeIf { alive(it, now) }
            var quality = 0
            for (i in 0 until route.size - 1) {
                val score = edges[edgeKey(route[i], route[i + 1])] ?: 0
                quality = if (i == 0) score else minOf(quality, score)
            }
            val age = if (online) 0L else coarse(((now - record.heardAt) / 1000).coerceAtLeast(1))
            emit(
                "n${record.id}",
                Protocol.node(
                    id = record.id,
                    rssi = if (neighbor == null) 0 else steady("rssi${record.id}", neighbor.rssi.toInt(), 3),
                    snr = if (neighbor == null) 0f else steady("snr${record.id}", (neighbor.snr * 10).toInt(), 15) / 10f,
                    battery = record.battery,
                    loss = neighbor?.let { (it.loss() * 100).toInt() } ?: 0,
                    score = if (quality == 0) 0 else steady("q${record.id}", quality, 3),
                    hops = (route.size - 1).coerceAtLeast(0),
                    nextHop = route.getOrElse(1) { 0 },
                    lastSeenSec = age,
                    name = record.name,
                ),
            )
            emit(
                "r${record.id}",
                if (mode == RoutingMode.Flood && online) Protocol.routeFlood(record.id) else Protocol.route(record.id, route),
            )
            emit(
                "s${record.id}",
                Protocol.stat(record.id, record.uptimeSec, record.tx, record.rx, record.fwd, record.drop, record.phone, record.fw),
            )
            val rLat = record.lat
            val rLon = record.lon
            if (rLat != null && rLon != null) {
                emit("p${record.id}", Protocol.posOf(record.id, rLat, rLon, coarse((now - record.posAt) / 1000)))
            }
        }

        for ((key, score) in edges) {
            emit("l$key", Protocol.link((key shr 16).toInt(), (key and 0xFFFF).toInt(), steady("l$key", score, 3)))
        }
        for (gone in reportedLinks - edges.keys) {
            emit("l$gone", Protocol.link((gone shr 16).toInt(), (gone and 0xFFFF).toInt(), 0))
        }
        reportedLinks = HashSet(edges.keys)
    }

    private fun emit(key: String, line: String) {
        if (reported[key] != line) {
            reported[key] = line
            toPhone(line)
        }
    }

    /**
     * Measurements wobble a little all the time. The phone only gets a new value when it
     * moved by at least [band], so the tables stay calm and the link is not kept busy.
     */
    private fun steady(key: String, value: Int, band: Int): Int {
        val shown = steadyValues[key]
        if (shown != null && kotlin.math.abs(value - shown) < band) return shown
        steadyValues[key] = value
        return value
    }

    /** Ages are rounded so that a line does not change every second. */
    private fun coarse(seconds: Long): Long = if (seconds < 60) seconds / 10 * 10 else seconds / 30 * 30

    private fun seenKey(p: Packet): Long =
        (p.src.toLong() shl 40) or (p.kind.ordinal.toLong() shl 36) or ((p.attempt.toLong() and 0xF) shl 32) or (p.id.toLong() and 0xFFFFFFFFL)

    private fun deliveredKey(p: Packet): Long =
        (p.src.toLong() shl 40) or (p.kind.ordinal.toLong() shl 36) or (p.id.toLong() and 0xFFFFFFFFL)

    private fun pendingKey(kind: PacketKind, msgId: Int): Long = (kind.ordinal.toLong() shl 32) or msgId.toLong()

    private fun edgeKey(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 16) or maxOf(a, b).toLong()

    /** Remembers the most recent keys only. */
    private class RecentSet(private val capacity: Int) {
        private val keys = LinkedHashSet<Long>()

        /** Returns true when the key is new. */
        fun add(key: Long): Boolean {
            if (!keys.add(key)) return false
            if (keys.size > capacity) keys.remove(keys.first())
            return true
        }

        fun clear() = keys.clear()
    }

    private companion object {
        const val SMOOTHING = 0.3f
        const val MAX_FIRMWARE_BYTES = 8 * 1024 * 1024
        val VERSION_IN_NAME = Regex("""\d+\.\d+\.\d+""")
    }
}
