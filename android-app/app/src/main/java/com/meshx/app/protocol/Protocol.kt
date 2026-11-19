package com.meshx.app.protocol

import com.meshx.app.model.MAX_NODE_ID
import com.meshx.app.model.RoutingMode
import java.util.Locale

/**
 * MeshX line protocol, version 2, spoken between the phone and one node.
 *
 * Every frame is one line of UTF-8 text that ends with '\n'. Fields are separated by '|'
 * and free text is always the last field, so it may contain '|' itself.
 * The same lines travel over Bluetooth LE, over Wi-Fi (TCP) and inside the app
 * (demo mesh, phone-to-phone node). See docs/protocol.md for the full table.
 */
object Protocol {
    const val VERSION = 2
    const val MAX_TEXT_BYTES = 160
    const val MAX_NAME_CHARS = 20
    const val MAX_LINE_BYTES = 1024
    const val TCP_PORT = 4403

    // ---------------------------------------------------------------- phone -> node

    fun hello(name: String) = "HELLO|$VERSION|${cleanName(name)}"
    fun msg(msgId: Int, to: Int, text: String) = "MSG|$msgId|$to|${cleanText(text)}"
    fun set(key: String, value: String) = "SET|$key|${oneLine(value)}"
    fun get(key: String = "*") = "GET|$key"
    fun pos(lat: Double, lon: Double) = "POS|${coord(lat)}|${coord(lon)}"
    fun ping(id: Int, dst: Int) = "PING|$id|$dst"
    fun otaBegin(size: Int, crc: Long, label: String) = "OTA|BEGIN|$size|${crcHex(crc)}|${oneLine(label)}"
    fun otaData(seq: Int, chunk: ByteArray) = "OTA|DATA|$seq|${Base64.encode(chunk)}"
    const val OTA_END = "OTA|END"
    const val OTA_ABORT = "OTA|ABORT"
    const val REBOOT = "REBOOT"

    // ---------------------------------------------------------------- node -> phone

    fun me(id: Int, battery: Int, fw: String, name: String) = "ME|$id|$battery|$fw|${cleanName(name)}"
    const val READY = "READY"
    fun sent(msgId: Int) = "SENT|$msgId"
    fun ack(msgId: Int, hops: Int, rttMs: Int) = "ACK|$msgId|$hops|$rttMs"
    fun fail(msgId: Int, reason: String) = "FAIL|$msgId|$reason"
    fun rx(msgId: Int, from: Int, to: Int, hops: Int, rssi: Int, snr: Float, text: String) =
        "RX|$msgId|$from|$to|$hops|$rssi|${one(snr)}|$text"

    fun node(
        id: Int, rssi: Int, snr: Float, battery: Int, loss: Int, score: Int,
        hops: Int, nextHop: Int, lastSeenSec: Long, name: String,
    ) = "NODE|$id|$rssi|${one(snr)}|$battery|$loss|$score|$hops|$nextHop|$lastSeenSec|${cleanName(name)}"

    fun link(a: Int, b: Int, score: Int) = "LINK|$a|$b|$score"
    fun route(dst: Int, path: List<Int>) = "ROUTE|$dst|${path.joinToString(",")}"
    fun routeFlood(dst: Int) = "ROUTE|$dst|*"
    fun posOf(id: Int, lat: Double, lon: Double, ageSec: Long) = "POS|$id|${coord(lat)}|${coord(lon)}|$ageSec"
    fun stat(id: Int, uptimeSec: Long, tx: Int, rx: Int, fwd: Int, drop: Int, phone: Boolean, fw: String) =
        "STAT|$id|$uptimeSec|$tx|$rx|$fwd|$drop|${if (phone) 1 else 0}|$fw"

    fun pkt(kind: String, src: Int, dst: Int, via: Int, hops: Int, rssi: Int, size: Int) =
        "PKT|$kind|$src|$dst|$via|$hops|$rssi|$size"

    fun cfg(key: String, value: String) = "CFG|$key|$value"
    fun ok(what: String) = "OK|$what"
    fun err(what: String, reason: String) = "ERR|$what|$reason"
    fun pong(id: Int, rttMs: Int, hops: Int) = "PONG|$id|$rttMs|$hops"
    fun otaReady(chunk: Int) = "OTA|READY|$chunk"
    fun otaAck(seq: Int) = "OTA|ACK|$seq"
    const val OTA_DONE = "OTA|DONE"
    fun otaErr(reason: String) = "OTA|ERR|$reason"

    // ---------------------------------------------------------------- parsing

