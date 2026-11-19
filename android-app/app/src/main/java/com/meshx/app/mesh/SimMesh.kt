package com.meshx.app.mesh

import com.meshx.app.model.BROADCAST_ID
import com.meshx.app.protocol.NodeFrame
import com.meshx.app.protocol.Protocol
import kotlin.random.Random

/**
 * The air between simulated nodes. Each pair of stations that can hear each other has a
 * signal strength, a signal-to-noise ratio and a share of packets that get lost.
 */
class SimMedium(private val timers: Timers, private val random: Random, private val airtimeMs: Long = 160) {
    private class Station(val number: Int, val send: (Int, ByteArray) -> Unit) : Radio {
        override var onReceive: ((bytes: ByteArray, rssi: Int, snr: Float) -> Unit)? = null
        override fun transmit(bytes: ByteArray) = send(number, bytes)
    }

    private class AirPath(val a: Int, val b: Int, val rssi: Int, val snr: Float, val loss: Float)

    private val stations = HashMap<Int, Station>()
    private val paths = ArrayList<AirPath>()
    private val off = HashSet<Int>()

    fun radio(station: Int): Radio = stations.getOrPut(station) { Station(station, ::transmit) }

    fun connect(a: Int, b: Int, rssi: Int, snr: Float, loss: Float) {
        paths.add(AirPath(a, b, rssi, snr, loss))
    }

    fun setPower(station: Int, on: Boolean) {
        if (on) off.remove(station) else off.add(station)
    }

    fun isOn(station: Int): Boolean = station !in off

    private fun transmit(from: Int, bytes: ByteArray) {
        if (from in off) return
        for (path in paths) {
            val to = when (from) {
                path.a -> path.b
                path.b -> path.a
                else -> continue
            }
            val lost = random.nextFloat() < path.loss
            val rssi = path.rssi + random.nextInt(-2, 3)
            val snr = path.snr + (random.nextFloat() - 0.5f)
            timers.after(airtimeMs + random.nextLong(0, 40)) {
                if (!lost && to !in off && from !in off) stations[to]?.onReceive?.invoke(bytes, rssi, snr)
            }
        }
    }
}

/**
 * The demo mesh: four complete software nodes on a simulated radio.
 *
 *   1 (you) ---- 2 Fatima ---- 3 Umaiza ---- 4 Hamna
 *      \_________ weak _________/
 *
 * Nodes 1 and 3 can hear each other only weakly, so adaptive routing goes through node 2
 * while shortest-hop routing uses the weak link. Nodes 2 to 4 answer messages on their own.
 */
class SimMesh(private val timers: Timers, myName: String, private val greet: Boolean, seed: Long = 2026) {
    private val random = Random(seed)
    private val startedAt = timers.now()
    val medium = SimMedium(timers, random)

    val timing = NodeTiming(
        beaconMs = 3000, jitterMs = 120, hopTimeoutMs = 1400, floodTimeoutMs = 3500,
        maxAttempts = 3, reportMs = 1000, ttl = 5, otaChunk = 512, otaWriteMs = 12,
    )

    private class Station(val name: String, val battery: Int, val lat: Double, val lon: Double)

    private val stations = listOf(
        Station(myName.ifBlank { "Me" }, 78, 24.93200, 67.11350),
        Station("Fatima", 86, 24.93420, 67.11520),
        Station("Umaiza", 64, 24.93650, 67.11230),
        Station("Hamna", 41, 24.93980, 67.11080),
    )

    val nodes: List<SoftNode> = stations.mapIndexed { index, station ->
        val number = index + 1
        SoftNode(
            id = number,
            name = station.name,
            radio = medium.radio(number),
            timers = timers,
            random = Random(seed + number),
            timing = timing,
            hardware = "ESP32-S3 + Ra-02 (simulated)",
            firmware = "0.2.0",
            battery = { (station.battery - (timers.now() - startedAt) / 240_000).toInt().coerceAtLeast(5) },
            hardwareConfig = mapOf(
                "sf" to "9", "bw" to "125", "power" to "17",
                "wifi" to "on", "wifi_ssid" to "MeshX-$number", "wifi_pass" to "meshx1234",
                "net_key" to "meshx-demo-key", "freq" to "433.0",
            ),
            otaSupported = true,
        )
    }

