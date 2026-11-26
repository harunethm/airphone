package com.scylla.airphone

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var prefs: SharedPreferences
    private lateinit var sensors: SensorManager
    private var gyro: Sensor? = null
    private var gravity: Sensor? = null
    private lateinit var sensorThread: HandlerThread
    private val motion = AirMotion()
    private var resumed = false // lifecycle state isn't RESUMED yet inside onResume
    private var air by mutableStateOf(false)
    private var gain by mutableFloatStateOf(AirMotion.DEFAULT_GAIN)
    private var repairNotice by mutableStateOf(false)

    private val perms = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        emptyArray()
    }
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startLink() }

    // Discovery: Android 12+ "Nearby devices"; 9-11 can only discover with location permission + Location on.
    private val scanPerms = arrayOf(
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION,
    )
    private val askScan = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) scan() else HidLink.say("Searching needs that permission — or pair by the TV's address")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("airphone", MODE_PRIVATE)
        sensors = getSystemService(SensorManager::class.java)
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorThread = HandlerThread("air").apply { start() }
        gain = prefs.getFloat(PREF_GAIN, AirMotion.DEFAULT_GAIN)
        motion.gain = gain
        air = gyro != null && prefs.getBoolean(PREF_AIR, true)
        if (!hasPerms()) requestPerms()
        // 0.3.0 added the scroll wheel to the mouse report; TVs paired earlier cached the old layout.
        // Marked seen on dismiss, so a recreation (theme, locale) or process death doesn't lose it.
        if (prefs.getInt(PREF_HID_LAYOUT, 1) < HID_LAYOUT) {
            repairNotice = !prefs.getStringSet("known_hosts", null).isNullOrEmpty()
            if (!repairNotice) prefs.edit().putInt(PREF_HID_LAYOUT, HID_LAYOUT).apply()
        }

        setContent {
            AirTheme {
                RemoteScreen(
                    air = air,
                    hasGyro = gyro != null,
                    onAir = ::toggleAir,
                    gain = gain,
                    onGain = { gain = it; motion.gain = it },
                    onGainDone = { prefs.edit().putFloat(PREF_GAIN, gain).apply() },
                    onFix = ::fix,
                    onScan = ::scan,
                    onMakeVisible = ::makeDiscoverable,
                    onForgetFailed = ::openBluetoothSettings,
                )
                if (repairNotice) {
                    RepairNotice {
                        repairNotice = false
                        prefs.edit().putInt(PREF_HID_LAYOUT, HID_LAYOUT).apply()
                    }
                }
            }
        }
    }

    @Composable
    private fun RepairNotice(onDismiss: () -> Unit) = AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("New: scrolling") },
        text = {
            Text(
                "Scroll with two fingers, or tap then drag, on the touchpad.\n\n" +
                    "Your TV still remembers AirPhone's old mouse, which has no scroll wheel. Pair once more to " +
                    "enable it: on the TV open Settings → Remotes & Accessories → this phone → Unpair, then in " +
                    "AirPhone tap Devices → ✕ on the TV and add it again.",
            )
        },
    )

    override fun onResume() {
        super.onResume()
        resumed = true
        startLink()
        updateSensors()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        HidLink.pause()
        updateSensors()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorThread.quitSafely()
    }

    private fun hasPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPerms() {
        prefs.edit().putBoolean(PREF_ASKED, true).apply()
        askPerms.launch(perms)
    }

    private fun startLink() = if (hasPerms()) HidLink.start(applicationContext) else HidLink.needPermission()

    private fun fix() {
        when (HidLink.phase) {
            HidLink.Phase.NEED_PERMISSION -> {
                val denied = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                val blocked = prefs.getBoolean(PREF_ASKED, false) && denied.none { shouldShowRequestPermissionRationale(it) }
                if (blocked) {
                    openAppSettings()
                } else {
                    requestPerms()
                }
            }
            HidLink.Phase.BT_OFF -> startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> {}
        }
    }

    private fun scan() {
        val denied = scanPerms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (denied.isNotEmpty()) {
            if (prefs.getBoolean(PREF_ASKED_SCAN, false) && denied.none { shouldShowRequestPermissionRationale(it) }) {
                HidLink.say("Allow Nearby devices / Location for AirPhone in Settings, or pair by the TV's address")
                openAppSettings()
            } else {
                prefs.edit().putBoolean(PREF_ASKED_SCAN, true).apply()
                askScan.launch(scanPerms)
            }
            return
        }
        if (Build.VERSION.SDK_INT < 31 && !getSystemService(LocationManager::class.java).isLocationEnabled) {
            HidLink.say("On Android 11 and older, searching needs Location on — turn it on, then search again")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        HidLink.startScan()
    }

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun openBluetoothSettings() {
        HidLink.say("Forget it in Bluetooth settings (tap the device → Forget)")
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun makeDiscoverable() {
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
        )
    }

    private fun toggleAir(on: Boolean) {
        air = on
        prefs.edit().putBoolean(PREF_AIR, on).apply()
        updateSensors()
    }

    private fun updateSensors() {
        sensors.unregisterListener(this)
        val g = gyro ?: return
        if (!air || !resumed) return
        motion.reset()
        val handler = Handler(sensorThread.looper)
        gravity?.let { sensors.registerListener(this, it, SAMPLE_US, handler) }
        sensors.registerListener(this, g, SAMPLE_US, handler)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return motion.gravity(e.values)
        val (dx, dy) = motion.gyro(e.values, e.timestamp)
        if (dx != 0 || dy != 0) HidLink.move(dx, dy)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private companion object {
        const val SAMPLE_US = 10_000 // 100 Hz
        const val PREF_GAIN = "gain"
        const val PREF_AIR = "air"
        const val PREF_ASKED = "asked_perms"
        const val PREF_ASKED_SCAN = "asked_scan_perms"
        const val PREF_HID_LAYOUT = "hid_layout" // mouse report version the user was told about
        const val HID_LAYOUT = 2 // 2 = wheel + pan (0.3.0)
    }
}

private const val TOUCH_GAIN = 1.2f // cursor counts per phone pixel
private val SCROLL_STEP = 24.dp // finger travel per wheel step (a step scrolls ~64dp on the TV)
private const val DOUBLE_CLICK_GAP_MS = 70L

@Composable
private fun AirTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current) else darkColorScheme()
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteScreen(
    air: Boolean,
    hasGyro: Boolean,
    onAir: (Boolean) -> Unit,
    gain: Float,
    onGain: (Float) -> Unit,
    onGainDone: () -> Unit,
    onFix: () -> Unit,
    onScan: () -> Unit,
    onMakeVisible: () -> Unit,
    onForgetFailed: () -> Unit,
) {
    var devices by rememberSaveable { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    // First run: nothing has ever connected, so go straight to adding a TV.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(HidLink.phase) {
        if (HidLink.phase == HidLink.Phase.READY && !autoOpened && !HidLink.hasKnownHost()) {
            autoOpened = true
            devices = true
        }
    }
    // While the phone keyboard is up there's no room for the remote; keep header, touchpad and text field.
    val typing = WindowInsets.isImeVisible
    // The text field keeps focus (and the keyboard) until told otherwise: drop it when the keyboard is closed
    // with Back, or when any touch lands outside the type row. Observed in the Initial pass and not consumed,
    // so that touch still works (drag moves the cursor, keys press).
    val focus = LocalFocusManager.current
    LaunchedEffect(typing) { if (!typing) focus.clearFocus() }
    var typeRow by remember { mutableStateOf<Rect?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (typeRow?.contains(down.position + origin) != true) focus.clearFocus()
                }
            },
    ) {
        val compact = maxHeight < 680.dp // small phones: shrink keys so the touchpad keeps usable height
        val keyH = if (compact) 44.dp else 52.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            Header(onFix = onFix, onDevices = { devices = true })
            if (!typing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val m = Modifier.weight(1f).height(keyH)
                    ConsumerKey("Power", Hid.CC_POWER, m, text = "POWER")
                    ConsumerKey("Back", Hid.CC_BACK, m, icon = Icons.AutoMirrored.Filled.ArrowBack)
                    ConsumerKey("Home", Hid.CC_HOME, m, icon = Icons.Filled.Home)
                    ConsumerKey("Menu", Hid.CC_MENU, m, icon = Icons.Filled.Menu)
                }
                DPad(if (compact) 50.dp else 60.dp, Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val m = Modifier.weight(1f).height(keyH)
                    ConsumerKey("Volume down", Hid.CC_VOL_DOWN, m, text = "VOL −")
                    ConsumerKey("Mute", Hid.CC_MUTE, m, text = "MUTE")
                    ConsumerKey("Volume up", Hid.CC_VOL_UP, m, text = "VOL +")
                    ConsumerKey("Play or pause", Hid.CC_PLAY_PAUSE, m, icon = Icons.Filled.PlayArrow)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Air mouse", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = air,
                        onCheckedChange = onAir,
                        enabled = hasGyro,
                        modifier = Modifier.semantics { contentDescription = "Air mouse" },
                    )
                    if (hasGyro) {
                        Text(
                            "Speed",
                            Modifier.padding(start = 16.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = gain,
                            onValueChange = onGain,
                            onValueChangeFinished = onGainDone,
                            valueRange = 600f..4000f,
                            enabled = air,
                            modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { contentDescription = "Air mouse speed" },
                        )
                    } else {
                        Text(
                            "No gyroscope on this phone",
                            Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TouchPad(Modifier.fillMaxWidth().weight(1f))
            TypeRow(Modifier.onGloballyPositioned { typeRow = it.boundsInWindow() })
        }
    }
    // Not before permission: a sheet restored after process death could open before start() (HidLink guards
    // prefs too). Brief STARTING/BT_OFF keep it open so typed input and search results survive.
    if (devices && HidLink.phase != HidLink.Phase.NEED_PERMISSION) {
        DevicesSheet(
            onDismiss = { devices = false },
            onScan = onScan,
            onMakeVisible = { devices = false; onMakeVisible(); pairing = true },
            onForgetFailed = onForgetFailed,
        )
    }
    if (pairing) PairDialog { pairing = false }
}

@Composable
private fun Header(onFix: () -> Unit, onDevices: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                HidLink.hostName ?: "Not connected",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                HidLink.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when (HidLink.phase) {
            HidLink.Phase.NEED_PERMISSION -> Button(onClick = onFix) { Text("Allow") }
            HidLink.Phase.BT_OFF -> Button(onClick = onFix) { Text("Turn on") }
            HidLink.Phase.READY, HidLink.Phase.CONNECTED -> FilledTonalButton(onClick = onDevices) {
                Text(if (HidLink.phase == HidLink.Phase.CONNECTED) "Devices" else "Connect")
            }
            HidLink.Phase.STARTING, HidLink.Phase.UNSUPPORTED -> {}
        }
    }
}

