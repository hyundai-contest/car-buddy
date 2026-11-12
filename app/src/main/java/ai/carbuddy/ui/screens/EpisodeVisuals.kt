package ai.carbuddy.ui.screens

import ai.carbuddy.card.CardEpisode
import ai.carbuddy.ui.components.drawEpisodeGlyph
import ai.carbuddy.ui.components.episodeColor
import ai.carbuddy.ui.theme.Cb
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Screening visuals drawn from the trip's real data (UX_SPEC 4장). One animation of ≤ 2 s, then
 * still (no loops). The loop trajectory exists only here, on the car screen — never on a share card.
 */
@Composable
fun EpisodeVisual(e: CardEpisode, track: List<Pair<Double, Double>>, note: String?, modifier: Modifier = Modifier) {
    val c = Cb.colors
    val still = ai.carbuddy.ui.theme.LocalReduceMotion.current
    val progress = remember(e.id) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(e.id) { if (!still) { progress.snapTo(0f); progress.animateTo(1f, tween(1_800, easing = LinearEasing)) } }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = ai.carbuddy.ui.theme.AstaSans, fontSize = 15.sp, color = VISUAL_INK)
    val bigStyle = TextStyle(fontFamily = ai.carbuddy.ui.theme.AstaSans, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = VISUAL_INK)
    val tint = episodeColor(e.type, c.amber, c.cyan)
    Box(
        modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF0E1B33)).border(1.dp, c.outline, RoundedCornerShape(24.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            roads()
            val p = progress.value
            when (e.type) {
                "LOST_LOOP" -> loop(track, p, c.amber, c.cyan, measurer, labelStyle, e.params["count"] ?: "3")
                "DETOUR" -> detour(p, c.cyan, measurer, labelStyle, e.params["fromName"], e.params["toName"])
                "SUDDEN_RAIN" -> rain(p, tint, measurer, labelStyle, e.params["highMinutes"] ?: "")
                "TEMP_WAR" -> tempWar(p, measurer, bigStyle, labelStyle, e.params["driverC"] ?: "", e.params["passengerC"] ?: "")
                "JAM_ENDURED" -> timeBar(p, (e.params["minutes"] ?: "0").toIntOrNull() ?: 0, 60, tint, measurer, bigStyle)
                "REST_STOP" -> timer(p, (e.params["minutes"] ?: "0").toIntOrNull() ?: 0, tint, measurer, bigStyle)
                else -> {
                    val side = min(size.width, size.height) * 0.3f
                    inset((size.width - side) / 2, (size.height - side) / 2) { drawEpisodeGlyph(e.type, tint) }
                }
            }
        }
        if (note != null) Text(note, style = Cb.type.caption, color = c.textSecondary, modifier = Modifier.align(Alignment.TopStart).padding(20.dp))
    }
}

/** The visual panel is always dark navy, so its text is always ivory. */
private val VISUAL_INK = Color(0xFFF6F3EC)

private fun DrawScope.roads() {
    val road = Color(0xFF1C2D4D)
    val w = size.width; val h = size.height
    drawLine(road, Offset(w * 0.18f, 0f), Offset(w * 0.32f, h), 26f)
    drawLine(road, Offset(w * 0.55f, 0f), Offset(w * 0.55f, h), 22f)
    drawLine(road, Offset(w * 0.78f, 0f), Offset(w * 0.9f, h), 22f)
    drawLine(road, Offset(0f, h * 0.55f), Offset(w, h * 0.55f), 26f)
}

private fun DrawScope.label(m: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    val r = m.measure(text, style)
    drawText(r, topLeft = Offset(at.x, at.y))
}

