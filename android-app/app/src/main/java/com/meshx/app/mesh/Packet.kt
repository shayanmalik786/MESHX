package com.meshx.app.mesh

import com.meshx.app.model.RoutingMode

enum class PacketKind(val code: String, val label: String) {
    Beacon("B", "BEACON"),
    Msg("M", "MSG"),
    Ack("A", "ACK"),
    Ping("P", "PING"),
    Pong("Q", "PONG");

    companion object {
        fun fromCode(code: String): PacketKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One packet as it travels between nodes (over LoRa, or between phones).
 *
 * The app keeps packets as text so they are easy to read while debugging:
 *   kind|src|dst|id|attempt|ttl|hops|via|next|mode|body
 * A LoRa firmware would pack the same header fields into a few bytes.
 */
data class Packet(
    val kind: PacketKind,
    val src: Int,               // node that created the packet
    val dst: Int,               // final destination, 0 = everyone
    val id: Int,                // message id, or the beacon number
    val attempt: Int = 1,       // 1 for the first try, 2 and 3 for retries
    val ttl: Int,               // transmissions left, the packet dies at 0
    val hops: Int = 1,          // transmissions so far, including this one
    val via: Int,               // node that transmitted this copy
    val next: Int = 0,          // node that should forward it, 0 = any node (flooding)
    val mode: RoutingMode = RoutingMode.Adaptive,
    val body: String = "",
) {
    fun encode(): ByteArray =
        "${kind.code}|$src|$dst|$id|$attempt|$ttl|$hops|$via|$next|${mode.key.first()}|$body".toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): Packet? = try {
            val parts = String(bytes, Charsets.UTF_8).split('|', limit = 11)
            if (parts.size != 11) {
                null
            } else {
                val kind = PacketKind.fromCode(parts[0])
                val mode = RoutingMode.entries.firstOrNull { it.key.startsWith(parts[9]) && parts[9].length == 1 }
                if (kind == null || mode == null) {
                    null
                } else {
                    Packet(
                        kind = kind, src = parts[1].toInt(), dst = parts[2].toInt(), id = parts[3].toInt(),
                        attempt = parts[4].toInt(), ttl = parts[5].toInt(), hops = parts[6].toInt(),
                        via = parts[7].toInt(), next = parts[8].toInt(), mode = mode, body = parts[10],
                    )
                }
            }
        } catch (e: RuntimeException) {
            null
        }
    }
}

/**
 * The radio as a node sees it: whatever is transmitted is heard by every node in range.
 * LoRa works like this, and so does a group of phones connected to each other.
 */
interface Radio {
    fun transmit(bytes: ByteArray)

    /** Called for every packet heard. rssi is in dBm (0 = unknown), snr in dB. */
    var onReceive: ((bytes: ByteArray, rssi: Int, snr: Float) -> Unit)?
}