    /** The node the app is connected to. */
    val me: SoftNode get() = nodes[0]

    private val bots = ArrayList<Bot>()

    init {
        medium.connect(1, 2, rssi = -72, snr = 9.0f, loss = 0.02f)
        medium.connect(2, 3, rssi = -84, snr = 7.0f, loss = 0.05f)
        medium.connect(1, 3, rssi = -116, snr = -8.5f, loss = 0.40f)
        medium.connect(3, 4, rssi = -95, snr = 2.5f, loss = 0.10f)
    }

    fun start() {
        nodes.forEach { it.start() }
        val answers = listOf(
            listOf("Got it, loud and clear.", "Okay, on my way.", "Yes, I can see your node on my screen.", "Signal is good here."),
            listOf("Received.", "Give me five minutes.", "The link through Fatima looks stable.", "Copy that."),
            listOf("Got your message, three hops away!", "Battery is a bit low here.", "All good on this side.", "Okay."),
        )
        for (index in 1 until nodes.size) {
            val station = stations[index]
            val bot = Bot(nodes[index], station.lat, station.lon, answers[index - 1])
            bots.add(bot)
            bot.start()
        }
        if (greet) {
            bots[0].say({ me.id }, "Salam! Fatima here on node 2. Your signal is strong on my side.", 5000)
            bots[2].say({ BROADCAST_ID }, "Node 4 is online. Can everyone hear me?", 11000)
        }
    }

    /** The demo position of the app's own node. */
    fun placeMe() {
        me.fromPhone(Protocol.pos(stations[0].lat, stations[0].lon))
    }

    fun stop() {
        nodes.forEach { it.stop() }
    }

    fun setPower(station: Int, on: Boolean) {
        if (station !in 2..nodes.size) return
        medium.setPower(station, on)
        if (on) nodes[station - 1].start() else nodes[station - 1].stop()
    }

    /** A pretend person on one of the other nodes. It talks to its node like a phone would. */
    private inner class Bot(val node: SoftNode, val lat: Double, val lon: Double, val answers: List<String>) {
        private var nextId = random.nextInt(1000, 30000)
        private var answered = 0

        fun start() {
            node.phone = { line -> onLine(line) }
            node.fromPhone(Protocol.hello(node.name))
            node.fromPhone(Protocol.pos(lat, lon))
        }

        private fun onLine(line: String) {
            if (!line.startsWith("RX|")) return
            val frame = Protocol.parseNodeFrame(line) as? NodeFrame.Rx ?: return
            if (frame.from != me.id) return // only answer the person using the app
            if (frame.to == BROADCAST_ID) {
                if (random.nextInt(100) < 45) say({ BROADCAST_ID }, "${node.name} here, heard you.", 900 + random.nextLong(0, 2500))
            } else {
                say({ frame.from }, answers[answered++ % answers.size], 1200 + random.nextLong(0, 1600))
            }
        }

        fun say(to: () -> Int, text: String, delayMs: Long) {
            timers.after(delayMs) { node.fromPhone(Protocol.msg(nextId++, to(), text)) }
        }
    }
}

/** Connects the app to node 1 of a fresh demo mesh. */
class SimLink(private val timers: Timers, private val myName: String, private val greet: Boolean) : NodeLink, DemoControls {
    private var mesh: SimMesh? = null
    private var closed = false

    override fun open(listener: LinkListener) {
        timers.after(500) {
            if (closed) return@after
            val created = SimMesh(timers, myName, greet)
            mesh = created
            created.me.phone = { line -> timers.after(1) { if (!closed) listener.onLinkLine(line) } }
            created.start()
            listener.onLinkUp()
            timers.after(50) { if (!closed) created.placeMe() }
        }
    }

    override fun send(line: String) {
        val target = mesh ?: return
        timers.after(1) { if (!closed) target.me.fromPhone(line) }
    }

    override fun close() {
        closed = true
        mesh?.stop()
        mesh = null
    }

    override fun setNodePower(id: Int, on: Boolean) {
        mesh?.setPower(id, on)
    }
}
