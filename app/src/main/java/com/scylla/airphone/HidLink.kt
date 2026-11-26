package com.scylla.airphone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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

    /** A paired or nearby device. [known]: it has taken our connection before. */
    data class Dev(val device: BluetoothDevice, val name: String, val likelyHost: Boolean, val known: Boolean, val rssi: Int = 0)

    var phase by mutableStateOf(Phase.STARTING)
        private set
    var status by mutableStateOf("Starting…")
        private set
    var hostName by mutableStateOf<String?>(null)
        private set
    var scanning by mutableStateOf(false)
        private set
    /** Classic-Bluetooth devices found by [startScan] that aren't paired yet. */
    val found = mutableStateListOf<Dev>()
    /** Failed connects/pairings since the last success; the UI shows help once this is > 0. */
    var failures by mutableIntStateOf(0)
        private set
    /** Bumped on every bond change so the paired list recomposes. */
    var bonds by mutableIntStateOf(0)
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
    private var wanted: BluetoothDevice? = null // connect once registered (app was paused mid-attempt)
    // Compose state so the device rows can show progress; only touched on the main thread.
    private var pending by mutableStateOf<BluetoothDevice?>(null) // next host, connected once the current one is gone
    private var bondTarget by mutableStateOf<BluetoothDevice?>(null)
    private var connecting by mutableStateOf<BluetoothDevice?>(null)
    private val aclUp = mutableSetOf<String>() // addresses with a live Bluetooth link (ACL broadcasts)
    private var reached = false // the current attempt got a link up: a failure is a refusal, not "off/far"
    private var connectedAt = 0L
    private var userDisconnect = false // the next DISCONNECTED was asked for, not a refusal
    private var stateReceiverRegistered = false
    private var searchLive = false // our discovery has started (see ACTION_DISCOVERY_STARTED)
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
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            // EXPORTED: these come from the Bluetooth process (its own uid, not "system"), which NOT_EXPORTED
            // filters out. Safe: all of these are protected broadcasts that other apps can't send.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(btEvents, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(btEvents, filter)
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

    /** Paired devices: the current host, then ones that connected before, then likely hosts. */
    fun paired(): List<Dev> {
        val known = knownHosts()
        return adapter?.bondedDevices.orEmpty()
            .map { Dev(it, label(it), Pairing.isLikelyHost(it.bluetoothClass?.deviceClass), it.address in known) }
            .sortedWith(
                compareByDescending<Dev> { it.device == host }.thenByDescending { it.known }
                    .thenByDescending { it.likelyHost }.thenBy { it.name.lowercase() },
            )
    }

    fun isHost(device: BluetoothDevice) = device == host

    fun hasKnownHost() = knownHosts().isNotEmpty()

    fun isConnecting(device: BluetoothDevice) = device == connecting || device == bondTarget || device == pending

    /** A phone-side pairing is in progress (its code dialogs may be up). */
    val pairing: Boolean get() = bondTarget != null

    fun connect(device: BluetoothDevice) {
        val h = hid
        if (h == null || !registered) {
            wanted = device // the registered callback connects it
            return
        }
        val current = host ?: connecting
        if (current == device) return
        stopScan() // inquiry steals radio time from paging
        set(Phase.READY, "Connecting to ${label(device)}…")
        // The HID device role holds one host, and connect() during a still-paging attempt silently reuses its
        // slot (callbacks then name the wrong TV). Disconnect is async: connect the new one once the old is gone.
        if (current != null) {
            pending = device
            h.disconnect(current)
            main.removeCallbacks(switchNow)
            main.postDelayed(switchNow, SWITCH_TIMEOUT_MS) // in case the old one's DISCONNECTED never comes
            return
        }
        main.removeCallbacks(switchNow)
        connecting = device
        reached = device.address in aclUp
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        if (!h.connect(device)) {
            reached = false // nothing was sent, so nobody refused
            failed(device)
        }
    }

    /** User-initiated: also stops auto-reconnect until they pick a TV again. */
    fun disconnect() {
        pending = null
        wanted = null
        main.removeCallbacks(switchNow)
        userDisconnect = true
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_AUTO, false).apply()
        connecting?.let { hid?.disconnect(it) }
        stopConnecting()
        host?.let { hid?.disconnect(it) }
        if (host == null) set(if (registered) Phase.READY else Phase.STARTING, "Not connected")
    }

    /** Pair (phone-initiated) and then connect. Bonded devices just connect. */
    fun pair(device: BluetoothDevice) {
        stopScan()
        if (device.bondState == BluetoothDevice.BOND_BONDED) return connect(device)
        // One pairing at a time: a second createBond while BONDING fails on Android ≤13 and would drop the first.
        if (bondTarget != null || device.bondState == BluetoothDevice.BOND_BONDING) return
        bondTarget = device
        say("Pairing with ${label(device)}… confirm the code on both screens when it appears")
        if (!device.createBond()) {
            bondTarget = null
            failures++
            say("Couldn't start pairing with ${label(device)}")
        }
    }

    /** [address] from [Pairing.normalizeAddress]. Works with TVs that are on but not discoverable. */
    fun pairAddress(address: String) {
        adapter?.getRemoteDevice(address)?.let { pair(it) }
    }

    /** Removes the pairing on this phone. False if Android refuses (then send the user to Settings). */
    fun forget(device: BluetoothDevice): Boolean {
        if (device == host || device == connecting) disconnect()
        if (wanted == device) wanted = null
        if (pending == device) pending = null
        forgetHost(device.address)
        // ponytail: removeBond is a hidden API; Bluetooth settings is the fallback when it's blocked.
        return runCatching { device.javaClass.getMethod("removeBond").invoke(device) as Boolean }.getOrDefault(false)
    }

    /** Caller holds BLUETOOTH_SCAN (31+) or location (28-30, with location on). */
    fun startScan() {
        val a = adapter ?: return
        found.clear()
        searchLive = false // a FINISHED still in flight from an earlier search isn't ours
        scanning = try {
            a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: SecurityException) {
            false
        }
        if (!scanning) say("Couldn't start searching")
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        searchLive = false
        runCatching { adapter?.cancelDiscovery() }
    }

    fun say(s: String) {
        status = s
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

    private fun label(d: BluetoothDevice) = d.name ?: d.address

    private fun lastDevice(): BluetoothDevice? {
        val addr = prefs.getString(KEY_LAST, null) ?: return null
        return adapter?.bondedDevices.orEmpty().firstOrNull { it.address == addr }
    }

    // prefs exist once start() ran; a restored Devices sheet can ask earlier (e.g. permission revoked).
    private fun knownHosts(): Set<String> =
        if (::prefs.isInitialized) prefs.getStringSet(KEY_KNOWN, null).orEmpty() else emptySet()

    private fun forgetHost(address: String) {
        if (!::prefs.isInitialized) return
        prefs.edit().putStringSet(KEY_KNOWN, knownHosts() - address).apply()
        if (prefs.getString(KEY_LAST, null) == address) prefs.edit().remove(KEY_LAST).apply()
    }

    private fun stopConnecting() {
        main.removeCallbacks(connectTimeout)
        connecting = null
    }

    // A TV that's off or asleep leaves the stack paging for up to a minute; give up sooner and say why.
    private val connectTimeout = Runnable {
        val d = connecting ?: return@Runnable
        hid?.disconnect(d)
        if (pending == null) return@Runnable failed(d)
        // User switched TVs while d was still paging: move on even if d's DISCONNECTED never comes.
        main.removeCallbacks(switchNow)
        main.postDelayed(switchNow, 500)
    }

    /** Give up on the old host/attempt and connect the parked one (normally DISCONNECTED does this first). */
    private val switchNow = Runnable {
        val next = pending ?: return@Runnable
        pending = null
        stopConnecting()
        host = null // its DISCONNECTED never came; don't park behind it again
        hostName = null
        connect(next)
    }

    /** Remember a host only once it kept the connection: Android 15+ hosts accept, then drop at once. */
    private val markKnown = Runnable {
        val d = host ?: return@Runnable
        prefs.edit()
            .putString(KEY_LAST, d.address)
            .putStringSet(KEY_KNOWN, knownHosts() + d.address)
            .putBoolean(KEY_AUTO, true)
            .apply()
    }

    private fun failed(d: BluetoothDevice) {
        stopConnecting()
        if (wanted == d) wanted = null
        if (pending == d) pending = null
        failures++
        set(if (registered) Phase.READY else Phase.STARTING, Pairing.connectFailure(label(d), reached))
    }

    /** Registration and connection are gone (Bluetooth off, service rebind, or unregistered). */
    private fun lost() {
        registered = false
        registering = false
        // An attempt cut short by losing registration (app backgrounded, Bluetooth off) resumes once registered again.
        (pending ?: connecting)?.let { wanted = it }
        stopConnecting()
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
    private val btEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> {
                        lost()
                        aclUp.clear()
                        scanning = false
                        searchLive = false
                        bondTarget = null
                        found.clear()
                        set(Phase.BT_OFF, "Bluetooth is off")
                    }
                    BluetoothAdapter.STATE_ON -> if (!proxyRequested && foreground) start(context)
                }
                // startScan cancels any running discovery first; that one's late FINISHED must not end ours,
                // so only a FINISHED after our STARTED counts.
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> searchLive = scanning
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (searchLive) {
                    searchLive = false
                    if (scanning && found.isEmpty()) say("No TV found — older TVs can't be found; use its address")
                    scanning = false
                }
                BluetoothDevice.ACTION_FOUND -> onFound(intent)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBond(intent)
                BluetoothDevice.ACTION_ACL_CONNECTED -> intent.device()?.let {
                    aclUp += it.address
                    if (it == connecting) reached = true
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> intent.device()?.let { aclUp -= it.address }
            }
        }
    }

    private fun onFound(intent: Intent) {
        val d = intent.device() ?: return
        // HID device role is Classic only; unnamed and LE-only hits are beacons, earbuds' cases, etc.
        if (d.bondState == BluetoothDevice.BOND_BONDED || d.type == BluetoothDevice.DEVICE_TYPE_LE) return
        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: d.name ?: return
        @Suppress("DEPRECATION")
        val cls = (intent.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS) ?: d.bluetoothClass)?.deviceClass
        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
        val dev = Dev(d, name, Pairing.isLikelyHost(cls), known = false, rssi = rssi)
        val i = found.indexOfFirst { it.device == d }
        if (i >= 0) found[i] = dev else found += dev
    }

    private fun onBond(intent: Intent) {
        val d = intent.device() ?: return
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
        bonds++
        if (state == BluetoothDevice.BOND_NONE) forgetHost(d.address)
        // A TV that lost its keys (reset, unpaired there) makes the stack re-pair mid-connect: hold the
        // timeout while both code prompts are up, else it cancels the pairing under the user.
        if (d == connecting) when (state) {
            BluetoothDevice.BOND_BONDING -> {
                main.removeCallbacks(connectTimeout)
                say("${label(d)} asks to pair again — confirm the code on both screens (TV: use its remote)")
            }
            BluetoothDevice.BOND_BONDED -> {
                main.removeCallbacks(connectTimeout)
                main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            }
            BluetoothDevice.BOND_NONE -> {
                stopConnecting()
                hid?.disconnect(d)
                if (wanted == d) wanted = null
                if (pending == d) pending = null
                failures++
                say(Pairing.bondFailure(label(d), intent.getIntExtra(EXTRA_UNBOND_REASON, 0)) + " — add it again below")
                if (pending != null) {
                    main.removeCallbacks(switchNow)
                    main.postDelayed(switchNow, 500) // the user already picked another TV
                }
            }
        }
        if (d != bondTarget) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                bondTarget = null
                found.removeAll { it.device == d }
                connect(d)
            }
            BluetoothDevice.BOND_NONE -> {
                bondTarget = null
                failures++
                say(Pairing.bondFailure(label(d), intent.getIntExtra(EXTRA_UNBOND_REASON, 0)))
            }
        }
    }

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

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
            set(Phase.READY, if (knownHosts().isEmpty()) "Ready — add your TV" else "Ready — pick your TV")
            // Only hosts that took our connection before: the stack's "plugged" device can be a TV that
            // never accepted us, and auto-failing on it at every launch reads as a broken app.
            val known = knownHosts()
            val auto = listOfNotNull(pluggedDevice, lastDevice()).firstOrNull { it.address in known }
                .takeIf { prefs.getBoolean(KEY_AUTO, true) }
            val target = wanted ?: auto
            wanted = null
            target?.let { connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    stopConnecting()
                    userDisconnect = false
                    host = device
                    val next = pending
                    if (next != null && next != device) {
                        // The old attempt won the race with our disconnect: drop it, DISCONNECTED moves on to next.
                        hid?.disconnect(device)
                        return
                    }
                    pending = null
                    main.removeCallbacks(switchNow)
                    failures = 0
                    connectedAt = SystemClock.elapsedRealtime()
                    hostName = label(device)
                    main.removeCallbacks(markKnown)
                    main.postDelayed(markKnown, Pairing.QUICK_DROP_MS)
                    set(Phase.CONNECTED, "Connected")
                }
                BluetoothProfile.STATE_CONNECTING -> set(Phase.READY, "Connecting to ${label(device)}…")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val attempt = device == connecting && device != host // refused, timed out or switched away
                    if (!attempt && device != host) return // stale or repeated callback: keep the last message
                    // Android 15+ hosts accept the channels, then close them if they didn't start the first connection.
                    val droppedAtOnce = device == host && !userDisconnect &&
                        SystemClock.elapsedRealtime() - connectedAt < Pairing.QUICK_DROP_MS
                    userDisconnect = false
                    main.removeCallbacks(markKnown)
                    if (device == host) {
                        host = null
                        hostName = null
                    }
                    val next = pending
                    pending = null
                    val idle = if (registered) Phase.READY else Phase.STARTING
                    when {
                        next != null && next != device -> {
                            main.removeCallbacks(switchNow)
                            stopConnecting()
                            connect(next)
                        }
                        attempt -> failed(device)
                        droppedAtOnce -> {
                            failures++
                            set(idle, "${label(device)} closed the connection right away — see Can't connect?")
                        }
                        else -> set(idle, "Not connected")
                    }
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
    private const val KEY_KNOWN = "known_hosts" // addresses that have taken our HID connection
    private const val KEY_AUTO = "auto_reconnect" // off after a manual disconnect
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val SWITCH_TIMEOUT_MS = 5_000L
    private const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON" // hidden; absent → 0
}
