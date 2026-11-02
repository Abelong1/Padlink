package com.meow.padlink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var pad: HidGamepad
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply { setTextColor(Color.parseColor("#F5E6D3")); textSize = 14f }
        pad = PadService.obtain(this)
        pad.listener = { msg -> runOnUiThread { status.text = msg } }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
            addView(status)
            addView(Button(context).apply { text = "Pair"; setOnClickListener { makeDiscoverable() } })
            addView(Button(context).apply { text = "Connect"; setOnClickListener { pickDevice() } })
            addView(Button(context).apply {
                text = if (pad.tvMode) "Mode: TV" else "Mode: GAME"
                setOnClickListener {
                    pad.tvMode = !pad.tvMode
                    text = if (pad.tvMode) "Mode: TV" else "Mode: GAME"
                }
            })
            addView(Button(context).apply { text = "⌨ Type"; setOnClickListener { toggleKeyboard() } })
        }

        typeField = buildTypeField()

        setContentView(FrameLayout(this).apply {
            addView(PadView(context, pad))
            addView(typeField, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM))
            addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        })

        if (hasPerms()) startPadService() else requestPerms()
    }

    private fun startPadService() {
        val i = Intent(this, PadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun perms() = if (Build.VERSION.SDK_INT >= 31)
        listOfNotNull(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null).toTypedArray()
    else emptyArray()

    private fun hasPerms() = perms().filter { it != Manifest.permission.POST_NOTIFICATIONS }.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPerms() = ActivityCompat.requestPermissions(this, perms(), 1)

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (hasPerms()) startPadService() else status.text = "Bluetooth permission needed"
    }

    /** Lets the TV find this tablet in "Pair accessory". */
    private fun makeDiscoverable() {
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        status.text = "On TV: Settings › Remotes & Accessories › Pair"
    }

    /** Reconnect to an already-paired TV. */
    private fun pickDevice() {
        val devices = pad.bondedDevices()
        if (devices.isEmpty()) { status.text = "No paired devices — tap Pair first"; return }
        AlertDialog.Builder(this)
            .setTitle("Connect to TV")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, i -> pad.connect(devices[i]) }
            .show()
    }

    // ---- Keyboard: the tablet's own keyboard types onto the TV ----
    private lateinit var typeField: EditText
    private val sentinel = "  " // lets us detect backspace on an "empty" field
    private var resetting = false

    private fun buildTypeField() = EditText(this).apply {
        setText(sentinel); setSelection(sentinel.length)
        isSingleLine = true
        alpha = 0f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        setOnEditorActionListener { _, _, _ -> pad.tapKey(HidGamepad.KEY_ENTER); true }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (resetting) return
                val t = s.toString()
                when {
                    t.length < sentinel.length -> repeat(sentinel.length - t.length) { pad.tapKey(HidGamepad.KEY_BACKSPACE) }
                    t.startsWith(sentinel) -> pad.typeText(t.substring(sentinel.length))
                    else -> pad.typeText(t.trim())
                }
                resetting = true
                setText(sentinel); setSelection(sentinel.length)
                resetting = false
            }
        })
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (typeField.hasFocus()) {
            imm.hideSoftInputFromWindow(typeField.windowToken, 0); typeField.clearFocus()
        } else {
            typeField.requestFocus(); imm.showSoftInput(typeField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroy() {
        // Keep the Bluetooth link alive; just detach this screen.
        pad.listener = null
        super.onDestroy()
    }
}
