package com.busys8.engine.system

/** ble.proto ServiceStatus, with the API's names for it (api_ble.c ble_status_names). */
enum class BleStatus(val proto: Int, val api: String) {
    READY(2, "disabled"),
    ADVERTISING(3, "enabled"),
    CONNECTABLE(4, "connectable"),
    CONNECTED(5, "connected"),
    ERROR(6, "internal error"),
}

/**
 * The Bluetooth service as the API and the status stream see it. The host implements it (the
 * phone's GATT server); [NoBle] stands in where there is no radio, such as on the desktop.
 */
interface BleControl {
    val status: BleStatus

    /** The connected peer's address, while [status] is CONNECTED. */
    val peerAddress: String?

    /** ble_start / ble_stop / ble_forget; false when the radio refuses. */
    fun enable(): Boolean
    fun disable(): Boolean
    fun forget(): Boolean
}

object NoBle : BleControl {
    override val status = BleStatus.READY
    override val peerAddress: String? = null
    override fun enable() = false
    override fun disable() = true
    override fun forget() = false
}
