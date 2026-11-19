package com.meshx.app.link

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.meshx.app.mesh.PeerRadio

/**
 * Phone-to-phone radio built on Google Nearby Connections. It works without internet and picks
 * Bluetooth, Bluetooth LE or Wi-Fi by itself. Every phone advertises and searches at the same
 * time and connects to every MeshX phone it finds.
 *
 * @param star false = many-to-many over Bluetooth (default). true = one hub with Wi-Fi Direct speed.
 */
class NearbyRadio(context: Context, star: Boolean) : PeerRadio {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = if (star) Strategy.P2P_STAR else Strategy.P2P_CLUSTER
    private val connected = HashSet<String>()
    private val asked = HashSet<String>()
    private var myName = ""
    private var running = false
    private var onPeers: (Int) -> Unit = {}
    private var onError: (String) -> Unit = {}

    override var onReceive: ((bytes: ByteArray, rssi: Int, snr: Float) -> Unit)? = null

    override fun start(name: String, onPeers: (Int) -> Unit, onError: (String) -> Unit) {
        myName = name
        this.onPeers = onPeers
        this.onError = onError
        // Clear anything left over from an earlier start, otherwise Nearby answers "already advertising".
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected.clear()
        asked.clear()
        running = true
        client.startAdvertising(name, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { failed(it) }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { failed(it) }
    }

    private fun failed(cause: Exception) {
        // 8001 = already advertising, 8002 = already discovering: Nearby is running, which is what we want.
        val code = (cause as? ApiException)?.statusCode
        if (code == 8001 || code == 8002) return
        if (!running) return
        running = false
        onError("Phone-to-phone mode could not start. Check that Bluetooth, Wi-Fi and Location are on. (${cause.message ?: "unknown error"})")
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!running || endpointId in connected || endpointId in asked) return
            // Only one of two phones asks, the one whose name sorts first. Otherwise both ask at once and both fail.
            if (myName <= info.endpointName) {
                asked.add(endpointId)
                client.requestConnection(myName, endpointId, lifecycle).addOnFailureListener { asked.remove(endpointId) }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            asked.remove(endpointId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (running) client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            asked.remove(endpointId)
            if (running && result.status.isSuccess) {
                connected.add(endpointId)
                onPeers(connected.size)
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connected.remove(endpointId) && running) onPeers(connected.size)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (running) onReceive?.invoke(bytes, 0, 0f)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    override fun transmit(bytes: ByteArray) {
        if (running && connected.isNotEmpty()) client.sendPayload(connected.toList(), Payload.fromBytes(bytes))
    }

    override fun stop() {
        running = false
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected.clear()
        asked.clear()
    }

    private companion object {
        const val SERVICE_ID = "com.meshx.app.mesh"
    }
}
