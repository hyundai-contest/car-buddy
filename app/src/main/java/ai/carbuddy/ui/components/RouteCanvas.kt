package ai.carbuddy.ui.components

import ai.carbuddy.card.RouteShape
import ai.carbuddy.ui.theme.AstaSans
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** Route background (UX_SPEC 4장 오늘의 길 스타일). */
val ROUTE_BG = Color(0xFF05080F)
private val IVORY = Color(0xFFF6F3EC)

/** SPEC 10.1 colours per kind. The line is never coloured by speed. */
fun routeKindColor(kind: String): Color = when (kind) {
    "JAM" -> Color(0xFF8C99B0)
    "LOOP" -> Color(0xFFF2A541)
    "DETOUR" -> Color(0xFF5FD4E0)
    "RAIN" -> Color(0xFF7FA8F2)
    "TUNNEL" -> Color(0xFFB48CF2)
    "WIND" -> Color(0xFFA8EEF5)
    else -> IVORY
}

/**
 * Draws a [RouteShape]: ivory line with a soft glow, episode-coloured stretches, hollow start/end
 * circles, pins with label chips, a zoom circle for a tiny loop, dotted straight lines over gaps.
 * The draw animation runs once over 2 s (UX 5.5) when [animate] is true.
 */
@Composable
fun RouteCanvas(
    shape: RouteShape,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    startLabel: String? = null,
    endLabel: String? = null,
    showPins: Boolean = true,
    showInset: Boolean = true,
    insetLabel: String? = null,
    liveDot: Boolean = false,
) {
    val drawIn = animate && !ai.carbuddy.ui.theme.LocalReduceMotion.current
    val progress = remember(shape) { Animatable(if (drawIn) 0f else 1f) }
    LaunchedEffect(shape) { if (drawIn) progress.animateTo(1f, tween(2_000, easing = LinearEasing)) }
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val p = progress.value
        val pts = shape.points
        if (pts.size < 2) return@Canvas
        val maxX = pts.maxOf { it[0] }.coerceAtLeast(1e-3)
        val maxY = pts.maxOf { it[1] }.coerceAtLeast(1e-3)
        val pad = min(size.width, size.height) * 0.12f
        val s = min((size.width - 2 * pad) / maxX.toFloat(), (size.height - 2 * pad) / maxY.toFloat())
        val ox = (size.width - maxX.toFloat() * s) / 2
        val oy = (size.height - maxY.toFloat() * s) / 2
        fun at(i: Int) = Offset(ox + pts[i][0].toFloat() * s, oy + pts[i][1].toFloat() * s)
        val shown = (pts.lastIndex * p).toInt().coerceIn(1, pts.lastIndex)
        val gapStarts = shape.gapsAfter.toSet()
        val unit = min(size.width, size.height) / 160f

        // glow + base line (gaps drawn separately as dotted straight lines)
        for (pass in 0..1) {
            val path = Path()
            var open = false
            for (i in 0..shown) {
                val o = at(i)
                if (!open || (i - 1) in gapStarts) { path.moveTo(o.x, o.y); open = true } else path.lineTo(o.x, o.y)
            }
            if (pass == 0) drawPath(path, IVORY.copy(alpha = 0.10f), style = Stroke(unit * 8, cap = StrokeCap.Round, join = StrokeJoin.Round))
            else drawPath(path, IVORY, style = Stroke(unit * 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        gapStarts.filter { it < shown }.forEach { i ->
            drawLine(IVORY.copy(alpha = 0.3f), at(i), at(i + 1), unit * 2f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(unit * 3, unit * 3)))
        }
        // episode stretches, bottom → top
        for (seg in shape.segments) {
            val to = min(seg.to, shown)
            if (to < seg.from) continue
            val color = routeKindColor(seg.kind)
            val path = Path().apply { moveTo(at(seg.from).x, at(seg.from).y); for (i in seg.from + 1..to) lineTo(at(i).x, at(i).y) }
            val effect = if (seg.kind == "JAM") PathEffect.dashPathEffect(floatArrayOf(unit * 2, unit * 2.5f)) else null
            if (seg.from == to) drawCircle(color, unit * 3.2f, at(seg.from))
            else drawPath(path, color, style = Stroke(unit * (if (seg.kind == "LOOP") 4.2f else 3f), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
        }
        // start / end: hollow circles (trimmed points)
        drawCircle(ROUTE_BG, unit * 3.4f, at(0)); drawCircle(IVORY, unit * 3.4f, at(0), style = Stroke(unit * 1.4f))
        // labels never cover each other: each one takes the first free spot around its anchor
        val placed = mutableListOf<androidx.compose.ui.geometry.Rect>()
        startLabel?.let { label(measurer, it, at(0), listOf(Offset(-unit * 4, unit * 5), Offset(-unit * 4, -unit * 12), Offset(unit * 5, -unit * 3)), unit, placed) }
        if (p >= 1f) {
            drawCircle(ROUTE_BG, unit * 3.4f, at(pts.lastIndex)); drawCircle(IVORY, unit * 3.4f, at(pts.lastIndex), style = Stroke(unit * 1.4f))
            endLabel?.let { label(measurer, it, at(pts.lastIndex), listOf(Offset(-unit * 14, -unit * 12), Offset(-unit * 14, unit * 5), Offset(unit * 5, -unit * 3)), unit, placed) }
        }
        if (liveDot) drawCircle(Color(0xFF5FD4E0), unit * 3.6f, at(pts.lastIndex))
        // pins appear one by one in time order
        if (showPins) shape.pins.forEachIndexed { k, pin ->
            if (pin.i > shown || p < (k + 1f) / (shape.pins.size + 1f)) return@forEachIndexed
            val o = at(pin.i.coerceIn(0, pts.lastIndex))
            val color = routeKindColor(pinKind(pin.type))
            if (pin.type == "REST_STOP") { drawCircle(IVORY, unit * 1.6f, o); chip(measurer, pin.label, o, spots(unit, below = true), Color(0xFF1B2D4F), IVORY, unit, placed); return@forEachIndexed }
            drawCircle(ROUTE_BG, unit * 3.4f, o); drawCircle(color, unit * 3.4f, o, style = Stroke(unit * 1.6f)); drawCircle(color, unit * 1.3f, o)
            val bg = if (pinKind(pin.type) == "LOOP") Color(0xFFF2A541) else if (pinKind(pin.type) in setOf("DETOUR", "RAIN")) color else Color(0xFF1B2D4F)
            val fg = if (bg == Color(0xFF1B2D4F)) IVORY else Color(0xFF0B1528)
            chip(measurer, pin.label, o, spots(unit, below = false), bg, fg, unit, placed)
        }
        // zoom circle for a loop too small to see (SPEC 10.1 확대 원)
        val inset = shape.insets.firstOrNull()
        if (showInset && inset != null && p >= 1f && inset.points.size > 2) {
            val r = min(size.width, size.height) * 0.16f
            val c = Offset(size.width - r - pad * 0.4f, size.height - r - pad * 0.9f)
            drawLine(Color(0xFFF2A541), at(inset.at.coerceIn(0, pts.lastIndex)), c - Offset(0f, r), unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(unit * 2, unit * 2)))
            drawCircle(ROUTE_BG, r, c); drawCircle(Color(0xFFF2A541), r, c, style = Stroke(unit * 1.4f))
            val ix = inset.points.maxOf { it[0] }.coerceAtLeast(1e-3).toFloat(); val iy = inset.points.maxOf { it[1] }.coerceAtLeast(1e-3).toFloat()
            val ss = r * 1.25f / maxOf(ix, iy)
            val io = c - Offset(ix * ss / 2, iy * ss / 2)
            val path = Path().apply {
                inset.points.forEachIndexed { i, q -> val o = io + Offset(q[0].toFloat() * ss, q[1].toFloat() * ss); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            }
            drawPath(path, Color(0xFFF2A541), style = Stroke(unit * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            (insetLabel ?: inset.label).takeIf { it.isNotEmpty() }?.let { chip(measurer, it, c, listOf(Offset(-r * 0.8f, r + unit * 3), Offset(-r * 0.8f, -r - unit * 10)), Color(0xFFF2A541), Color(0xFF0B1528), unit, placed) }
        }
    }
}

private fun pinKind(type: String) = when (type) {
    "JAM_ENDURED" -> "JAM"; "LOST_LOOP", "PARKING_HUNT" -> "LOOP"; "DETOUR" -> "DETOUR"; "SUDDEN_RAIN" -> "RAIN"
    "TUNNEL_PARADE" -> "TUNNEL"; "WIND_RUN" -> "WIND"; "TEMP_WAR" -> "TEMP"; else -> "OTHER"
}

/** Candidate chip spots around a pin, preferred one first (above-right, or below-right for rest stops). */
private fun spots(unit: Float, below: Boolean): List<Offset> {
    val up = listOf(Offset(unit * 4, -unit * 11), Offset(unit * 4, -unit * 20), Offset(-unit * 30, -unit * 11))
    val down = listOf(Offset(unit * 4, unit * 2), Offset(unit * 4, unit * 11), Offset(-unit * 30, unit * 2))
    return if (below) down + up else up + down
}

/**
 * First candidate (anchor + offset) whose box stays inside the canvas and overlaps nothing already
 * placed; if none is free, the least-overlapping one. The chosen box is added to [placed].
 */
private fun DrawScope.pick(anchor: Offset, candidates: List<Offset>, box: Size, placed: MutableList<androidx.compose.ui.geometry.Rect>): Offset {
    fun rect(o: Offset) = androidx.compose.ui.geometry.Rect(anchor + o, box)
    fun overlap(r: androidx.compose.ui.geometry.Rect) = placed.sumOf { p -> val i = r.intersect(p); if (i.width > 0 && i.height > 0) (i.width * i.height).toDouble() else 0.0 }
    fun inside(r: androidx.compose.ui.geometry.Rect) = r.left >= 0 && r.top >= 0 && r.right <= size.width && r.bottom <= size.height
    val best = candidates.firstOrNull { val r = rect(it); inside(r) && overlap(r) == 0.0 }
        ?: candidates.minBy { val r = rect(it); overlap(r) + if (inside(r)) 0.0 else 1e7 }
    return (anchor + best).also { placed += androidx.compose.ui.geometry.Rect(it, box) }
}

private fun DrawScope.label(m: TextMeasurer, text: String, anchor: Offset, candidates: List<Offset>, unit: Float, placed: MutableList<androidx.compose.ui.geometry.Rect>) {
    val r = m.measure(text, TextStyle(fontFamily = AstaSans, fontSize = 14.sp, color = IVORY.copy(alpha = 0.85f)))
    drawText(r, topLeft = pick(anchor, candidates, Size(r.size.width.toFloat(), r.size.height.toFloat()), placed))
}

private fun DrawScope.chip(m: TextMeasurer, text: String, anchor: Offset, candidates: List<Offset>, bg: Color, fg: Color, unit: Float, placed: MutableList<androidx.compose.ui.geometry.Rect>) {
    val r = m.measure(text, TextStyle(fontFamily = AstaSans, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = fg))
    val padX = unit * 3; val padY = unit * 1.2f
    val box = Size(r.size.width + padX * 2, r.size.height + padY * 2)
    val at = pick(anchor, candidates, box, placed)
    drawRoundRect(bg, at, box, CornerRadius(r.size.height.toFloat()))
    drawText(r, topLeft = at + Offset(padX, padY))
}
