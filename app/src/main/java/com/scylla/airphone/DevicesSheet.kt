package com.scylla.airphone

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Pick, add and forget TVs. Android TV hides phones in its own "Add accessory" list (it only shows
 * keyboards/mice/remotes/audio by Class of Device), so pairing starts here, on the phone: by search
 * (Android TV 12+ is discoverable while Add accessory is open) or by the TV's Bluetooth address (older
 * TVs such as the Mi TV Stick are never discoverable, but always connectable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onMakeVisible: () -> Unit,
    onForgetFailed: () -> Unit,
) {
    // Close once a (new) host connects and keeps the connection (Android 15+ hosts may drop it at once; then the
    // effect restarts with hostName = null and the sheet stays open to show why).
    val seen = remember { object { var host = HidLink.hostName } } // last value, not state: no recomposition
    LaunchedEffect(HidLink.hostName) {
        val now = HidLink.hostName
        val before = seen.host
        seen.host = now
        if (now != null && now != before) {
            delay(Pairing.QUICK_DROP_MS)
            onDismiss()
        }
    }
    DisposableEffect(Unit) { onDispose { HidLink.stopScan() } }

    var forgetting by remember { mutableStateOf<HidLink.Dev?>(null) }
    var showOthers by rememberSaveable { mutableStateOf(false) }
    val paired = remember(HidLink.bonds, HidLink.hostName) { HidLink.paired() }
    val (main, others) = paired.partition { it.likelyHost || it.known || HidLink.isHost(it.device) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Devices", style = MaterialTheme.typography.titleLarge)
            Text(HidLink.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (HidLink.failures > 0) Help()

            Section("Paired")
            if (main.isEmpty()) Hint("No TVs paired yet — add one below.")
            for (d in main) DeviceRow(d, onForget = { forgetting = d })
            if (others.isNotEmpty()) {
                TextButton(onClick = { showOthers = !showOthers }) {
                    Text(if (showOthers) "Hide other devices" else "Show ${others.size} other paired devices (headphones, watches…)")
                }
                if (showOthers) for (d in others) DeviceRow(d, onForget = { forgetting = d })
            }

            Section("Add a TV")
            SearchNearby(onScan)
            AddressEntry()
            TextButton(onClick = onMakeVisible) { Text("Computer or tablet? Pair from it instead…") }
        }
    }

    forgetting?.let { d ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${d.name}?") },
            text = { Text("This phone unpairs it. To pair again later, also remove this phone on the TV (Remotes & Accessories).") },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = null
                    if (!HidLink.forget(d.device)) onForgetFailed()
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) = Text(
    title,
    Modifier.padding(top = 12.dp),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Help() = Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
    Text(
        "Can't connect?\n" +
            "• Make sure the TV is on and within a few metres.\n" +
            "• If the TV was reset or you removed this phone there, Forget the TV here (✕) and add it again.\n" +
            "• Refused or closed right away? Some newer hosts (Android 15+) only accept a keyboard they connected " +
            "to themselves: on it, open Bluetooth settings and tap this phone once.\n" +
            "• Close other phone-as-keyboard apps, then turn Bluetooth off and on.",
        Modifier.padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DeviceRow(d: HidLink.Dev, onForget: (() -> Unit)? = null) {
    val connected = HidLink.isHost(d.device)
    val busy = HidLink.isConnecting(d.device)
    ListItem(
        headlineContent = { Text(d.name) },
        supportingContent = {
            when {
                connected -> Text("Connected")
                busy -> Text("Connecting…")
                d.known -> Text("Used before · tap to connect")
                onForget == null -> Text("Tap to pair")
                else -> Text("Tap to connect")
            }
        },
        leadingContent = if (busy) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connected) TextButton(onClick = HidLink::disconnect) { Text("Disconnect") }
                if (onForget != null) {
                    IconButton(onClick = onForget) { Icon(Icons.Filled.Close, contentDescription = "Forget ${d.name}") }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.clickable(enabled = !connected && !busy) { HidLink.pair(d.device) },
    )
}

@Composable
private fun SearchNearby(onScan: () -> Unit) {
    if (HidLink.scanning) {
        OutlinedButton(onClick = HidLink::stopScan, Modifier.fillMaxWidth()) { Text("Stop searching") }
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        FilledTonalButton(onClick = onScan, Modifier.fillMaxWidth()) { Text("Search nearby") }
    }
    Hint(
        "Android TV 12 and newer: open Settings → Remotes & Accessories → Add accessory on the TV while " +
            "searching, so it can be found. Older TVs (like the Mi TV Stick) never show up here — use the address below.",
    )
    val found = HidLink.found.sortedWith(compareByDescending<HidLink.Dev> { it.likelyHost }.thenByDescending { it.rssi })
    for (d in found) DeviceRow(d)
}

@Composable
private fun AddressEntry() {
    var text by rememberSaveable { mutableStateOf("") }
    val address = Pairing.normalizeAddress(text)
    val canPair = address != null && !HidLink.pairing
    val pair = { if (canPair) address?.let { HidLink.pairAddress(it) }; Unit }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(17) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("TV's Bluetooth address") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            isError = text.length >= 12 && address == null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { pair() }),
        )
        FilledTonalButton(onClick = pair, enabled = canPair) { Text("Pair") }
    }
    Hint("On the TV: Settings → Device Preferences → About → Status → Bluetooth address.")
}
