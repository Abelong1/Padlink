package com.meow.padlink

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import java.util.concurrent.Executors

/**
 * Registers this device as a standard Bluetooth HID gamepad.
 * The TV sees it like any physical controller (no TV app needed).
 */
@SuppressLint("MissingPermission")
class HidGamepad(private val ctx: Context, private val onStatus: (String) -> Unit) {

    companion object {
        const val REPORT_ID = 1

        // Bit order follows Linux BTN_GAMEPAD mapping used by Android
        const val A = 0; const val B = 1; const val X = 3; const val Y = 4
        const val L1 = 6; const val R1 = 7; const val L2 = 8; const val R2 = 9
        const val SELECT = 10; const val START = 11; const val HOME = 12

        // 16 buttons, 1 hat (D-pad), 4 axes (LX, LY, RX, RY)
        private val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01,
            0x85.toByte(), REPORT_ID.toByte(),
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
            0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07,
            0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
            0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x42,
            0x65, 0x00, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04,
            0x81.toByte(), 0x02,
            0xC0.toByte()
        )
    }

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null

    // Live state
    private var buttons = 0
    private var stickHat = 8 // 8 = centered (null state)
    private var dpadHat = 8
    private val hat get() = if (dpadHat != 8) dpadHat else stickHat
    private val axes = ByteArray(4)

    val isConnected get() = host != null
    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
            onStatus(if (registered) "Ready — pair from TV or tap a device" else "Not registered")
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { host = device; onStatus("Connected: ${device.name}") }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device) host = null
                    onStatus("Disconnected")
                }
            }
        }
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, report())
        }
    }

    fun start() {
        if (adapter == null) { onStatus("No Bluetooth"); return }
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "PadLink Gamepad", "Phone gamepad", "Meow",
                    BluetoothHidDevice.SUBCLASS1_NONE, DESCRIPTOR
                )
                hid?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
            }
            override fun onServiceDisconnected(profile: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun connect(device: BluetoothDevice) {
        onStatus("Connecting to ${device.name}…")
        hid?.connect(device)
    }

    fun stop() {
        host?.let { hid?.disconnect(it) }
        hid?.unregisterApp()
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
    }

    // ---- Input API ----
    fun setButton(bit: Int, down: Boolean) {
        buttons = if (down) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
        send()
    }

    /** dx, dy in -1..1 */
    fun setLeftStick(dx: Float, dy: Float) {
        axes[0] = toAxis(dx); axes[1] = toAxis(dy)
        stickHat = toHat(dx, dy)
        send()
    }

    /** D-pad: 0=N,2=E,4=S,6=W (diagonals allowed), 8=released */
    fun setDpad(h: Int) {
        if (h == dpadHat) return
        dpadHat = h
        send()
    }

    fun setRightStick(dx: Float, dy: Float) {
        axes[2] = toAxis(dx); axes[3] = toAxis(dy)
        send()
    }

    private fun toAxis(v: Float) = (v.coerceIn(-1f, 1f) * 127).toInt().toByte()

    // Hat: 0=N,1=NE,2=E ... 7=NW, 8=center. Also lets the stick work in TV menus.
    private fun toHat(dx: Float, dy: Float): Int {
        val t = 0.5f
        val up = dy < -t; val down = dy > t; val left = dx < -t; val right = dx > t
        return when {
            up && right -> 1; down && right -> 3; down && left -> 5; up && left -> 7
            up -> 0; right -> 2; down -> 4; left -> 6
            else -> 8
        }
    }

    private fun report() = byteArrayOf(
        (buttons and 0xFF).toByte(), (buttons shr 8 and 0xFF).toByte(),
        hat.toByte(), axes[0], axes[1], axes[2], axes[3]
    )

    private fun send() {
        val h = host ?: return
        hid?.sendReport(h, REPORT_ID, report())
    }
}
