package com.meshx.app.mesh

import com.meshx.app.protocol.LineBuffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * A connection from the app to one node. Every transport (Bluetooth, Wi-Fi, the in-app phone
 * node and the demo mesh) looks the same from here: lines of the MeshX protocol go in and out.
 * All listener calls arrive on the main thread.
 */
interface NodeLink {
    fun open(listener: LinkListener)

    /** Sends one protocol line, without the trailing newline. */
    fun send(line: String)

    /** Closes the link. The listener is not called again after this. */
    fun close()
}

interface LinkListener {
    /** The transport is up. The app now sends HELLO. */
    fun onLinkUp()

    fun onLinkLine(line: String)

    /** The link failed or the other side closed it. */
    fun onLinkDown(reason: String)

    /** Extra status for the user, for example "2 phones connected". */
    fun onLinkNote(note: String)
}

/** Extra controls that only the demo mesh has. */
interface DemoControls {
    fun setNodePower(id: Int, on: Boolean)
}

/**
 * Wi-Fi link: a plain TCP socket to the node, which runs a small server on its access point
 * (192.168.4.1, port 4403 by default).
 *
 * @param post runs a block on the main thread
 * @param newSocket lets Android hand out a socket that is tied to the Wi-Fi network
 */
class TcpLink(
    private val host: String,
    private val port: Int,
    private val post: (() -> Unit) -> Unit,
    private val newSocket: () -> Socket = { Socket() },
) : NodeLink {
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    private val outbox = LinkedBlockingQueue<String>()

    override fun open(listener: LinkListener) {
        thread(name = "meshx-tcp-read") {
            try {
                val s = newSocket()
                socket = s
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                if (closed) {
                    s.close()
                    return@thread
                }
                post { if (!closed) listener.onLinkUp() }
                thread(name = "meshx-tcp-write") { writeLoop(s, listener) }

                val lines = LineBuffer()
                val chunk = ByteArray(2048)
                val input = s.getInputStream()
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    lines.feed(chunk, count) { line -> post { if (!closed) listener.onLinkLine(line) } }
                }
                fail(listener, "The node closed the connection")
            } catch (e: IOException) {
                fail(listener, "Could not reach $host:$port (${e.message ?: "no answer"})")
            } catch (e: RuntimeException) {
                fail(listener, "Could not reach $host:$port (${e.message ?: "error"})")
            }
        }
    }

    private fun writeLoop(s: Socket, listener: LinkListener) {
        try {
            val output = s.getOutputStream()
            while (!closed) {
                val line = outbox.take()
                if (line === STOP) break
                output.write((line + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (e: IOException) {
            fail(listener, "Connection lost")
        } catch (e: InterruptedException) {
            // closing
        }
    }

    private fun fail(listener: LinkListener, reason: String) {
        if (closed) return
        shutdown()
        post { listener.onLinkDown(reason) }
    }

    override fun send(line: String) {
        if (!closed) outbox.offer(line)
    }

    override fun close() {
        if (closed) return
        shutdown()
    }

    private fun shutdown() {
        closed = true
        outbox.offer(STOP)
        val s = socket
        // Closing a socket is network work, so it is done off the main thread.
        thread(name = "meshx-tcp-close") {
            try {
                s?.close()
            } catch (e: IOException) {
                // already closed
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6000
        val STOP = String(charArrayOf('\u0000'))
    }
}