    /** Parses a line sent by a node. Returns null for anything that is not a valid frame. */
    fun parseNodeFrame(line: String): NodeFrame? = try {
        when (line.substringBefore('|')) {
            "ME" -> fields(line, 5)?.let { NodeFrame.Me(it[1].toInt(), it[2].toInt(), it[3], it[4]) }
            "READY" -> NodeFrame.Ready
            "SENT" -> fields(line, 2)?.let { NodeFrame.Sent(it[1].toInt()) }
            "ACK" -> fields(line, 4)?.let { NodeFrame.Ack(it[1].toInt(), it[2].toInt(), it[3].toInt()) }
            "FAIL" -> fields(line, 3)?.let { NodeFrame.Fail(it[1].toInt(), it[2]) }
            "RX" -> fields(line, 8)?.let {
                NodeFrame.Rx(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt(), it[5].toInt(), it[6].toFloat(), it[7])
            }
            "NODE" -> fields(line, 11)?.let {
                NodeFrame.Node(
                    id = it[1].toInt(), rssi = it[2].toInt(), snr = it[3].toFloat(), battery = it[4].toInt(),
                    loss = it[5].toInt(), score = it[6].toInt(), hops = it[7].toInt(), nextHop = it[8].toInt(),
                    lastSeenSec = it[9].toLong(), name = it[10],
                )
            }
            "LINK" -> fields(line, 4)?.let { NodeFrame.Link(it[1].toInt(), it[2].toInt(), it[3].toInt()) }
            "ROUTE" -> fields(line, 3)?.let {
                val flood = it[2] == "*"
                val path = if (flood || it[2].isEmpty()) emptyList() else it[2].split(',').map { id -> id.toInt() }
                NodeFrame.Route(it[1].toInt(), path, flood)
            }
            "POS" -> fields(line, 5)?.let { NodeFrame.Pos(it[1].toInt(), it[2].toDouble(), it[3].toDouble(), it[4].toLong()) }
            "STAT" -> fields(line, 9)?.let {
                NodeFrame.Stat(
                    it[1].toInt(), it[2].toLong(), it[3].toInt(), it[4].toInt(), it[5].toInt(), it[6].toInt(), it[7] == "1", it[8],
                )
            }
            "PKT" -> fields(line, 8)?.let {
                NodeFrame.Pkt(it[1], it[2].toInt(), it[3].toInt(), it[4].toInt(), it[5].toInt(), it[6].toInt(), it[7].toInt())
            }
            "CFG" -> fields(line, 3)?.let { NodeFrame.Cfg(it[1], it[2]) }
            "OK" -> fields(line, 2)?.let { NodeFrame.Ok(it[1]) }
            "ERR" -> fields(line, 3)?.let { NodeFrame.Err(it[1], it[2]) }
            "PONG" -> fields(line, 4)?.let { NodeFrame.Pong(it[1].toInt(), it[2].toInt(), it[3].toInt()) }
            "OTA" -> {
                val parts = line.split('|', limit = 3)
                when (parts.getOrNull(1)) {
                    "READY" -> NodeFrame.OtaReady(parts[2].toInt())
                    "ACK" -> NodeFrame.OtaAck(parts[2].toInt())
                    "DONE" -> NodeFrame.OtaDone
                    "ERR" -> NodeFrame.OtaErr(parts.getOrElse(2) { "error" })
                    else -> null
                }
            }
            else -> null
        }
    } catch (e: RuntimeException) {
        null
    }

