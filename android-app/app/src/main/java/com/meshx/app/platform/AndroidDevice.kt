package com.meshx.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.meshx.app.core.Blocker
import com.meshx.app.core.Device
import com.meshx.app.core.Need
import com.meshx.app.core.SystemPanel
import com.meshx.app.data.JsonStorage
import com.meshx.app.link.BleLink
import com.meshx.app.link.BleScanner
import com.meshx.app.link.NearbyRadio
import com.meshx.app.link.bluetoothAdapter
import com.meshx.app.mesh.CoroutineTimers
import com.meshx.app.mesh.NodeLink
import com.meshx.app.mesh.PhoneLink
import com.meshx.app.mesh.TcpLink
import com.meshx.app.mesh.Timers
import com.meshx.app.model.BleDevice
import com.meshx.app.model.LinkSpec
import com.meshx.app.model.SavedData
import kotlinx.coroutines.CoroutineScope
import java.net.Socket

/** Which Android permissions each feature needs. The rules changed with Android 12 and 13. */
object Permissions {
    /** What is shown to the user in the system dialog. */
    fun toRequest(need: Need): Array<String> = when (need) {
        Need.BleScan, Need.BleConnect ->
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        Need.PhoneMesh -> when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES,
                // Nearby Connections still checks location on new Android versions too.
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        Need.Location -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** True when everything the feature really needs has been granted. */
    fun granted(context: Context, need: Need): Boolean {
        val required = when (need) {
            // for the position, an approximate location is enough
            Need.Location -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> toRequest(need).filter { it != Manifest.permission.ACCESS_COARSE_LOCATION }
        }
        return required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}

/** The real phone behind the app logic: storage, Bluetooth, Wi-Fi, nearby phones and GPS. */
class AndroidDevice(private val app: Application, scope: CoroutineScope) : Device {
    override val timers: Timers = CoroutineTimers(scope)
    private val main = Handler(Looper.getMainLooper())
    private val storage = JsonStorage(app)
    private val scanner = BleScanner(app)
    private var locationListener: LocationListener? = null

    override fun now(): Long = System.currentTimeMillis()

    override fun load(): SavedData? = storage.load()

    override fun save(data: SavedData) = storage.save(data)

    override fun openLink(spec: LinkSpec, myName: String): NodeLink = when (spec) {
        is LinkSpec.Ble -> BleLink(app, spec.address)
        is LinkSpec.Wifi -> TcpLink(spec.host, spec.port, post = { block -> main.post { block() } }, newSocket = { wifiSocket() })
        is LinkSpec.Phone -> PhoneLink(spec.nodeId, myName, NearbyRadio(app, spec.star), timers) { batteryPercent() }
        LinkSpec.Demo -> throw IllegalArgumentException("The demo link is created by the app logic")
    }

    override fun problem(need: Need): Blocker? {
        if (!Permissions.granted(app, need)) {
            return Blocker("A permission is missing. Allow it in the app settings.", SystemPanel.AppDetails)
        }
        val bluetoothOn = bluetoothAdapter(app)?.isEnabled == true
        return when (need) {
            Need.BleScan, Need.BleConnect -> when {
                !bluetoothOn -> Blocker("Bluetooth is off. Switch it on to reach your node.", SystemPanel.Bluetooth)
                need == Need.BleScan && Build.VERSION.SDK_INT < 31 && !locationOn() ->
                    Blocker("Location is off. This Android version only finds Bluetooth devices while Location is on.", SystemPanel.Location)
                else -> null
            }
            Need.PhoneMesh -> when {
                !bluetoothOn -> Blocker("Bluetooth is off. Switch it on so phones can find each other.", SystemPanel.Bluetooth)
                Build.VERSION.SDK_INT < 33 && !locationOn() ->
                    Blocker("Location is off. This Android version only finds nearby phones while Location is on.", SystemPanel.Location)
                else -> null
            }
            Need.Location -> if (locationOn()) null else Blocker("Location is off. Switch it on to share your position.", SystemPanel.Location)
        }
    }

    override fun startBleScan(onFound: (BleDevice) -> Unit, onError: (String) -> Unit) = scanner.start(onFound, onError)

    override fun stopBleScan() = scanner.stop()

    private fun locationManager() = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun locationOn(): Boolean {
        val manager = locationManager() ?: return false
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: RuntimeException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun startLocation(onFix: (lat: Double, lon: Double) -> Unit): Boolean {
        stopLocation()
        val manager = locationManager() ?: return false
        // All four functions are written out because Android 10 and older call them and crash when one is missing.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.latitude, location.longitude)

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        var started = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!manager.isProviderEnabled(provider)) continue
                manager.requestLocationUpdates(provider, 10_000L, 10f, listener, Looper.getMainLooper())
                manager.getLastKnownLocation(provider)?.let { onFix(it.latitude, it.longitude) }
                started = true
            } catch (e: RuntimeException) {
                // provider missing on this phone, or the permission was taken away
            }
        }
        if (started) locationListener = listener
        return started
    }

    override fun stopLocation() {
        val listener = locationListener ?: return
        locationListener = null
        try {
            locationManager()?.removeUpdates(listener)
        } catch (e: RuntimeException) {
            // nothing to remove
        }
    }

    private fun batteryPercent(): Int {
        val manager = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else -1
    }

    /**
     * A node's Wi-Fi has no internet, so Android prefers mobile data for new connections.
     * Taking the socket from the Wi-Fi network itself makes sure it really goes to the node.
     */
    @Suppress("DEPRECATION")
    private fun wifiSocket(): Socket {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Socket()
        return try {
            val wifi = manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            wifi?.socketFactory?.createSocket() ?: Socket()
        } catch (e: Exception) {
            Socket()
        }
    }
}