@Composable
private fun PairDialog(onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    title = { Text("Pair from the other device") },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "For computers, tablets and phones. (TVs don't list phones — use Search or the address instead.)\n\n" +
                    "1. Allow the visibility prompt — this phone shows up as “${HidLink.phoneName}” for 2 minutes.\n" +
                    "2. On the other device, open its Bluetooth settings and pick this phone.\n" +
                    "3. Confirm the pairing code on both screens.\n\n" +
                    "It connects by itself after pairing. Paired these two before? Unpair on both sides first.",
            )
            Text(HidLink.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    },
)

@Composable
private fun DPad(keySize: Dp, modifier: Modifier) {
    val k = Modifier.size(keySize)
    val arrow = Icons.Filled.KeyboardArrowUp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ConsumerKey("Up", Hid.CC_UP, k, icon = arrow)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConsumerKey("Left", Hid.CC_LEFT, k, icon = arrow, rotation = 270f)
            ConsumerKey("OK", Hid.CC_OK, k)
            ConsumerKey("Right", Hid.CC_RIGHT, k, icon = arrow, rotation = 90f)
        }
        ConsumerKey("Down", Hid.CC_DOWN, k, icon = arrow, rotation = 180f)
    }
}

@Composable
private fun ConsumerKey(
    label: String,
    usage: Int,
    modifier: Modifier,
    icon: ImageVector? = null,
    rotation: Float = 0f,
    text: String? = null,
) = Key(label, modifier, icon, rotation, text, onDown = { HidLink.consumer(usage) }, onUp = { HidLink.consumer(0) })

