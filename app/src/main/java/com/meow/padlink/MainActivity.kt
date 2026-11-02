package com.meow.padlink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
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
        pad = HidGamepad(this) { msg -> runOnUiThread { status.text = msg } }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
            addView(status)
            addView(Button(context).apply { text = "Pair"; setOnClickListener { makeDiscoverable() } })
            addView(Button(context).apply { text = "Connect"; setOnClickListener { pickDevice() } })
        }

        setContentView(FrameLayout(this).apply {
            addView(PadView(context, pad))
            addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        })

        if (hasPerms()) pad.start() else requestPerms()
    }

    private fun perms() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    else emptyArray()

    private fun hasPerms() = perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPerms() = ActivityCompat.requestPermissions(this, perms(), 1)

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (hasPerms()) pad.start() else status.text = "Bluetooth permission needed"
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

    override fun onDestroy() {
        pad.stop()
        super.onDestroy()
    }
}