    /** Parses a line sent by the phone. Used by the software node and the demo mesh. */
    fun parsePhoneFrame(line: String): PhoneFrame? = try {
        when (line.substringBefore('|')) {
            "HELLO" -> fields(line, 3)?.let { PhoneFrame.Hello(it[1].toInt(), it[2]) }
            "MSG" -> fields(line, 4)?.let { PhoneFrame.Msg(it[1].toInt(), it[2].toInt(), it[3]) }
            "SET" -> fields(line, 3)?.let { PhoneFrame.Set(it[1], it[2]) }
            "GET" -> fields(line, 2)?.let { PhoneFrame.Get(it[1]) }
            "POS" -> fields(line, 3)?.let { PhoneFrame.Pos(it[1].toDouble(), it[2].toDouble()) }
            "PING" -> fields(line, 3)?.let { PhoneFrame.Ping(it[1].toInt(), it[2].toInt()) }
            "REBOOT" -> PhoneFrame.Reboot
            "OTA" -> {
                val parts = line.split('|', limit = 5)
                when (parts.getOrNull(1)) {
                    "BEGIN" -> PhoneFrame.OtaBegin(parts[2].toInt(), parts[3].toLong(16), parts.getOrElse(4) { "" })
                    "DATA" -> Base64.decode(parts[3])?.let { PhoneFrame.OtaData(parts[2].toInt(), it) }
                    "END" -> PhoneFrame.OtaEnd
                    "ABORT" -> PhoneFrame.OtaAbort
                    else -> null
                }
            }
            else -> null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun fields(line: String, count: Int): List<String>? {
        val parts = line.split('|', limit = count)
        return if (parts.size == count) parts else null
    }

    // ---------------------------------------------------------------- text helpers

    /** A message must fit in one line and in MAX_TEXT_BYTES bytes of UTF-8. */
    fun cleanText(text: String): String = truncateUtf8(oneLine(text), MAX_TEXT_BYTES)

    fun oneLine(text: String): String =
        text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()

    /** Names travel inside beacons, so the separators used there are removed. */
    fun cleanName(name: String): String =
        oneLine(name).filter { it != '|' && it != ';' && it != ',' && it >= ' ' }.trim().take(MAX_NAME_CHARS)

    fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            bytes += utf8Size(cp)
            i += Character.charCount(cp)
        }
        return bytes
    }

    /** Cuts the text so that it needs at most maxBytes bytes, without splitting a character. */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = utf8Size(cp)
            if (bytes + size > maxBytes) break
            bytes += size
            i += Character.charCount(cp)
        }
        return text.substring(0, i)
    }

    private fun utf8Size(codePoint: Int) = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    fun coord(value: Double): String = String.format(Locale.US, "%.5f", value)
    fun one(value: Float): String = String.format(Locale.US, "%.1f", value)
    fun crcHex(crc: Long): String = String.format(Locale.US, "%08x", crc)
}

/** Frames a node sends to the phone. */
sealed class NodeFrame {
    data class Me(val id: Int, val battery: Int, val fw: String, val name: String) : NodeFrame()
    object Ready : NodeFrame()
    data class Sent(val msgId: Int) : NodeFrame()
    data class Ack(val msgId: Int, val hops: Int, val rttMs: Int) : NodeFrame()
    data class Fail(val msgId: Int, val reason: String) : NodeFrame()
    data class Rx(
        val msgId: Int, val from: Int, val to: Int, val hops: Int, val rssi: Int, val snr: Float, val text: String,
    ) : NodeFrame()

    data class Node(
        val id: Int, val rssi: Int, val snr: Float, val battery: Int, val loss: Int, val score: Int,
        val hops: Int, val nextHop: Int, val lastSeenSec: Long, val name: String,
    ) : NodeFrame()

    data class Link(val a: Int, val b: Int, val score: Int) : NodeFrame()
    data class Route(val dst: Int, val path: List<Int>, val flood: Boolean) : NodeFrame()
    data class Pos(val id: Int, val lat: Double, val lon: Double, val ageSec: Long) : NodeFrame()
    data class Stat(
        val id: Int, val uptimeSec: Long, val tx: Int, val rx: Int, val fwd: Int, val drop: Int,
        val phone: Boolean, val fw: String,
    ) : NodeFrame()

    data class Pkt(
        val kind: String, val src: Int, val dst: Int, val via: Int, val hops: Int, val rssi: Int, val size: Int,
    ) : NodeFrame()

    data class Cfg(val key: String, val value: String) : NodeFrame()
    data class Ok(val what: String) : NodeFrame()
    data class Err(val what: String, val reason: String) : NodeFrame()
    data class Pong(val id: Int, val rttMs: Int, val hops: Int) : NodeFrame()
    data class OtaReady(val chunk: Int) : NodeFrame()
    data class OtaAck(val seq: Int) : NodeFrame()
    object OtaDone : NodeFrame()
    data class OtaErr(val reason: String) : NodeFrame()
}

/** Frames the phone sends to a node. */
sealed class PhoneFrame {
    data class Hello(val proto: Int, val name: String) : PhoneFrame()
    data class Msg(val msgId: Int, val to: Int, val text: String) : PhoneFrame()
    data class Set(val key: String, val value: String) : PhoneFrame()
    data class Get(val key: String) : PhoneFrame()
    data class Pos(val lat: Double, val lon: Double) : PhoneFrame()
    data class Ping(val id: Int, val dst: Int) : PhoneFrame()
    data class OtaBegin(val size: Int, val crc: Long, val label: String) : PhoneFrame()
    class OtaData(val seq: Int, val data: ByteArray) : PhoneFrame()
    object OtaEnd : PhoneFrame()
    object OtaAbort : PhoneFrame()
    object Reboot : PhoneFrame()
}