/** Press sends key-down, release sends key-up, so holding a key auto-repeats on the TV like a real remote. */
@Composable
private fun Key(
    label: String,
    modifier: Modifier,
    icon: ImageVector? = null,
    rotation: Float = 0f,
    text: String? = null,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    var down by remember { mutableStateOf(false) }
    val view = LocalView.current
    Surface(
        modifier = modifier
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick { onDown(); onUp(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onDown()
                    // finally: a press cancelled mid-hold (key leaves the screen when the keyboard opens)
                    // must still send key-up, or the TV keeps auto-repeating it.
                    try {
                        tryAwaitRelease()
                    } finally {
                        onUp()
                        down = false
                    }
                })
            },
        shape = RoundedCornerShape(16.dp),
        color = if (down) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.rotate(rotation))
            else Text(text ?: label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TouchPad(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = "Touchpad"; onClick { HidLink.click(); true } }
            .pointerInput(Unit) {
                val pad = PadGestures(
                    slopPx = viewConfiguration.touchSlop,
                    scrollPxPerStep = SCROLL_STEP.toPx(),
                    doubleTapMs = viewConfiguration.doubleTapTimeoutMillis,
                    longPressMs = viewConfiguration.longPressTimeoutMillis,
                    moveGain = TOUCH_GAIN,
                )
                var clickTimer: Job? = null
                fun run(outs: List<PadGestures.Out>) {
                    // Double click: apps need >= 40 ms between the clicks (GestureDetector DOUBLE_TAP_MIN_TIME).
                    if (outs.count { it == PadGestures.Out.Click } >= 2) {
                        scope.launch { HidLink.click(); delay(DOUBLE_CLICK_GAP_MS); HidLink.click() }
                        return
                    }
                    outs.forEach {
                        when (it) {
                            is PadGestures.Out.Move -> HidLink.move(it.dx, it.dy)
                            is PadGestures.Out.Scroll -> HidLink.scroll(it.wheel, it.pan)
                            PadGestures.Out.Click -> HidLink.click()
                        }
                    }
                }
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    clickTimer?.cancel()
                    run(pad.down(first.uptimeMillis))
                    first.consume()
                    var lastTime = first.uptimeMillis
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // Compose cancels with a synthetic, already-consumed release; a real lift isn't consumed.
                        if (event.changes.all { !it.pressed && it.isConsumed }) {
                            cancelled = true
                            break
                        }
                        lastTime = event.changes.maxOf { it.uptimeMillis }
                        val moving = event.changes.filter { it.pressed && it.previousPressed }
                        if (moving.isNotEmpty()) {
                            val d = moving.fold(Offset.Zero) { sum, c -> sum + c.positionChange() } / moving.size.toFloat()
                            val fingers = event.changes.count { it.pressed }
                            if (d != Offset.Zero || fingers >= 2) run(pad.move(d.x, d.y, fingers))
                        }
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (cancelled) pad.cancel() else run(pad.up(lastTime))
                    pad.clickDueAt?.let { due ->
                        clickTimer = scope.launch {
                            delay((due - SystemClock.uptimeMillis()).coerceAtLeast(0))
                            run(pad.flush(SystemClock.uptimeMillis()))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Touchpad\ndrag to move · tap to click\nscroll: two fingers, or tap then drag",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TypeRow(modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    // The field mirrors what this TV connection received; start fresh when the host changes so
    // text typed while disconnected never turns into backspaces on the TV.
    LaunchedEffect(HidLink.hostName) { text = "" }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                val (backspaces, typed) = Hid.textEdits(text, new)
                HidLink.type(backspaces, typed)
                text = new
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Type on TV") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { HidLink.type(0, "\n"); text = "" }),
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear field (TV text stays)") }
                }
            },
        )
        Key(
            "Backspace",
            Modifier.size(56.dp),
            text = "⌫",
            onDown = { HidLink.keyDown(0, Hid.KEY_BACKSPACE) },
            onUp = { HidLink.keyUp() },
        )
    }
}
