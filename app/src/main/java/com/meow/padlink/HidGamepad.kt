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
            0xC0.toByte(),
            // Report 2: Consumer Control (what real TV remotes send) — one 16-bit usage
            0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01,
            0x85.toByte(), 0x02, // report ID = CONSUMER_ID
            0x15, 0x00, 0x26, 0xFF.toByte(), 0x03,
            0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03,
            0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00,
            0xC0.toByte(),
            // Report 3: Keyboard — modifier byte, reserved, 6 keys
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x85.toByte(), 0x03, // report ID = KEYBOARD_ID
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte()
        )

        const val KEYBOARD_ID = 3
        const val KEY_ENTER = 0x28; const val KEY_BACKSPACE = 0x2A
        private const val SHIFT = 0x02

        /** US layout: char → (keycode, needsShift) */
        private val KEYMAP: Map<Char, Pair<Int, Boolean>> = HashMap<Char, Pair<Int, Boolean>>().apply {
            for (i in 0 until 26) {
                put('a' + i, (0x04 + i) to false); put('A' + i, (0x04 + i) to true)
            }
            "1234567890".forEachIndexed { i, c -> put(c, (0x1E + i) to false) }
            "!@#$%^&*()".forEachIndexed { i, c -> put(c, (0x1E + i) to true) }
            put('\n', 0x28 to false); put('\t', 0x2B to false); put(' ', 0x2C to false)
            val plain = "-=[]\\;'`,./"; val shifted = "_+{}|:\"~<>?"
            val codes = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
            for (i in codes.indices) { put(plain[i], codes[i] to false); put(shifted[i], codes[i] to true) }
        }
    }

    private val typer = Executors.newSingleThreadExecutor()

    /** Type text on the TV. Unsupported characters (emoji, accents) are skipped. */
    fun typeText(text: String) = typer.execute {
        for (c in text) KEYMAP[c]?.let { (code, shift) -> tap(code, if (shift) SHIFT else 0) }
    }

    fun tapKey(code: Int) = typer.execute { tap(code, 0) }

    private fun tap(code: Int, mod: Int) {
        val h = host ?: return
        hid?.sendReport(h, KEYBOARD_ID, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
        Thread.sleep(12)
        hid?.sendReport(h, KEYBOARD_ID, ByteArray(8))
        Thread.sleep(12)
    }

        const val CONSUMER_ID = 2
        // Consumer usages → Android keys
        const val CC_OK = 0x41      // Menu Pick → DPAD_CENTER
        const val CC_UP = 0x42; const val CC_DOWN = 0x43
        const val CC_LEFT = 0x44; const val CC_RIGHT = 0x45
        const val CC_MENU = 0x40
        const val CC_HOME = 0x223   // AC Home
        const val CC_BACK = 0x224   // AC Back
        const val CC_VOL_UP = 0xE9; const val CC_VOL_DOWN = 0xEA
    }

    /** TV mode: D-pad/A/B act like a TV remote. Game mode: everything is gamepad. */
    @Volatile var tvMode = true
    private var consumerKey = 0

    fun remoteKey(usage: Int, down: Boolean) {
        if (down) consumerKey = usage else if (consumerKey == usage) consumerKey = 0 else return
        val h = host ?: return
        hid?.sendReport(h, CONSUMER_ID, byteArrayOf((consumerKey and 0xFF).toByte(), (consumerKey shr 8).toByte()))
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
        val remote = when {
            bit == HOME -> CC_HOME
            tvMode && bit == A -> CC_OK
            tvMode && bit == B -> CC_BACK
            tvMode && bit == START -> CC_MENU
            else -> 0
        }
        if (remote != 0) { remoteKey(remote, down); return }
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
        if (tvMode) {
            val key = when (h) { 0 -> CC_UP; 2 -> CC_RIGHT; 4 -> CC_DOWN; 6 -> CC_LEFT; else -> 0 }
            if (h == 8) remoteKey(consumerKey, false)
            else if (key != 0) remoteKey(key, true)   // diagonals keep the last direction
            dpadHat = h
            return
        }
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