/** Node settings that can be read with GET and changed with SET. */
object ConfigKeys {
    const val ID = "id"
    const val ROUTE = "route"
    const val SF = "sf"
    const val BW = "bw"
    const val POWER = "power"
    const val WIFI = "wifi"
    const val WIFI_SSID = "wifi_ssid"
    const val WIFI_PASS = "wifi_pass"
    const val NET_KEY = "net_key"

    // read only
    const val FREQ = "freq"
    const val FW = "fw"
    const val HW = "hw"
    const val OTA = "ota"

    val readOnly = setOf(FREQ, FW, HW, OTA)

    /** Returns null when the value is fine, otherwise a short reason. The node and the app share this check. */
    fun problem(key: String, value: String): String? = when (key) {
        ID -> value.toIntOrNull().let { if (it == null || it !in 1..MAX_NODE_ID) "Use a number from 1 to $MAX_NODE_ID" else null }
        ROUTE -> if (RoutingMode.fromKey(value) == null) "Use adaptive, shortest or flood" else null
        SF -> value.toIntOrNull().let { if (it == null || it !in 7..12) "Spreading factor is 7 to 12" else null }
        BW -> if (value !in setOf("125", "250", "500")) "Bandwidth is 125, 250 or 500 kHz" else null
        POWER -> value.toIntOrNull().let { if (it == null || it !in 2..20) "Power is 2 to 20 dBm" else null }
        WIFI -> if (value != "on" && value != "off") "Use on or off" else null
        WIFI_SSID -> when {
            value.isEmpty() -> "The name cannot be empty"
            Protocol.utf8Length(value) > 32 -> "At most 32 characters"
            value.contains('|') -> "The name cannot contain |"
            else -> null
        }
        WIFI_PASS -> when {
            value.isEmpty() -> null // open network
            value.length < 8 -> "At least 8 characters"
            value.length > 63 -> "At most 63 characters"
            else -> null
        }
        NET_KEY -> when {
            value.length < 8 -> "At least 8 characters"
            value.length > 32 -> "At most 32 characters"
            value.contains('|') || value.contains('&') -> "The key cannot contain | or &"
            else -> null
        }
        in readOnly -> "This value cannot be changed"
        else -> "Unknown setting"
    }
}

/** Collects bytes from a stream and hands out complete lines. */
class LineBuffer(private val maxBytes: Int = 4096) {
    private var buffer = ByteArray(256)
    private var length = 0
    private var overflow = false

    fun feed(data: ByteArray, count: Int = data.size, onLine: (String) -> Unit) {
        for (i in 0 until count) {
            val b = data[i]
            if (b == NEWLINE) {
                var end = length
                if (end > 0 && buffer[end - 1] == RETURN) end--
                if (!overflow && end > 0) onLine(String(buffer, 0, end, Charsets.UTF_8))
                length = 0
                overflow = false
            } else if (!overflow) {
                if (length == maxBytes) {
                    overflow = true // line is too long: drop it up to the next newline
                    length = 0
                } else {
                    if (length == buffer.size) buffer = buffer.copyOf(minOf(buffer.size * 2, maxBytes))
                    buffer[length++] = b
                }
            }
        }
    }

    fun clear() {
        length = 0
        overflow = false
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val RETURN = '\r'.code.toByte()
    }
}

/** Small Base64 codec, so the same code runs on every Android version and in unit tests. */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < data.size) ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < data.size) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Returns null when the text is not valid Base64. */
    fun decode(text: String): ByteArray? {
        if (text.length % 4 != 0) return null
        val padding = when {
            text.endsWith("==") -> 2
            text.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArray(text.length / 4 * 3 - padding)
        var o = 0
        var i = 0
        while (i < text.length) {
            var chunk = 0
            for (j in 0 until 4) {
                val c = text[i + j]
                val v = if (c == '=') {
                    if (i + j < text.length - padding) return null
                    0
                } else {
                    ALPHABET.indexOf(c).also { if (it < 0) return null }
                }
                chunk = (chunk shl 6) or v
            }
            if (o < out.size) out[o++] = (chunk shr 16).toByte()
            if (o < out.size) out[o++] = (chunk shr 8).toByte()
            if (o < out.size) out[o++] = chunk.toByte()
            i += 4
        }
        return out
    }
}