/** Real loop trajectory, normalised into the box; falls back to a drawn figure-eight. */
private fun DrawScope.loop(track: List<Pair<Double, Double>>, p: Float, amber: Color, cyan: Color, m: TextMeasurer, style: TextStyle, count: String) {
    val path = Path()
    val pts: List<Offset> = if (track.size >= 6) {
        val lat0 = track.map { it.first }.average()
        val k = cos(Math.toRadians(lat0))
        val xs = track.map { it.second * k }; val ys = track.map { it.first }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val pad = min(size.width, size.height) * 0.14f
        val scale = (min(size.width, size.height) - 2 * pad) / span
        val ox = (size.width - (maxX - minX) * scale) / 2; val oy = (size.height - (maxY - minY) * scale) / 2
        xs.indices.map { i -> Offset((ox + (xs[i] - minX) * scale).toFloat(), (oy + (maxY - ys[i]) * scale).toFloat()) }
    } else {
        val w = size.width; val h = size.height; val cx = w * 0.55f; val cy = h * 0.55f; val r = min(w, h) * 0.22f
        listOf(Offset(w * 0.1f, cy), Offset(cx, cy), Offset(cx, cy - r), Offset(cx + r, cy - r), Offset(cx + r, cy), Offset(cx, cy),
            Offset(cx, cy + r), Offset(cx - r, cy + r), Offset(cx - r, cy), Offset(cx, cy), Offset(w * 0.9f, cy))
    }
    pts.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
    val measure = PathMeasure().apply { setPath(path, false) }
    val partial = Path()
    measure.getSegment(0f, measure.length * p, partial, true)
    drawPath(partial, amber, style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(cyan, 12f, pts.first()); if (p >= 1f) drawCircle(cyan, 12f, pts.last())
    // the junction we kept coming back to: the most visited point
    val junction = pts.maxByOrNull { a -> pts.count { b -> (a - b).getDistance() < 18f } } ?: pts.first()
    if (p > 0.6f) {
        drawCircle(Color.White, 34f, junction, style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        label(m, "같은 길 ${count}번 통과", junction + Offset(44f, 20f), style)
    }
}

private fun DrawScope.detour(p: Float, cyan: Color, m: TextMeasurer, style: TextStyle, from: String?, to: String?) {
    val w = size.width; val h = size.height
    val start = Offset(w * 0.12f, h * 0.72f); val bend = Offset(w * 0.5f, h * 0.6f)
    val old = Offset(w * 0.86f, h * 0.68f); val new = Offset(w * 0.78f, h * 0.24f)
    drawLine(Color(0xFF5B6B86), bend, old, 6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
    pin(old, Color(0xFF8A96AA)); from?.let { label(m, it, old + Offset(-20f, 26f), style) }
    val path = Path().apply { moveTo(start.x, start.y); lineTo(bend.x, bend.y); quadraticTo(w * 0.66f, h * 0.5f, new.x, new.y + 24f) }
    val pm = PathMeasure().apply { setPath(path, false) }
    val part = Path(); pm.getSegment(0f, pm.length * p, part, true)
    drawPath(part, cyan, style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (p > 0.85f) { pin(new, cyan); to?.let { label(m, it, new + Offset(28f, -14f), style) } }
}

private fun DrawScope.pin(at: Offset, color: Color) {
    drawCircle(color, 22f, at)
    drawCircle(Color(0xFF0E1B33), 8f, at)
    val tip = Path().apply { moveTo(at.x - 14f, at.y + 12f); lineTo(at.x + 14f, at.y + 12f); lineTo(at.x, at.y + 38f); close() }
    drawPath(tip, color)
}

private fun DrawScope.rain(p: Float, tint: Color, m: TextMeasurer, style: TextStyle, highMinutes: String) {
    val w = size.width; val h = size.height
    // heavy rain that stops, then the sun (3 frames over the animation)
    val intensity = when { p < 0.45f -> p / 0.45f; p < 0.8f -> 1f - (p - 0.45f) / 0.35f; else -> 0f }
    val n = (60 * intensity).toInt()
    for (i in 0 until n) {
        val x = (i * 97 % 100) / 100f * w; val y = (i * 53 % 100) / 100f * h * 0.75f
        drawLine(tint.copy(alpha = 0.8f), Offset(x + 10f, y), Offset(x - 8f, y + 34f), 4f, StrokeCap.Round)
    }
    if (p > 0.75f) drawCircle(Color(0xFFF2C14E).copy(alpha = ((p - 0.75f) / 0.25f).coerceIn(0f, 1f)), min(w, h) * 0.1f, Offset(w * 0.78f, h * 0.24f))
    // wiper stage bar Off → High
    val labels = listOf("OFF", "LOW", "HIGH")
    val stage = when { p < 0.15f -> 0; p < 0.3f -> 1; p < 0.75f -> 2; else -> 0 }
    labels.forEachIndexed { i, s ->
        val x = w * 0.1f + i * w * 0.18f
        drawRoundRect(if (i <= stage) tint else Color(0xFF2A3B5C), Offset(x, h * 0.84f), Size(w * 0.16f, 14f), androidx.compose.ui.geometry.CornerRadius(7f))
        label(m, s, Offset(x, h * 0.84f - 30f), style.copy(fontSize = 12.sp))
    }
    if (highMinutes.isNotEmpty()) label(m, "HIGH ${highMinutes}분", Offset(w * 0.66f, h * 0.8f), style)
}

private fun DrawScope.tempWar(p: Float, m: TextMeasurer, big: TextStyle, style: TextStyle, d: String, pass: String) {
    val w = size.width; val h = size.height
    val blue = Color(0xFF6FA8FF); val orange = Color(0xFFF2A541)
    fun gauge(x: Float, value: String, color: Color, label: String) {
        val v = value.toFloatOrNull() ?: 22f
        val frac = ((v - 16f) / 14f).coerceIn(0.05f, 1f) * p
        drawRoundRect(Color(0xFF22345A), Offset(x, h * 0.18f), Size(w * 0.12f, h * 0.6f), androidx.compose.ui.geometry.CornerRadius(30f))
        drawRoundRect(color, Offset(x, h * 0.18f + h * 0.6f * (1 - frac)), Size(w * 0.12f, h * 0.6f * frac), androidx.compose.ui.geometry.CornerRadius(30f))
        label(m, "${value}°", Offset(x + w * 0.15f, h * 0.36f), big.copy(color = color))
        label(m, label, Offset(x + w * 0.15f, h * 0.36f + 64f), style)
    }
    gauge(w * 0.12f, d, blue, "운전석")
    gauge(w * 0.56f, pass, orange, "조수석")
}

private fun DrawScope.timeBar(p: Float, minutes: Int, maxMin: Int, tint: Color, m: TextMeasurer, big: TextStyle) {
    val w = size.width; val h = size.height
    val frac = (minutes.toFloat() / maxMin).coerceIn(0.05f, 1f) * p
    drawRoundRect(Color(0xFF22345A), Offset(w * 0.1f, h * 0.62f), Size(w * 0.8f, 36f), androidx.compose.ui.geometry.CornerRadius(18f))
    drawRoundRect(tint, Offset(w * 0.1f, h * 0.62f), Size(w * 0.8f * frac, 36f), androidx.compose.ui.geometry.CornerRadius(18f))
    label(m, "${(minutes * p).toInt()}분", Offset(w * 0.1f, h * 0.36f), big)
}

private fun DrawScope.timer(p: Float, minutes: Int, tint: Color, m: TextMeasurer, big: TextStyle) {
    val w = size.width; val h = size.height
    val r = min(w, h) * 0.3f
    val c = Offset(w / 2, h / 2)
    drawCircle(Color(0xFF22345A), r, c, style = Stroke(26f))
    drawArc(tint, -90f, 360f * (minutes / 60f).coerceIn(0.05f, 1f) * p, false, c - Offset(r, r), Size(r * 2, r * 2), style = Stroke(26f, cap = StrokeCap.Round))
    val text = m.measure("${(minutes * p).toInt()}분", big)
    drawText(text, topLeft = c - Offset(text.size.width / 2f, text.size.height / 2f))
}
