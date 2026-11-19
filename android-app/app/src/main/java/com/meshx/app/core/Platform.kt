package com.meshx.app.core

import com.meshx.app.mesh.NodeLink
import com.meshx.app.mesh.Timers
import com.meshx.app.model.BleDevice
import com.meshx.app.model.LinkSpec
import com.meshx.app.model.SavedData

/** Things the app needs the phone's permission for. */
enum class Need { BleScan, BleConnect, PhoneMesh, Location }

enum class SystemPanel { Bluetooth, Location, Wifi, AppDetails }

/**
 * Everything the app logic needs from the phone that lives as long as the app does.
 * The real one is AndroidDevice. Tests use a fake, so the whole app logic runs without a phone.
 */
interface Device {
    val timers: Timers

    /** Wall clock in milliseconds, used for message times. */
    fun now(): Long

    fun load(): SavedData?
    fun save(data: SavedData)

    /** Opens a Bluetooth, Wi-Fi or phone-to-phone link. The demo link is made by the app itself. */
    fun openLink(spec: LinkSpec, myName: String): NodeLink

    /** Returns what blocks this feature right now (Bluetooth is off...), or null. */
    fun problem(need: Need): Blocker?

    fun startBleScan(onFound: (BleDevice) -> Unit, onError: (String) -> Unit)
    fun stopBleScan()

    /** Starts GPS updates. Returns false when location is not available. */
    fun startLocation(onFix: (lat: Double, lon: Double) -> Unit): Boolean
    fun stopLocation()
}

/**
 * Things only a visible screen can do. The activity implements this and answers through the
 * matching AppController functions (onPermission, onQrScanned, onFirmwarePicked).
 */
interface Host {
    fun requestPermission(need: Need)
    fun scanQr()
    fun pickFirmware()
    fun shareText(text: String)
    fun openPanel(panel: SystemPanel)
}
