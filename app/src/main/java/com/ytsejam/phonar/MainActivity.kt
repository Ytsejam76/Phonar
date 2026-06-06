// Copyright (c) 2026 Elias S. G. Carotti
package com.ytsejam.phonar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

        reading = null
        error = null
        pingCount = 0
        phase = ScanPhase.Scanning

        scope.launch {
            runCatching {
                val measurement = async { engine.measure(pingCount = 3) }

                repeat(3) { index ->
                    pingCount = index + 1
                    delay(190)
                }

                reading = measurement.await()
                phase = ScanPhase.Result
            }.onFailure {
                error = it.message ?: "Measurement failed"
                phase = ScanPhase.Error
            }
        }
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
                text = "Tap to send a sonar burst and lock onto the echo",
                color = Color(0xFF9CC9BE),
                textAlign = TextAlign.Center,
            )

            SonarRadarCard(
                phase = phase,
                pingCount = pingCount,
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

            Text(
                text = when (phase) {
                    ScanPhase.Idle -> "Ready. Volume should be moderate."
                    ScanPhase.Scanning -> "Ping $pingCount / 3"
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
    reading: SonarReading?,
    error: String?,
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave",
    )

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
                    val sweepAngle = sweep

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
                        radius = radius * 1.18f,
                        center = center,
                    )
                    drawCircle(
                        color = Color(0xFF11322B),
                        radius = radius * 1.02f,
                        center = center,
                        style = Stroke(width = 7f),
                    )

                    for (fraction in listOf(0.25f, 0.5f, 0.75f, 1f)) {
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
                    drawLine(
                        color = grid,
                        start = Offset(center.x, center.y - radius),
                        end = Offset(center.x, center.y + radius),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )

                    val sweepRad = sweepAngle * (PI / 180f).toFloat()
                    val sweepLength = radius * 0.98f
                    val sweepEnd = Offset(
                        x = center.x + cos(sweepRad) * sweepLength,
                        y = center.y + sin(sweepRad) * sweepLength,
                    )
                    drawArc(
                        color = accent.copy(alpha = 0.18f),
                        startAngle = sweepAngle - 18f,
                        sweepAngle = 34f,
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
                    drawCircle(
                        color = Color(0xFFE6FFB8),
                        radius = 7f,
                        center = center,
                    )

                    repeat(3) { index ->
                        val pulseOffset = ((wavePhase + index * 0.32f) % 1f)
                        val pulseRadius = radius * (0.18f + pulseOffset * 0.62f)
                        val alpha = (0.34f - index * 0.08f).coerceAtLeast(0.08f) * (1f - pulseOffset)
                        drawCircle(
                            color = dimAccent.copy(alpha = alpha),
                            radius = pulseRadius,
                            center = center,
                            style = Stroke(width = 4f),
                        )
                    }

                    if (pingCount > 0) {
                        repeat(pingCount) { index ->
                            val pulseRadius = radius * (0.22f + index * 0.09f)
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
                    if (reading != null && reading.distanceMeters >= 0f) {
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
                    } else if (phase == ScanPhase.Error) {
                        drawCircle(
                            color = Color(0xFFFF7F7F),
                            radius = 10f,
                            center = Offset(echoX, echoY),
                        )
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
                val barCount = 26
                val gap = size.width / barCount
                val maxHeight = size.height * 0.85f
                for (index in 0 until barCount) {
                    val normalized = index / (barCount - 1f)
                    val wave = sin((normalized * 7.2f + wavePhase * 9f) * PI).toFloat()
                    val scanBoost = if (phase == ScanPhase.Scanning) 0.25f else 0.12f
                    val height = (maxHeight * (0.18f + scanBoost + wave * 0.22f)).coerceAtLeast(8f)
                    val left = index * gap + gap * 0.22f
                    drawRoundRect(
                        color = Color(0xFF7CFF43).copy(alpha = 0.18f + normalized * 0.35f),
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