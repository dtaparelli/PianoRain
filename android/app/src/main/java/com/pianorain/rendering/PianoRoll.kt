package com.pianorain.rendering

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pianorain.domain.NoteTimeline
import com.pianorain.ui.HighNote
import com.pianorain.ui.Ink
import com.pianorain.ui.LowNote
import kotlin.math.ceil

@Composable
fun PianoRoll(timeline: NoteTimeline, time: State<Long>, modifier: Modifier = Modifier) {
    val layout = remember(timeline) { PianoLayout(timeline.notes) }
    val index = remember(timeline) { TimelineIndex(timeline.notes) }
    val keys = remember(layout) { layout.keys.associateBy { it.pitch } }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(67, 48, 85); textAlign = Paint.Align.CENTER } }
    val countPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(244, 237, 248); textAlign = Paint.Align.CENTER } }
    Canvas(modifier.semantics { contentDescription = "Piano roll. Falling notes align with the keys; lilac is lower register and rose is higher register." }) {
        val current = time.value
        val keyboardHeight = size.height * .28f
        val strike = size.height - keyboardHeight
        val lookaheadUs = 3_000_000L
        val speed = strike / 3f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF100B18), Color(0xFF1A1224))))
        for (key in layout.keys.filter { !it.black }) {
            val x = key.left * size.width
            drawLine(Color(0xFF251C30), Offset(x, 0f), Offset(x, strike), 1f)
        }
        val visible = index.visible(current, lookaheadUs)
        val active = visible.filter { it.startUs <= current && current < it.endUs }.map { it.pitch }.toSet()
        clipRect(0f, 0f, size.width, strike) {
            val firstSecond = current / 1_000_000
            for (second in firstSecond..(firstSecond + 4)) {
                val y = strike - (second * 1_000_000 - current) / 1_000_000f * speed
                drawLine(Color(0xFF33253F).copy(alpha = .4f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            for (note in visible) {
                val key = keys[note.pitch] ?: continue
                val bounds = tileBounds(note, current, strike, speed)
                val gap = (key.width * size.width * .09f).coerceAtMost(2f * density)
                val width = key.width * size.width - gap * 2
                val height = (bounds.bottom - bounds.top).coerceAtLeast(2f)
                val color = if (note.pitch < 60) LowNote else HighNote
                drawRoundRect(Brush.verticalGradient(listOf(color.copy(alpha = .72f), color), startY = bounds.top, endY = bounds.top + height),
                    Offset(key.left * size.width + gap, bounds.top), Size(width, height), CornerRadius(minOf(4f * density, width * .22f)))
                if (note.pitch in active) drawRect(color.copy(alpha = .10f), Offset(key.left * size.width, strike - 20 * density), Size(key.width * size.width, 20 * density))
            }
        }
        for (pitch in active) {
            val key = keys[pitch] ?: continue
            val color = if (pitch < 60) LowNote else HighNote
            val radius = key.width * size.width * 1.5f
            val center = Offset((key.left + key.width / 2) * size.width, strike)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .5f), Color.Transparent), center = center, radius = radius), radius, center)
        }
        drawLine(LilacLine.copy(alpha = .65f), Offset(0f, strike), Offset(size.width, strike), 1.5f * density)
        for (key in layout.keys.filter { !it.black }) {
            val lit = key.pitch in active
            val color = if (lit) { if (key.pitch < 60) LowNote else HighNote } else Ink
            val x = key.left * size.width
            drawRoundRect(Brush.verticalGradient(listOf(color, if (lit) color.copy(alpha = .85f) else Color(0xFFD9CDE4)), startY = strike, endY = size.height), Offset(x + 1f, strike + 3f * density), Size((key.width * size.width - 2f).coerceAtLeast(1f), keyboardHeight - 3f * density), CornerRadius(2f * density))
            if (key.pitch % 12 == 0 && key.width * size.width > 10f * density) {
                labelPaint.textSize = (key.width * size.width * .65f).coerceAtMost(10f * density)
                drawContext.canvas.nativeCanvas.drawText(PianoLayout.label(key.pitch), x + key.width * size.width / 2, size.height - 7f * density, labelPaint)
            }
        }
        for (key in layout.keys.filter { it.black }) {
            val color = if (key.pitch in active) { if (key.pitch < 60) LowNote else HighNote } else Color(0xFF2B2139)
            drawRoundRect(Brush.verticalGradient(listOf(color, if (key.pitch in active) color else Color(0xFF45334F)), startY = strike, endY = strike + keyboardHeight * .61f), Offset(key.left * size.width, strike + 3f * density), Size(key.width * size.width, keyboardHeight * .61f), CornerRadius(2f * density))
        }
        if (current < 0) {
            countPaint.textSize = 13f * density
            drawContext.canvas.nativeCanvas.drawText("READY IN ${ceil(-current / 1_000_000.0).toInt()}", size.width / 2, size.height * .25f, countPaint)
        }
    }
}
private val LilacLine = Color(0xFFC8ABFF)
