// Copyright (c) 2026 Elias S. G. Carotti
package com.ytsejam.phonar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ytsejam.phonar.ui.theme.PhonarTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class ScanPhase {
    Idle,
    Scanning,
    Result,
    Error,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            PhonarTheme {
                PhonarScreen(
                    hasPermission = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
            }
        }
    }
}

@Composable
private fun PhonarScreen(hasPermission: Boolean) {
    val scope = rememberCoroutineScope()
    val engine = remember { SonarEngine() }
    var permissionGranted by remember { mutableStateOf(hasPermission) }
    var phase by remember { mutableStateOf(ScanPhase.Idle) }
    var pingCount by remember { mutableIntStateOf(0) }
    var pingTotal by rememberSaveable { mutableIntStateOf(3) }
    var transmitVolume by rememberSaveable { mutableFloatStateOf(0.8f) }
    var showTuningDialog by rememberSaveable { mutableStateOf(false) }
    var probeKind by rememberSaveable { mutableStateOf(ProbeKind.Chirp) }
    var probeMenuExpanded by remember { mutableStateOf(false) }
    var chirpDurationMs by rememberSaveable { mutableFloatStateOf(40f) }
    var chirpStartHz by rememberSaveable { mutableFloatStateOf(16_000f) }
    var chirpEndHz by rememberSaveable { mutableFloatStateOf(22_000f) }
    var pingDurationMs by rememberSaveable { mutableFloatStateOf(120f) }
    var pingHz by rememberSaveable { mutableFloatStateOf(18_000f) }
    var pingDecay by rememberSaveable { mutableFloatStateOf(6f) }
    var scanSweep by remember { mutableFloatStateOf(0f) }
    var scanWave by remember { mutableFloatStateOf(0f) }
    var reading by remember { mutableStateOf<SonarReading?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            error = "Microphone permission is required."
            phase = ScanPhase.Error
        }
    }

    fun startScan() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (phase == ScanPhase.Scanning) return

        phase = ScanPhase.Scanning
        pingCount = 0
        scanSweep = 0f
        scanWave = 0f
        reading = null
        error = null

        val probe = ProbeSpec(
            kind = probeKind,
            chirpDurationMs = chirpDurationMs,
            chirpStartHz = chirpStartHz,
            chirpEndHz = chirpEndHz,
            pingDurationMs = pingDurationMs,
            pingHz = pingHz,
            pingDecay = pingDecay,
        )

        scope.launch {
            val scanJob: Job = launch {
                while (isActive && phase == ScanPhase.Scanning) {
                    scanSweep = (scanSweep + 7f) % 360f
                    scanWave = (scanWave + 0.035f) % 1f
                    delay(32)
                }
            }

            try {
                reading = engine.measure(
                    probe = probe,
                    pingCount = pingTotal,
                    volume = transmitVolume,
                ) { trial ->
                    scope.launch {
                        pingCount = trial
                    }
                }
                phase = ScanPhase.Result
            } catch (t: Throwable) {
                error = t.message ?: "Measurement failed"
                phase = ScanPhase.Error
            } finally {
                scanJob.cancel()
            }
        }
    }

    LaunchedEffect(phase) {
        if (phase != ScanPhase.Idle) return@LaunchedEffect
        scanSweep = 0f
        scanWave = 0f
    }

    if (showTuningDialog) {
        AlertDialog(
            onDismissRequest = { showTuningDialog = false },
            containerColor = Color(0xFF061012),
            titleContentColor = Color(0xFFE8FFF0),
            textContentColor = Color(0xFFBDE7DC),
            title = {
                Text(
                    text = "Tuning",
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Transmit volume",
                        color = Color(0xFFBDE7DC),
                    )
                    Text(
                        text = String.format(Locale.US, "%.0f%%", transmitVolume * 100f),
                        color = Color(0xFFE8FFF0),
                    )
                    Slider(
                        value = transmitVolume,
                        onValueChange = { transmitVolume = it },
                        valueRange = 0.05f..1f,
                        steps = 0,
                    )

                    Text(
                        text = "Scan pings: $pingTotal",
                        color = Color(0xFFBDE7DC),
                    )
                    Slider(
                        value = pingTotal.toFloat(),
                        onValueChange = { pingTotal = it.toInt().coerceIn(1, 8) },
                        valueRange = 1f..8f,
                        steps = 6,
                    )

                    Text(
                        text = "Probe type",
                        color = Color(0xFFBDE7DC),
                    )
                    Box {
                        Button(
                            onClick = { probeMenuExpanded = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF091716),
                                contentColor = Color(0xFFE8FFF0),
                            ),
                        ) {
                            Text(
                                text = when (probeKind) {
                                    ProbeKind.Chirp -> "Linear chirp"
                                    ProbeKind.Ping -> "Decaying ping"
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = probeMenuExpanded,
                            onDismissRequest = { probeMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Linear chirp") },
                                onClick = {
                                    probeKind = ProbeKind.Chirp
                                    probeMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Decaying ping") },
                                onClick = {
                                    probeKind = ProbeKind.Ping
                                    probeMenuExpanded = false
                                },
                            )
                        }
                    }

                    when (probeKind) {
                        ProbeKind.Chirp -> {
                            Text(text = "Duration: ${chirpDurationMs.toInt()} ms", color = Color(0xFFBDE7DC))
                            Slider(
                                value = chirpDurationMs,
                                onValueChange = { chirpDurationMs = it },
                                valueRange = 10f..60f,
                                steps = 0,
                            )
                            Text(text = "Start: ${chirpStartHz.toInt()} Hz", color = Color(0xFFBDE7DC))
                            Slider(
                                value = chirpStartHz,
                                onValueChange = {
                                    chirpStartHz = it.coerceAtMost(chirpEndHz - 500f)
                                },
                                valueRange = 12_000f..20_000f,
                                steps = 0,
                            )
                            Text(text = "End: ${chirpEndHz.toInt()} Hz", color = Color(0xFFBDE7DC))
                            Slider(
                                value = chirpEndHz,
                                onValueChange = {
                                    chirpEndHz = it.coerceAtLeast(chirpStartHz + 500f)
                                },
                                valueRange = 16_000f..24_000f,
                                steps = 0,
                            )
                        }

                        ProbeKind.Ping -> {
                            Text(text = "Duration: ${pingDurationMs.toInt()} ms", color = Color(0xFFBDE7DC))
                            Slider(
                                value = pingDurationMs,
                                onValueChange = { pingDurationMs = it },
                                valueRange = 30f..200f,
                                steps = 0,
                            )
                            Text(text = "Frequency: ${pingHz.toInt()} Hz", color = Color(0xFFBDE7DC))
                            Slider(
                                value = pingHz,
                                onValueChange = { pingHz = it },
                                valueRange = 12_000f..22_000f,
                                steps = 0,
                            )
                            Text(text = "Decay: ${pingDecay.toInt()}", color = Color(0xFFBDE7DC))
                            Slider(
                                value = pingDecay,
                                onValueChange = { pingDecay = it },
                                valueRange = 1f..12f,
                                steps = 0,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showTuningDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF7CFF43),
                    ),
                ) {
                    Text(text = "Done")
                }
            },
        )
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF04090A),
                            Color(0xFF071314),
                            Color(0xFF021010),
                        ),
                    ),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Phonar",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8FFF0),
            )
            Text(
                text = "Tap once to send a sonar sweep and lock the echo",
                color = Color(0xFF9CC9BE),
                textAlign = TextAlign.Center,
            )

            SonarRadarCard(
                phase = phase,
                pingCount = pingCount,
                sweep = scanSweep,
                wave = scanWave,
                reading = reading,
                error = error,
            )

            Button(
                enabled = phase != ScanPhase.Scanning,
                onClick = { startScan() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7CFF43),
                    contentColor = Color(0xFF07120D),
                ),
            ) {
                Text(
                    when (phase) {
                        ScanPhase.Idle -> "Start sonar"
                        ScanPhase.Scanning -> "Scanning..."
                        ScanPhase.Result -> "Scan again"
                        ScanPhase.Error -> "Retry"
                    },
                )
            }

            Button(
                enabled = phase != ScanPhase.Scanning,
                onClick = { showTuningDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF091716),
                    contentColor = Color(0xFFE8FFF0),
                ),
            ) {
                Text("Tuning")
            }

            Text(
                text = when (phase) {
                    ScanPhase.Idle -> "Ready. Keep volume moderate."
                    ScanPhase.Scanning -> "Ping $pingCount / $pingTotal"
                    ScanPhase.Result -> reading?.let {
                        String.format(Locale.US, "Estimated distance: %.2f m", it.distanceMeters)
                    } ?: "Echo locked"
                    ScanPhase.Error -> error ?: "Measurement failed"
                },
                color = Color(0xFFBDE7DC),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SonarRadarCard(
    phase: ScanPhase,
    pingCount: Int,
    sweep: Float,
    wave: Float,
    reading: SonarReading?,
    error: String?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.92f),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF061012)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) * 0.42f
                    val accent = Color(0xFF7CFF43)
                    val dimAccent = Color(0xFF7CFF43).copy(alpha = 0.22f)
                    val grid = Color(0xFF78D0C0).copy(alpha = 0.15f)

                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF0A1815),
                                Color(0xFF050D0E),
                            ),
                        ),
                    )

                    drawCircle(
                        color = Color(0xFF0B1B18),
                        radius = radius * 1.16f,
                        center = center,
                    )
                    drawCircle(
                        color = Color(0xFF11322B),
                        radius = radius * 1.02f,
                        center = center,
                        style = Stroke(width = 7f),
                    )

                    for (fraction in listOf(0.48f, 0.76f, 0.98f)) {
                        drawCircle(
                            color = grid,
                            radius = radius * fraction,
                            center = center,
                            style = Stroke(width = 2f),
                        )
                    }

                    drawLine(
                        color = grid,
                        start = Offset(center.x - radius, center.y),
                        end = Offset(center.x + radius, center.y),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )

                    if (phase == ScanPhase.Scanning) {
                        val sweepRad = sweep * (PI / 180f).toFloat()
                        val sweepLength = radius * 0.98f
                        val sweepEnd = Offset(
                            x = center.x + cos(sweepRad) * sweepLength,
                            y = center.y + sin(sweepRad) * sweepLength,
                        )
                        drawArc(
                            color = accent.copy(alpha = 0.16f),
                            startAngle = sweep - 14f,
                            sweepAngle = 28f,
                            useCenter = true,
                            size = Size(radius * 2f, radius * 2f),
                            topLeft = Offset(center.x - radius, center.y - radius),
                        )
                        drawLine(
                            color = accent,
                            start = center,
                            end = sweepEnd,
                            strokeWidth = 5f,
                            cap = StrokeCap.Round,
                        )
                    }

                    drawCircle(
                        color = Color(0xFFE6FFB8),
                        radius = 7f,
                        center = center,
                    )

                    if (phase == ScanPhase.Scanning) {
                        repeat(2) { index ->
                            val pulseOffset = ((wave + index * 0.45f) % 1f)
                            val pulseRadius = radius * (0.24f + pulseOffset * 0.54f)
                            val alpha = (0.26f - index * 0.08f).coerceAtLeast(0.06f) * (1f - pulseOffset)
                            drawCircle(
                                color = dimAccent.copy(alpha = alpha),
                                radius = pulseRadius,
                                center = center,
                                style = Stroke(width = 4f),
                            )
                        }
                    }

                    if (pingCount > 0) {
                        repeat(pingCount.coerceAtMost(3)) { index ->
                            val pulseRadius = radius * (0.22f + index * 0.10f)
                            drawCircle(
                                color = accent.copy(alpha = 0.20f - index * 0.04f),
                                radius = pulseRadius,
                                center = center,
                                style = Stroke(width = 3f),
                            )
                        }
                    }

                    val echoAngle = 25f
                    val echoRadius = radius * 0.55f
                    val echoX = center.x + cos(echoAngle * (PI / 180f).toFloat()) * echoRadius
                    val echoY = center.y + sin(echoAngle * (PI / 180f).toFloat()) * echoRadius
                    when {
                        reading != null && reading.distanceMeters >= 0f -> {
                            drawCircle(
                                color = Color(0xFFD6FF9A),
                                radius = 11f,
                                center = Offset(echoX, echoY),
                            )
                            drawCircle(
                                color = Color(0xFF8CFF3F).copy(alpha = 0.55f),
                                radius = 24f,
                                center = Offset(echoX, echoY),
                                style = Stroke(width = 3f),
                            )
                        }

                        phase == ScanPhase.Error -> {
                            drawCircle(
                                color = Color(0xFFFF7F7F),
                                radius = 10f,
                                center = Offset(echoX, echoY),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { index ->
                    val active = index < pingCount
                    val color = if (active) Color(0xFF7CFF43) else Color(0xFF335046)
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .height(8.dp)
                            .width(22.dp)
                            .background(color, RoundedCornerShape(999.dp)),
                    )
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                val barBins = reading?.autocorrelationBins?.takeIf { it.isNotEmpty() }
                val barCount = barBins?.size ?: 14
                val gap = size.width / barCount
                val maxHeight = size.height * 0.85f
                for (index in 0 until barCount) {
                    val normalized = index / (barCount - 1f)
                    val autocorr = barBins?.get(index)?.coerceIn(0f, 1f)
                    val barWave = sin((normalized * 5.5f + wave * 6.0f) * PI).toFloat()
                    val scanBoost = if (phase == ScanPhase.Scanning) 0.22f else 0.10f
                    val value = autocorr ?: (0.20f + scanBoost + barWave * 0.18f)
                    val height = (maxHeight * value).coerceAtLeast(8f)
                    val left = index * gap + gap * 0.22f
                    drawRoundRect(
                        color = Color(0xFF7CFF43).copy(alpha = (0.18f + normalized * 0.35f).coerceAtMost(0.7f)),
                        topLeft = Offset(left, size.height - height),
                        size = Size(gap * 0.42f, height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    )
                }
            }

            Text(
                text = when (phase) {
                    ScanPhase.Idle -> "Stand by for pings"
                    ScanPhase.Scanning -> "Sending pings..."
                    ScanPhase.Result -> reading?.let {
                        String.format(Locale.US, "Echo locked: %.2f m", it.distanceMeters)
                    } ?: "Echo locked"
                    ScanPhase.Error -> error ?: "Scan failed"
                },
                color = Color(0xFFE8FFF0),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// vim: set ts=4 sw=4 et:
