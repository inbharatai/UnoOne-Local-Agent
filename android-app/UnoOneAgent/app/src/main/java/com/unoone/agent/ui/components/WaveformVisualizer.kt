package com.unoone.agent.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun WaveformVisualizer(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF6750A4),
    inactiveColor: Color = Color(0xFFE0E0E0),
    barCount: Int = 40
) {
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isActive) amplitude else 0f,
        animationSpec = tween(durationMillis = 100),
        label = "waveform_amplitude"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "waveform_phase")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveform_phase"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
        val barWidth = size.width / barCount
        val centerY = size.height / 2f

        for (i in 0 until barCount) {
            val x = i * barWidth + barWidth / 2f
            val normalizedPos = i.toFloat() / barCount

            // Create a wave pattern that responds to amplitude
            val waveOffset = sin(normalizedPos * Math.PI.toFloat() + phase + i * 0.2f)
            val barHeight = if (isActive && animatedAmplitude > 0.01f) {
                // Active: amplitude-driven wave with organic movement
                (animatedAmplitude * size.height * 0.8f * (0.3f + 0.7f * (waveOffset * 0.5f + 0.5f)))
                    .coerceIn(4f, size.height * 0.9f)
            } else {
                // Inactive: subtle idle wave
                (4f + 2f * sin(normalizedPos * Math.PI.toFloat() + phase * 0.5f))
            }

            val color = if (isActive) barColor else inactiveColor
            drawLine(
                color = color,
                start = Offset(x, centerY - barHeight / 2f),
                end = Offset(x, centerY + barHeight / 2f),
                strokeWidth = barWidth * 0.6f
            )
        }
    }
}