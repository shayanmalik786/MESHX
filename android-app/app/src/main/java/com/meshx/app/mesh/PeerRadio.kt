package com.meshx.app.mesh

import kotlin.random.Random

/**
 * A "radio" made of direct connections to other phones nearby (Bluetooth or Wi-Fi).
 * A packet that is transmitted goes to every connected phone, like a radio broadcast.
 */
interface PeerRadio : Radio {
    /**
     * Starts looking for other phones and connecting to them.
     * @param name how this phone is announced to others
     * @param onPeers called with the number of connected phones whenever it changes
     * @param onError called once when the radio cannot work (permission missing, Bluetooth off)
     */
    fun start(name: String, onPeers: (Int) -> Unit, onError: (String) -> Unit)

    fun stop()
}

/**
 * Phone-to-phone mode: the app runs its own software node and uses nearby phones as the radio.
 * No hardware is needed. Phones forward messages for each other, so two phones that are out of
 * range can still talk through a third one between them.
 */
class PhoneLink(
    private val nodeId: Int,
    private val name: String,
    private val radio: PeerRadio,
    private val timers: Timers,
    private val battery: () -> Int,
) : NodeLink {
    private var node: SoftNode? = null
    private var closed = false

    override fun open(listener: LinkListener) {
        radio.start(
            name = "MeshX-$nodeId",
            onPeers = { count ->
                if (!closed) {
                    listener.onLinkNote(
                        when (count) {
                            0 -> "Looking for phones nearby"
                            1 -> "1 phone connected"
                            else -> "$count phones connected"
                        },
                    )
                }
            },
            onError = { reason ->
                if (!closed) {
                    close()
                    listener.onLinkDown(reason)
                }
            },
        )
        if (closed) return
        val created = SoftNode(
            id = nodeId,
            name = name,
            radio = radio,
            timers = timers,
            random = Random(System.nanoTime()),
            hardware = "Phone",
            battery = battery,
        )
        created.phone = { line -> timers.after(1) { if (!closed) listener.onLinkLine(line) } }
        node = created
        created.start()
        timers.after(1) {
            if (!closed) {
                listener.onLinkUp()
                listener.onLinkNote("Looking for phones nearby")
            }
        }
    }

    override fun send(line: String) {
        val target = node ?: return
        timers.after(1) { if (!closed) target.fromPhone(line) }
    }

    override fun close() {
        if (closed) return
        closed = true
        node?.stop()
        node = null
        radio.stop()
    }
}
