package com.micplugin.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import android.widget.Toast
import com.micplugin.CrashReporter
import com.micplugin.DiagnosticsLog
import com.micplugin.service.ShizukuState
import com.micplugin.service.VirtualMicTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(navController: NavController, vm: AudioViewModel = hiltViewModel()) {
    val status      by vm.engineStatus.collectAsState()
    val tier        by vm.virtualMicTier.collectAsState()
    val shizukuState by vm.shizukuState.collectAsState()
    val context     = LocalContext.current

    var crashReports by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var viewingCrash by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(Unit) { crashReports = CrashReporter.getCrashReports(context) }

    // ── Diagnostics: one text with device info, app state and recent logs ────
    val scope = rememberCoroutineScope()
    var collecting by remember { mutableStateOf(false) }

    /** What the app is doing right now. Read on the main thread, then handed to the collector. */
    fun appStateSnapshot(): String = buildString {
        val st = vm.engineStatus.value
        appendLine("Engine:        running=${st.isRunning} rate=${st.sampleRate} Hz frames/burst=${st.framesPerBurst} latency~${st.latencyMs.toInt()} ms")
        appendLine("Master bypass: ${vm.masterBypass.value}")
        appendLine("Preset:        ${vm.selectedPreset.value?.name ?: "none"}")
        appendLine("Shizuku:       ${vm.shizukuState.value}")
        appendLine("Active tier:   ${vm.virtualMicTier.value} (${vm.virtualMicTier.value.displayName})")
        VirtualMicTier.values().forEach { t ->
            appendLine("  tier ${t.name}: ${vm.checkTierAvailability(t) ?: "available"}")
        }
        appendLine("Input device id:  ${vm.selectedInputDeviceId.value}")
        appendLine("Output device id: ${vm.selectedOutputDeviceId.value}")
        val slots = vm.pluginSlots.value
        appendLine("Plugin chain (${slots.size}):")
        if (slots.isEmpty()) appendLine("  (empty)")
        slots.forEach { s ->
            // nativeHandle 0 means the plugin did not load into the audio engine
            appendLine("  #${s.position} ${s.descriptor.name} [${s.descriptor.format}] enabled=${s.enabled} " +
                "loaded=${s.nativeHandle != 0L} path=${s.descriptor.path}")
        }
        appendLine("Discovered plugins: ${vm.discovered.value.size}")
        vm.discovered.value.forEach { d -> appendLine("  ${d.name} [${d.format}] ${d.path}") }
    }

    fun collectLog(onReady: (String) -> Unit) {
        if (collecting) return
        collecting = true
        val snapshot = try { appStateSnapshot() } catch (e: Throwable) { "(could not read app state: $e)" }
        scope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    val probe = try { vm.shizukuProbe() } catch (e: Throwable) { "probe failed: $e" }
                    DiagnosticsLog.build(context, snapshot + "\n=== Shizuku probe (runs as the shell user) ===\n" + probe)
                }
            } catch (e: Throwable) {
                "Could not collect the log: $e"
            }
            collecting = false
            onReady(text)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Surface(color = StudioColors.Surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = StudioColors.TextPrimary)
                }
                Text("SETTINGS", style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary, letterSpacing = 2.sp)
            }
        }

        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // ── Audio ─────────────────────────────────────────────────────────
            SettingsSection("Audio") {
                SettingsInfoRow("Sample Rate", "${status.sampleRate} Hz")
                SettingsInfoRow("Frames/Burst", "${status.framesPerBurst}")
                SettingsInfoRow("Latency (est.)", "~${status.latencyMs.toInt()} ms")
            }

            // ── Shizuku ───────────────────────────────────────────────────────
            SettingsSection("Shizuku (ADB Access)") {
                val (stateColor, stateLabel) = when (shizukuState) {
                    ShizukuState.READY       -> Color(0xFF3DFCAC) to "READY"
                    ShizukuState.NEED_GRANT  -> Color(0xFFFFD700) to "NEED PERMISSION"
                    ShizukuState.UNAVAILABLE -> Color(0xFFFF6B6B) to "NOT RUNNING"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Status", fontSize = 12.sp, color = StudioColors.TextMuted)
                        Text(stateLabel, fontSize = 11.sp, color = stateColor)
                    }
                    if (shizukuState == ShizukuState.NEED_GRANT) {
                        Button(
                            onClick = { vm.requestShizukuPermission() },
                            colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B4D8)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Grant", fontSize = 11.sp)
                        }
                    }
                }
                if (shizukuState == ShizukuState.UNAVAILABLE) {
                    Text(
                        "Install Shizuku from Play Store, start it via ADB or wireless debugging, then reopen this app.",
                        fontSize = 10.sp, color = StudioColors.TextMuted, lineHeight = 14.sp,
                    )
                }
                if (shizukuState == ShizukuState.READY) {
                    Text(
                        "Shizuku active — ALSA loopback and appops routing available.",
                        fontSize = 10.sp, color = StudioColors.TextMuted, lineHeight = 14.sp,
                    )
                }
            }

            // ── Virtual Mic ───────────────────────────────────────────────────
            SettingsSection("Virtual Microphone") {
                Surface(
                    shape  = RoundedCornerShape(7.dp),
                    color  = Color(tier.badgeColorHex).copy(alpha = 0.05f),
                    border = BorderStroke(0.5.dp, Color(tier.badgeColorHex).copy(alpha = 0.4f)),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ACTIVE TIER", fontSize = 8.sp, color = StudioColors.TextMuted, letterSpacing = 1.sp)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape  = RoundedCornerShape(4.dp),
                                color  = Color(tier.badgeColorHex).copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, Color(tier.badgeColorHex)),
                            ) {
                                Text(tier.displayName, fontSize = 9.sp, color = Color(tier.badgeColorHex),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(tier.description, fontSize = 11.sp, color = StudioColors.TextMuted)
                    }
                }

                VirtualMicTier.values().forEach { t ->
                    val reason = vm.checkTierAvailability(t)
                    TierRow(
                        tier              = t,
                        isActive          = t == tier,
                        unavailableReason = reason,
                        onClick           = {
                            if (t == VirtualMicTier.SHIZUKU_ADB &&
                                shizukuState == ShizukuState.NEED_GRANT) {
                                vm.requestShizukuPermission()
                            } else {
                                vm.setTier(t)
                            }
                        },
                    )
                }
            }


            SettingsSection("Monitor Output Device") {
                val devices    by vm.outputDevices.collectAsState()
                val selectedId by vm.selectedOutputDeviceId.collectAsState()
                LaunchedEffect(Unit) { vm.loadOutputDevices(context) }
                if (devices.isEmpty()) {
                    Text("No devices found", fontSize = 12.sp, color = StudioColors.TextMuted)
                } else {
                    devices.forEach { device ->
                        val isSelected = device.id == selectedId
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setOutputDevice(context, device.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected,
                                onClick = { vm.setOutputDevice(context, device.id) },
                                colors  = RadioButtonDefaults.colors(selectedColor = StudioColors.Accent))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(device.name, fontSize = 12.sp,
                                    color = if (isSelected) StudioColors.Accent else StudioColors.TextPrimary)
                                if (device.id == -1)
                                    Text("Prefers headset then earpiece, never speaker",
                                        fontSize = 9.sp, color = StudioColors.TextMuted)
                            }
                        }
                    }
                }
            }

            SettingsSection("Input Device") {
                val devices    by vm.inputDevices.collectAsState()
                val selectedId by vm.selectedInputDeviceId.collectAsState()
                LaunchedEffect(Unit) { vm.loadInputDevices(context) }
                if (devices.isEmpty()) {
                    Text("No devices found", fontSize = 12.sp, color = StudioColors.TextMuted)
                } else {
                    devices.forEach { device ->
                        val isSelected = device.id == selectedId
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setInputDevice(context, device.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected,
                                onClick = { vm.setInputDevice(context, device.id) },
                                colors  = RadioButtonDefaults.colors(selectedColor = StudioColors.Accent))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(device.name, fontSize = 12.sp,
                                    color = if (isSelected) StudioColors.Accent else StudioColors.TextPrimary)
                                if (device.id == -1)
                                    Text("Uses whatever the OS considers the default mic",
                                        fontSize = 9.sp, color = StudioColors.TextMuted)
                            }
                        }
                    }
                }
            }

            // ── Plugins ───────────────────────────────────────────────────────
            SettingsSection("Plugins") {
                SettingsActionRow("Manage Plugin Paths", Icons.Default.FolderOpen) { navController.navigate("plugin_paths") }
                SettingsActionRow("Rescan Plugin Directories", Icons.Default.Refresh) { vm.rescan() }
            }

            // ── Diagnostics ───────────────────────────────────────────────────
            SettingsSection("Diagnostics") {
                Text(
                    "Bundles your device model, what the app is doing and this app's recent log lines into one text file. " +
                        "It contains no audio. Send it to whoever is helping you.",
                    fontSize = 10.sp, color = StudioColors.TextMuted, lineHeight = 14.sp,
                )
                SettingsActionRow(
                    if (collecting) "Collecting…" else "Share log",
                    Icons.Default.Share,
                ) {
                    collectLog { text ->
                        try {
                            DiagnosticsLog.share(context, DiagnosticsLog.writeFile(context, text))
                        } catch (e: Throwable) {
                            Toast.makeText(context, "Could not share the log: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                SettingsActionRow(
                    if (collecting) "Collecting…" else "Copy log",
                    Icons.Default.ContentCopy,
                ) {
                    collectLog { text ->
                        try {
                            DiagnosticsLog.copy(context, text)
                            Toast.makeText(context, "Log copied. Paste it into your message.", Toast.LENGTH_LONG).show()
                        } catch (e: Throwable) {
                            Toast.makeText(context, "Could not copy the log: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            // ── About ─────────────────────────────────────────────────────────
            SettingsSection("About") {
                SettingsInfoRow("Version", com.micplugin.BuildConfig.VERSION_NAME)
                SettingsInfoRow("Build", "MicUp · Oboe 1.8.1 · CLAP 1.2.1 · LV2")
                SettingsInfoRow("Native", "C++17 · NDK 26 · AAudio/OpenSL ES")
            }

            // ── Crash Reports ─────────────────────────────────────────────────
            SettingsSection("Crash Reports") {
                if (crashReports.isEmpty()) {
                    Text("No crashes recorded.", fontSize = 12.sp, color = StudioColors.TextMuted)
                } else {
                    crashReports.forEach { file ->
                        Row(Modifier.fillMaxWidth().clickable { viewingCrash = file },
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(file.name.removePrefix("crash_").removeSuffix(".txt"),
                                fontSize = 11.sp, color = StudioColors.MeterRed)
                            Icon(Icons.Default.ChevronRight, null, tint = StudioColors.TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    SettingsActionRow("Clear All Crash Reports", Icons.Default.DeleteForever) {
                        CrashReporter.clearAll(context); crashReports = emptyList()
                    }
                }
            }
        }
    }

    viewingCrash?.let { file ->
        Dialog(onDismissRequest = { viewingCrash = null }) {
            Surface(shape = RoundedCornerShape(12.dp), color = StudioColors.Card,
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Crash Report", style = MaterialTheme.typography.titleSmall, color = StudioColors.MeterRed)
                        IconButton(onClick = { viewingCrash = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, tint = StudioColors.TextMuted)
                        }
                    }
                    Spacer(Modifier.height(8.dp)); HorizontalDivider(color = StudioColors.Border); Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(file.readText(), fontSize = 9.sp, color = StudioColors.TextMuted,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { file.delete(); crashReports = CrashReporter.getCrashReports(context); viewingCrash = null },
                        modifier = Modifier.align(Alignment.End)) { Text("Delete", color = StudioColors.MeterRed) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        Surface(shape = RoundedCornerShape(8.dp), color = StudioColors.Card,
            border = BorderStroke(0.5.dp, StudioColors.Border), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = StudioColors.TextMuted)
        Text(value, fontSize = 12.sp, color = StudioColors.TextPrimary)
    }
}

@Composable
private fun SettingsActionRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = StudioColors.Accent)
        Icon(icon, null, tint = StudioColors.Accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TierRow(
    tier: VirtualMicTier,
    isActive: Boolean,
    unavailableReason: String?,
    onClick: () -> Unit,
) {
    val available = unavailableReason == null
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = available || isActive) { if (available) onClick() }
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected  = isActive,
                onClick   = if (available) onClick else null,
                colors    = RadioButtonDefaults.colors(
                    selectedColor   = Color(tier.badgeColorHex),
                    unselectedColor = if (available) StudioColors.TextMuted
                                     else StudioColors.TextMuted.copy(alpha = 0.35f),
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                tier.displayName, fontSize = 12.sp,
                color = when {
                    isActive   -> Color(tier.badgeColorHex)
                    !available -> StudioColors.TextMuted.copy(alpha = 0.4f)
                    else       -> StudioColors.TextMuted
                },
                modifier = Modifier.weight(1f),
            )
            if (!available) {
                Icon(
                    Icons.Default.Lock, contentDescription = null,
                    tint     = StudioColors.TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (unavailableReason != null) {
            Text(
                unavailableReason,
                fontSize   = 9.sp,
                color      = Color(0xFFFF6B6B).copy(alpha = 0.85f),
                lineHeight = 12.sp,
                modifier   = Modifier.padding(start = 48.dp, bottom = 2.dp),
            )
        }
    }
}
