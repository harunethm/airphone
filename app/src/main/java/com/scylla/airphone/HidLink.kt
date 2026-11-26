package com.scylla.airphone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executor

/**
 * The phone's Bluetooth HID device role: registers the descriptor, connects to the TV, sends reports.
 * Callers check BLUETOOTH_CONNECT before [start]; every other call is a no-op until registered.
 */
@SuppressLint("MissingPermission")
object HidLink {
    enum class Phase { NEED_PERMISSION, BT_OFF, UNSUPPORTED, STARTING, READY, CONNECTED }

    var phase by mutableStateOf(Phase.STARTING)
        private set
    var status by mutableStateOf("Starting…")
        private set
    var hostName by mutableStateOf<String?>(null)
        private set

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private var adapter: BluetoothAdapter? = null
    // Requested once per process: the framework re-binds the same proxy itself after Bluetooth
    // restarts, and a second proxy's registerApp fails with "another app is registered" (us).
    private var proxyRequested = false
    private var registered = false
    private var registering = false
    private var foreground = false
    private var retriedThisResume = false
    private var pending: BluetoothDevice? = null // next host, connected once the current one is gone
    private var stateReceiverRegistered = false
    private val mouseLock = Any()
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }

    private val sdp = BluetoothHidDeviceAppSdpSettings(
        "AirPhone", "Air mouse and remote", "AirPhone", BluetoothHidDevice.SUBCLASS1_COMBO, Hid.DESCRIPTOR,
    )
    // Same out-QoS as Google's WearMouse sample, which is known to work with Android TV hosts.
    private val qos = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
    )

    val phoneName: String get() = adapter?.name ?: "this phone"

    fun needPermission() = set(Phase.NEED_PERMISSION, "Bluetooth permission needed")

    /**
     * Idempotent: call on every resume. Android's HidDeviceService unregisters the app whenever its
     * process leaves the foreground (screen off, app switch), so re-register here; the
     * registered callback then reconnects the last TV.
     */
    // ponytail: no foreground service, so the TV link drops while the app is in the background.
    // Add a connectedDevice foreground service if an always-on link is wanted.
    fun start(ctx: Context) {
        foreground = true
        retriedThisResume = false
        if (proxyRequested) {
            hid?.let { register(it) }
            return
        }
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences("airphone", Context.MODE_PRIVATE)
        val a = app.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return set(Phase.UNSUPPORTED, "This phone has no Bluetooth")
        adapter = a
        if (!stateReceiverRegistered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(btState, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(btState, filter)
            stateReceiverRegistered = true
        }
        if (!a.isEnabled) return set(Phase.BT_OFF, "Bluetooth is off")
        set(Phase.STARTING, "Starting Bluetooth HID…")
        proxyRequested = a.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)
        if (!proxyRequested) return set(Phase.UNSUPPORTED, "This phone's system has Bluetooth HID disabled")
        main.postDelayed({
            if (hid == null && proxyRequested) set(Phase.UNSUPPORTED, "Bluetooth HID service didn't answer — phone may not support it")
        }, 5000)
    }

    /** Activity left the foreground; Android is about to unregister us, so don't fight it. */
    fun pause() {
        foreground = false
    }

    /** Paired devices as (display name, device). */
    fun bonded(): List<Pair<String, BluetoothDevice>> =
        adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to it }.sortedBy { it.first.lowercase() }

    fun isHost(device: BluetoothDevice) = device == host

    fun connect(device: BluetoothDevice) {
        val h = hid ?: return
        val current = host
        if (current == device) return
        set(Phase.READY, "Connecting to ${device.name ?: device.address}…")
        // The HID device role holds one host and disconnect is async: connect the new one once it's gone.
        if (current != null) {
            pending = device
            h.disconnect(current)
            return
        }
        if (!h.connect(device)) set(Phase.READY, "Couldn't start connecting — is the TV on?")
    }

    fun disconnect() {
        pending = null
        host?.let { hid?.disconnect(it) }
    }

    // --- reports (safe from any thread) ---

    fun consumer(usage: Int) = send(Hid.ID_CONSUMER, Hid.consumer(usage))

    // Gyro (sensor thread) and touchpad/click (main thread) share the mouse report: one lock keeps a
    // gyro report from landing between a click's press and release and leaving the button held.
    fun move(dx: Int, dy: Int) = synchronized(mouseLock) { send(Hid.ID_MOUSE, Hid.mouse(0, dx, dy)) }

    fun click() = synchronized(mouseLock) {
        send(Hid.ID_MOUSE, Hid.mouse(1, 0, 0))
        send(Hid.ID_MOUSE, Hid.mouse(0, 0, 0))
    }

    fun keyDown(mod: Int, usage: Int) = send(Hid.ID_KEYBOARD, Hid.keyboard(mod, usage))

    fun keyUp() = send(Hid.ID_KEYBOARD, Hid.keyboard(0, 0))

    // ponytail: no pacing between reports; add a short delay if long pastes drop characters.
    fun type(backspaces: Int, text: String) {
        repeat(backspaces) { keyDown(0, Hid.KEY_BACKSPACE); keyUp() }
        for (c in text) {
            val (mod, usage) = Hid.keyFor(c) ?: continue
            keyDown(mod, usage)
            keyUp()
        }
    }

    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        h.sendReport(d, id, data)
    }

    private fun set(p: Phase, s: String) {
        phase = p
        status = s
    }

    private fun lastDevice(): BluetoothDevice? {
        val addr = prefs.getString(KEY_LAST, null) ?: return null
        return bonded().firstOrNull { it.second.address == addr }?.second
    }

    /** Registration and connection are gone (Bluetooth off, service rebind, or unregistered). */
    private fun lost() {
        registered = false
        registering = false
        host = null
        hostName = null
        pending = null
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        // A (re)bind means a fresh service: any earlier registration died with the old one.
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val h = proxy as BluetoothHidDevice
            hid = h
            lost()
            register(h)
        }

        // Bluetooth turned off or its process restarted; the framework calls onServiceConnected again when it's back.
        override fun onServiceDisconnected(profile: Int) {
            hid = null
            lost()
            set(Phase.BT_OFF, "Bluetooth is off")
        }
    }

    // Android 9-12 unbind the proxy on Bluetooth off without onServiceDisconnected, and a phone that
    // started with Bluetooth off needs a nudge when it comes on (quick settings doesn't pause us).
    private val btState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    lost()
                    set(Phase.BT_OFF, "Bluetooth is off")
                }
                BluetoothAdapter.STATE_ON -> if (!proxyRequested && foreground) start(context)
            }
        }
    }

    private fun register(h: BluetoothHidDevice) {
        if (registered || registering) return
        set(Phase.STARTING, "Registering as keyboard + mouse…")
        registering = h.registerApp(sdp, null, qos, mainExecutor, callback)
        if (!registering) set(Phase.UNSUPPORTED, "Bluetooth HID is busy — close other keyboard/mouse apps")
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, ok: Boolean) {
            if (!ok) {
                lost()
                // Quick app switch: we may already be back in front when the unregister lands.
                val h = hid
                if (foreground && !retriedThisResume && h != null) {
                    retriedThisResume = true
                    return register(h)
                }
                return set(Phase.STARTING, "Paused in background — reconnects when reopened")
            }
            registered = true
            registering = false
            set(Phase.READY, "Ready — pick your TV")
            (pluggedDevice ?: lastDevice())?.let { connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    hostName = device.name ?: device.address
                    prefs.edit().putString(KEY_LAST, device.address).apply()
                    set(Phase.CONNECTED, "Connected")
                }
                BluetoothProfile.STATE_CONNECTING -> set(Phase.READY, "Connecting to ${device.name ?: device.address}…")
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    host = null
                    hostName = null
                    val next = pending
                    pending = null
                    if (next != null && next != device) connect(next)
                    else set(if (registered) Phase.READY else Phase.STARTING, "Not connected")
                }
            }
        }

        // Hosts may poll reports or set LEDs; answer so they don't stall the connection.
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            val len = Hid.reportLength(id.toInt())
            if (len == null) h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            else h.replyReport(device, type, id, ByteArray(len))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private const val KEY_LAST = "last_host"
}
