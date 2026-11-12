package ai.carbuddy.ui.components

import ai.carbuddy.card.Award
import ai.carbuddy.card.CardEpisode
import ai.carbuddy.card.Receipt
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.Dimens
import ai.carbuddy.ui.theme.Motion
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonKind { PRIMARY, SECONDARY, AMBER, GHOST }

/**
 * Pleos touch rule: pressed → 95 % scale + background change; release outside cancels
 * (Compose clickable already cancels when the pointer leaves). Min 64 dp, primary 72 dp.
 */
@Composable
fun CbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
) {
    val c = Cb.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(Motion.SHORT), label = "press")
    val base = when (kind) {
        ButtonKind.PRIMARY -> c.cyan
        ButtonKind.AMBER -> c.amber
        ButtonKind.SECONDARY, ButtonKind.GHOST -> Color.Transparent
    }
    val pressedBg = when (kind) {
        ButtonKind.PRIMARY, ButtonKind.AMBER -> base.copy(alpha = 0.78f)
        else -> c.bgSurface2
    }
    val bg by animateColorAsState(if (pressed) pressedBg else base, tween(Motion.SHORT), label = "bg")
    val fg = when (kind) { ButtonKind.PRIMARY, ButtonKind.AMBER -> c.onAccent; else -> c.textPrimary }
    val height = if (kind == ButtonKind.PRIMARY || kind == ButtonKind.AMBER) Dimens.primaryButton else Dimens.secondaryButton
    Box(
        modifier
            .defaultMinSize(minWidth = Dimens.touch, minHeight = height)
            .scale(scale)
            .clip(RoundedCornerShape(Dimens.radiusButton))
            .background(if (enabled) bg else c.bgSurface2)
            .then(if (kind == ButtonKind.SECONDARY) Modifier.border(2.dp, c.outline, RoundedCornerShape(Dimens.radiusButton)) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Cb.type.button, color = if (enabled) fg else c.textSecondary, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Square icon button (64 dp) with the same press behaviour. */
@Composable
fun CbIconButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, draw: DrawScope.(Color) -> Unit) {
    val c = Cb.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(Motion.SHORT), label = "press")
    Box(
        modifier.size(Dimens.touch).scale(scale).clip(RoundedCornerShape(18.dp))
            .background(if (pressed) c.bgSurface2 else c.bgSurface)
            .border(1.dp, c.outline, RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val tint = c.textPrimary
        Canvas(Modifier.size(28.dp)) { draw(tint) }
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, border: Color? = null) {
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(999.dp)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(text, style = Cb.type.label.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1) }
}

// ------------------------------------------------------------------------------------- buddy

enum class BuddyMood { DEFAULT, HAPPY, DIZZY, SLEEPY, SWEAT, SPARKLE }

fun moodFor(type: String?): BuddyMood = when (type) {
    "LOST_LOOP" -> BuddyMood.DIZZY
    "DETOUR" -> BuddyMood.SPARKLE
    "JAM_ENDURED" -> BuddyMood.SLEEPY
    "TEMP_WAR" -> BuddyMood.SWEAT
    "SUDDEN_RAIN", "REST_STOP" -> BuddyMood.HAPPY
    else -> BuddyMood.DEFAULT
}

/**
 * UX_SPEC 5.4 BuddyAvatar: body + face + optional item badge. Sizes S48 / M84 / L140.
 * [stage] (확장 A) adds a small mark: EGG shell, SPROUT leaf, COMPANION scarf, VETERAN cap.
 */
@Composable
fun BuddyAvatar(size: Dp, mood: BuddyMood = BuddyMood.DEFAULT, item: String? = null, modifier: Modifier = Modifier, stage: String? = null) {
    val c = Cb.colors
    val outlineless = c.isDark
    Box(modifier.size(size).semantics { contentDescription = "버디" }) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val body = Color(0xFFF6F3EC)
            val ink = Color(0xFF12203A)
            drawOval(body, topLeft = Offset(w * 0.04f, w * 0.1f), size = Size(w * 0.92f, w * 0.84f))
            if (!outlineless) drawOval(ink.copy(alpha = 0.25f), topLeft = Offset(w * 0.04f, w * 0.1f), size = Size(w * 0.92f, w * 0.84f), style = Stroke(w * 0.02f))
            val eyeY = w * 0.44f
            val lx = w * 0.36f; val rx = w * 0.64f
            val sw = w * 0.045f
            when (mood) {
                BuddyMood.DIZZY -> for (x in listOf(lx, rx)) {
                    val d = w * 0.06f
                    drawLine(ink, Offset(x - d, eyeY - d), Offset(x + d, eyeY + d), sw, StrokeCap.Round)
                    drawLine(ink, Offset(x - d, eyeY + d), Offset(x + d, eyeY - d), sw, StrokeCap.Round)
                }
                BuddyMood.SLEEPY -> for (x in listOf(lx, rx)) drawLine(ink, Offset(x - w * 0.06f, eyeY), Offset(x + w * 0.06f, eyeY), sw, StrokeCap.Round)
                BuddyMood.SPARKLE -> for (x in listOf(lx, rx)) {
                    drawCircle(ink, w * 0.065f, Offset(x, eyeY)); drawCircle(body, w * 0.022f, Offset(x + w * 0.02f, eyeY - w * 0.02f))
                }
                else -> for (x in listOf(lx, rx)) drawCircle(ink, w * 0.05f, Offset(x, eyeY))
            }
            val mouthY = w * 0.6f
            when (mood) {
                BuddyMood.DIZZY, BuddyMood.SWEAT -> drawArc(ink, 200f, 140f, false, Offset(w * 0.42f, mouthY), Size(w * 0.16f, w * 0.1f), style = Stroke(sw, cap = StrokeCap.Round))
                BuddyMood.SLEEPY -> drawCircle(ink, w * 0.035f, Offset(w * 0.5f, mouthY + w * 0.04f), style = Stroke(sw * 0.8f))
                else -> drawArc(ink, 20f, 140f, false, Offset(w * 0.4f, mouthY - w * 0.04f), Size(w * 0.2f, w * 0.12f), style = Stroke(sw, cap = StrokeCap.Round))
            }
            if (mood == BuddyMood.SWEAT) drawOval(Color(0xFF5FD4E0), Offset(w * 0.72f, w * 0.26f), Size(w * 0.07f, w * 0.11f))
            stageMark(stage, w, ink)
        }
        if (item != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.34f).clip(CircleShape).background(c.amber)
                    .border(size * 0.03f, c.bgBase, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(size * 0.2f)) { itemGlyph(item, this.size.width, Color(0xFF12203A)) }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.stageMark(stage: String?, w: Float, ink: Color) {
    when (stage) {
        "EGG" -> {                                                           // cracked shell around the bottom
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.1f, w * 0.72f)
                listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f).forEachIndexed { i, x -> lineTo(w * x, if (i % 2 == 0) w * 0.64f else w * 0.72f) }
                lineTo(w * 0.86f, w * 0.86f); quadraticTo(w * 0.5f, w * 1.0f, w * 0.14f, w * 0.86f); close()
            }
            drawPath(p, Color(0xFFE9E1CF))
            drawPath(p, ink.copy(alpha = 0.35f), style = Stroke(w * 0.015f))
        }
        "SPROUT" -> {                                                        // leaf on top
            drawLine(Color(0xFF3FA36B), Offset(w * 0.5f, w * 0.12f), Offset(w * 0.5f, w * 0.02f), w * 0.03f, StrokeCap.Round)
            drawOval(Color(0xFF5CC489), Offset(w * 0.5f, -w * 0.02f), Size(w * 0.16f, w * 0.09f))
        }
        "COMPANION" -> drawRoundRect(Color(0xFFF2A541), Offset(w * 0.22f, w * 0.78f), Size(w * 0.56f, w * 0.08f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
        "VETERAN" -> {                                                       // little cap
            drawArc(Color(0xFF3B5FC2), 180f, 180f, true, Offset(w * 0.3f, w * 0.04f), Size(w * 0.4f, w * 0.22f))
            drawRoundRect(Color(0xFF3B5FC2), Offset(w * 0.46f, w * 0.12f), Size(w * 0.3f, w * 0.04f), androidx.compose.ui.geometry.CornerRadius(w * 0.02f))
        }
    }
}

/** 기획안 확장 A 아이템 8종의 작은 표식. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.itemGlyph(item: String?, w: Float, ink: Color) {
    val s = Stroke(w * 0.1f, cap = StrokeCap.Round)
    when (item) {
        "COMPASS" -> { drawCircle(ink, w * 0.42f, Offset(w / 2, w / 2), style = s); drawLine(ink, Offset(w * 0.35f, w * 0.65f), Offset(w * 0.65f, w * 0.35f), w * 0.12f, StrokeCap.Round) }
        "MAP" -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(0f, w * 0.2f); lineTo(w * 0.33f, 0f); lineTo(w * 0.66f, w * 0.2f); lineTo(w, 0f); lineTo(w, w * 0.8f); lineTo(w * 0.66f, w); lineTo(w * 0.33f, w * 0.8f); lineTo(0f, w); close() }; drawPath(p, ink, style = Stroke(w * 0.08f)) }
        "RAINCOAT" -> { drawArc(ink, 180f, 180f, true, Offset(0f, w * 0.1f), Size(w, w * 0.8f)); drawLine(ink, Offset(w / 2, w * 0.5f), Offset(w / 2, w * 0.95f), w * 0.08f, StrokeCap.Round) }
        "HANDY_FAN" -> { drawCircle(ink, w * 0.3f, Offset(w / 2, w * 0.35f), style = s); drawLine(ink, Offset(w / 2, w * 0.65f), Offset(w / 2, w), w * 0.12f, StrokeCap.Round) }
        "CUSHION" -> drawRoundRect(ink, Offset(w * 0.05f, w * 0.25f), Size(w * 0.9f, w * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.25f))
        "WAVE" -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(0f, w * 0.6f); cubicTo(w * 0.25f, w * 0.2f, w * 0.5f, w * 0.9f, w * 0.75f, w * 0.4f); quadraticTo(w * 0.88f, w * 0.2f, w, w * 0.4f) }; drawPath(p, ink, style = s) }
        "STARLIGHT" -> { val p = androidx.compose.ui.graphics.Path().apply { (0 until 10).forEach { i -> val r = if (i % 2 == 0) w * 0.5f else w * 0.2f; val a = Math.toRadians(-90.0 + i * 36); val x = (w / 2 + r * Math.cos(a)).toFloat(); val y = (w / 2 + r * Math.sin(a)).toFloat(); if (i == 0) moveTo(x, y) else lineTo(x, y) }; close() }; drawPath(p, ink) }
        "FRIEND_BADGE" -> { drawCircle(ink, w * 0.22f, Offset(w * 0.32f, w * 0.5f)); drawCircle(ink, w * 0.22f, Offset(w * 0.68f, w * 0.5f), style = s) }
        else -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, 0f); lineTo(w * 0.75f, w / 2); lineTo(w / 2, w); lineTo(w * 0.25f, w / 2); close() }; drawPath(p, ink) }
    }
}

/** "준비 중" notice for features waiting on the companion server ([ai.carbuddy.core.Features]). */
@Composable
fun ComingSoonDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ai.carbuddy.ui.t("coming_soon_title"), style = Cb.type.title) },
        text = { Text(ai.carbuddy.ui.t("coming_soon_body"), style = Cb.type.body) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(ai.carbuddy.ui.t("coming_soon_ok"), style = Cb.type.button) } },
    )
}

/** Button label for a feature that is not open yet: "같이 탈 사람 초대 · 준비 중". */
@Composable
fun soonLabel(key: String): String =
    if (ai.carbuddy.core.Features.COMPANION_SERVER) ai.carbuddy.ui.t(key) else ai.carbuddy.ui.t("coming_soon_label", "name" to ai.carbuddy.ui.t(key))

/** Speech bubble (ivory) next to the buddy. */
@Composable
fun Bubble(text: String, modifier: Modifier = Modifier) {
    val c = Cb.colors
    Box(
        modifier.clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
            .background(c.paper).padding(horizontal = 20.dp, vertical = 14.dp),
    ) { Text(text, style = Cb.type.body, color = c.onPaper) }
}

// ------------------------------------------------------------------------------- episode icons

fun episodeColor(type: String, amber: Color, cyan: Color): Color = when (type) {
    "LOST_LOOP" -> amber
    "DETOUR" -> cyan
    "SUDDEN_RAIN" -> Color(0xFF7FA8FF)
    "TEMP_WAR" -> Color(0xFFF28B6B)
    "JAM_ENDURED" -> Color(0xFFB9C4D6)
    "REST_STOP" -> Color(0xFFE8C07A)
    else -> Color(0xFFB9C4D6)
}

/** 32 dp icon box (UX_SPEC 5.4 EpisodeRow). */
@Composable
fun EpisodeIcon(type: String, name: String, modifier: Modifier = Modifier, box: Dp = 40.dp, bg: Color = Cb.colors.bgSurface2) {
    val c = Cb.colors
    val tint = episodeColor(type, c.amber, c.cyan)
    Box(modifier.size(box).clip(RoundedCornerShape(10.dp)).background(bg).semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(box * 0.55f)) { drawEpisodeGlyph(type, tint) }
    }
}

fun DrawScope.drawEpisodeGlyph(type: String, tint: Color) {
    val w = size.width
    val sw = w * 0.13f
    when (type) {
        "LOST_LOOP" -> {
            drawArc(tint, 40f, 290f, false, Offset(w * 0.1f, w * 0.1f), Size(w * 0.8f, w * 0.8f), style = Stroke(sw, cap = StrokeCap.Round))
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.62f, w * 0.02f); lineTo(w * 0.95f, w * 0.22f); lineTo(w * 0.6f, w * 0.38f); close() }
            drawPath(p, tint)
        }
        "DETOUR" -> {
            drawLine(tint, Offset(w * 0.3f, w), Offset(w * 0.3f, w * 0.45f), sw, StrokeCap.Round)
            drawArc(tint, 180f, 90f, false, Offset(w * 0.3f, w * 0.2f), Size(w * 0.5f, w * 0.5f), style = Stroke(sw, cap = StrokeCap.Round))
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.62f, w * 0.0f); lineTo(w * 0.95f, w * 0.2f); lineTo(w * 0.62f, w * 0.42f); close() }
            drawPath(p, tint)
        }
        "SUDDEN_RAIN" -> for (i in 0..2) {
            val x = w * (0.22f + i * 0.28f)
            drawLine(tint, Offset(x + w * 0.12f, w * 0.1f), Offset(x - w * 0.08f, w * 0.9f), sw, StrokeCap.Round)
        }
        "TEMP_WAR" -> {
            drawRoundRect(tint, Offset(w * 0.38f, 0f), Size(w * 0.24f, w * 0.7f), CornerRadius(w * 0.12f))
            drawCircle(tint, w * 0.22f, Offset(w * 0.5f, w * 0.76f))
        }
        "JAM_ENDURED" -> {
            drawRoundRect(tint, Offset(0f, w * 0.35f), Size(w * 0.42f, w * 0.3f), CornerRadius(w * 0.08f))
            drawRoundRect(tint, Offset(w * 0.56f, w * 0.35f), Size(w * 0.42f, w * 0.3f), CornerRadius(w * 0.08f))
        }
        "REST_STOP" -> {
            drawRoundRect(tint, Offset(w * 0.12f, w * 0.3f), Size(w * 0.56f, w * 0.6f), CornerRadius(w * 0.1f))
            drawArc(tint, -90f, 180f, false, Offset(w * 0.55f, w * 0.42f), Size(w * 0.32f, w * 0.32f), style = Stroke(sw * 0.8f))
        }
        // PT types (기획안 v13 3장)
        "FULL_HOUSE" -> for (i in 0..3) drawCircle(tint, w * 0.16f, Offset(w * (0.25f + (i % 2) * 0.5f), w * (0.25f + (i / 2) * 0.5f)))
        "WIND_RUN" -> for (i in 0..2) {
            val y = w * (0.22f + i * 0.28f)
            drawLine(tint, Offset(w * 0.05f, y), Offset(w * (0.75f - i * 0.15f), y), sw, StrokeCap.Round)
        }
        "TUNNEL_PARADE" -> {
            drawArc(tint, 180f, 180f, false, Offset(w * 0.05f, w * 0.2f), Size(w * 0.9f, w * 0.9f), style = Stroke(sw, cap = StrokeCap.Round))
            drawLine(tint, Offset(w * 0.05f, w * 0.65f), Offset(w * 0.05f, w * 0.95f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.95f, w * 0.65f), Offset(w * 0.95f, w * 0.95f), sw, StrokeCap.Round)
        }
        "SUNSET_DRIVE" -> {
            drawArc(tint, 180f, 180f, true, Offset(w * 0.2f, w * 0.35f), Size(w * 0.6f, w * 0.6f))
            drawLine(tint, Offset(0f, w * 0.7f), Offset(w, w * 0.7f), sw, StrokeCap.Round)
        }
        "DRIVER_SWAP" -> {
            drawCircle(tint, w * 0.38f, Offset(w / 2, w / 2), style = Stroke(sw))
            drawLine(tint, Offset(w * 0.15f, w / 2), Offset(w * 0.85f, w / 2), sw, StrokeCap.Round)
            drawLine(tint, Offset(w / 2, w / 2), Offset(w / 2, w * 0.88f), sw, StrokeCap.Round)
        }
        "CAMERA_CLEAN" -> {
            drawRoundRect(tint, Offset(w * 0.05f, w * 0.3f), Size(w * 0.9f, w * 0.55f), CornerRadius(w * 0.1f), style = Stroke(sw))
            drawCircle(tint, w * 0.15f, Offset(w / 2, w * 0.57f))
        }
        "REVISIT" -> {
            drawCircle(tint, w * 0.28f, Offset(w / 2, w * 0.36f), style = Stroke(sw))
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.27f, w * 0.5f); lineTo(w / 2, w * 0.98f); lineTo(w * 0.73f, w * 0.5f); close() }
            drawPath(p, tint)
        }
        "NIGHT_DRIVE" -> {
            drawCircle(tint, w * 0.4f, Offset(w * 0.45f, w * 0.5f))
            drawCircle(Color(0xFF16233D), w * 0.34f, Offset(w * 0.66f, w * 0.38f))
        }
        else -> drawCircle(tint, w * 0.3f)
    }
}

// ------------------------------------------------------------------------------- receipt / awards

/** Texts the receipt needs from the CopyBook (keeps the component free of key lookups). */
data class ReceiptTexts(
    val title: String,
    val plan: String?,
    val summaryHeader: String,
    val settleHeader: String,
    val summary: String,
)

/**
 * UX_SPEC 5.4 ReceiptCard / V4 여정 영수증: title + stamp, plan, '여정 내역' (always the same),
 * '시간 정산' lines (one per 0.3 s when [visibleLines] grows), total line, gap note.
 * The stamp lands tilted −8° once the total is shown. NO_DEST has no stamp.
 */
@Composable
fun ReceiptCard(
    receipt: Receipt,
    texts: ReceiptTexts,
    modifier: Modifier = Modifier,
    detailed: Boolean = false,
    compact: Boolean = false,
    footer: String? = null,
    visibleLines: Int = Int.MAX_VALUE,
    starType: String? = null,
) {
    val c = Cb.colors
    val ink = c.onPaper
    val num = Cb.type.body.copy(fontFeatureSettings = "tnum", color = ink)
    val showTotal = visibleLines >= receipt.lines.size
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusPanel)).background(c.paper)
                .drawBehind {
                    drawRoundRect(Color(0x33000000), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))), cornerRadius = CornerRadius(20.dp.toPx()))
                }
                .padding(if (compact) 20.dp else 24.dp),
        ) {
            Text(texts.title, style = Cb.type.title.copy(fontSize = Cb.type.body.fontSize * 1.05f), color = ink, modifier = Modifier.padding(end = 110.dp))
            texts.plan?.let { Text(it, style = Cb.type.caption, color = ink.copy(alpha = 0.7f)) }
            Spacer(Modifier.height(10.dp))
            DashedRule(ink.copy(alpha = 0.35f))
            if (!compact) {
                Spacer(Modifier.height(8.dp))
                Text(texts.summaryHeader, style = Cb.type.caption, color = ink.copy(alpha = 0.55f))
            } else Spacer(Modifier.height(6.dp))
            Text(texts.summary, style = Cb.type.caption.copy(fontSize = Cb.type.label.fontSize), color = ink.copy(alpha = 0.8f))
            Spacer(Modifier.height(8.dp))
            DashedRule(ink.copy(alpha = 0.35f))
            if (!compact && receipt.lines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(texts.settleHeader, style = Cb.type.caption, color = ink.copy(alpha = 0.55f))
            }
            receipt.lines.take(visibleLines).forEach { l ->
                val star = starType != null && l.type == starType
                val muted = l.min < 0
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        (if (star) "★ " else "") + (if (detailed) l.detail ?: l.label else l.label),
                        style = num.copy(fontWeight = if (star) FontWeight.Bold else FontWeight.Normal, color = if (muted) ink.copy(alpha = 0.65f) else ink),
                        modifier = Modifier.weight(1f),
                    )
                    Text(signed(l.min) + "분", style = num.copy(fontWeight = if (star) FontWeight.Bold else FontWeight.Normal, color = if (muted) ink.copy(alpha = 0.65f) else ink))
                }
            }
            if (showTotal) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(ink))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(receipt.totalLabel, style = Cb.type.title.copy(fontSize = Cb.type.body.fontSize * 1.15f), color = ink, modifier = Modifier.weight(1f))
                    if (receipt.mode != "NO_DEST") Text(signed(receipt.totalMin) + "분", style = Cb.type.title.copy(fontFeatureSettings = "tnum"), color = ink)
                }
                receipt.gapNote?.let { Text(it, style = Cb.type.caption, color = ink.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp)) }
                footer?.let { Text(it, style = Cb.type.caption, color = ink.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = TextAlign.Center) }
            }
        }
        val stamp = receipt.stamp
        if (stamp != null && showTotal) Stamp(stamp, receipt.mode, Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 16.dp))
    }
}

/** [지각] amber, [정시 도착] cyan, [일찍 도착] blue; slightly tilted (UX V4). */
@Composable
fun Stamp(text: String, mode: String, modifier: Modifier = Modifier) {
    val color = when (mode) { "LATE" -> Color(0xFFB0700F); "ON_TIME" -> Color(0xFF1F8A96); else -> Color(0xFF3B5FC2) }
    val scale by animateFloatAsState(1f, tween(Motion.MEDIUM), label = "stamp")
    Box(
        modifier.scale(scale).graphicsLayer { rotationZ = -8f }
            .border(2.dp, color, RoundedCornerShape(10.dp)).padding(3.dp)
            .border(1.dp, color, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
    ) { Text(text, style = Cb.type.label.copy(fontWeight = FontWeight.Bold), color = color) }
}

/** Amber dark enough for text on ivory paper (contrast ≥ 4.5:1). */
val STAR_INK = Color(0xFF9A5A00)

fun signed(n: Int) = if (n > 0) "+$n" else if (n < 0) "−${-n}" else "0"

@Composable
fun DashedRule(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
    }
}

/** UX_SPEC 5.4 AwardCard: hidden → revealed → grand (amber). */
@Composable
fun AwardCard(award: Award, revealed: Boolean, votesLabel: String, grandLabel: String, modifier: Modifier = Modifier, showVotes: Boolean = true) {
    val c = Cb.colors
    val grand = award.grand && revealed
    val bg = if (grand) c.amber else c.bgSurface
    val fg = if (grand) c.onAccent else c.textPrimary
    Column(
        modifier.clip(RoundedCornerShape(Dimens.radiusPanel)).background(bg)
            .border(1.dp, if (grand) c.amber else c.outline, RoundedCornerShape(Dimens.radiusPanel))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!revealed) {
            Text("?", style = Cb.type.display, color = c.textSecondary)
            return@Column
        }
        val top = buildString {
            if (award.grand) append(grandLabel)
            if (showVotes) { if (isNotEmpty()) append(" · "); append(votesLabel) }
        }
        if (top.isNotEmpty()) Text(top, style = Cb.type.label.copy(fontWeight = FontWeight.Bold), color = if (grand) fg else c.amber)
        Text(award.name, style = Cb.type.title, color = fg)
        if (award.desc.isNotEmpty()) Text(award.desc, style = Cb.type.caption.copy(fontSize = Cb.type.label.fontSize), color = fg.copy(alpha = 0.85f))
    }
}

/** UX_SPEC 5.4 EpisodeRow: normal / star (bright) / hidden (dimmed + label). */
@Composable
fun EpisodeRow(e: CardEpisode, starLabel: String, hiddenLabel: String, modifier: Modifier = Modifier, extra: String? = null) {
    val c = Cb.colors
    val star = e.star && !e.hidden
    val bg = if (star) c.paper else Color.Transparent
    val ink = if (star) c.onPaper else c.textPrimary
    val sub = if (star) c.onPaper.copy(alpha = 0.7f) else c.textSecondary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EpisodeIcon(e.type, e.name, bg = if (star) c.onPaper else c.bgSurface2)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val label = buildString {
                append(e.time); append(" · "); append(e.name)
                if (star) { append(" · ★ "); append(starLabel) }
                if (e.hidden) { append(" · "); append(hiddenLabel) }
            }
            Text(label, style = Cb.type.label, color = if (star) STAR_INK else sub)
            Text(e.text, style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = if (e.hidden) ink.copy(alpha = 0.4f) else ink)
            extra?.let { Text(it, style = Cb.type.caption, color = sub) }
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, bg: Color = Cb.colors.bgSurface, padding: Dp = 20.dp, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(Dimens.radiusPanel)).background(bg).border(1.dp, Cb.colors.outline.copy(alpha = 0.6f), RoundedCornerShape(Dimens.radiusPanel)).padding(padding)) { content() }
}

/** Home status card (UX_SPEC V1 (a)–(e)): accent bar · label · text · one action. */
@Composable
fun StatusCard(label: String?, text: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier, accent: Color = Cb.colors.cyan) {
    val c = Cb.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusPanel)).background(c.bgSurface)
            .border(1.dp, c.outline.copy(alpha = 0.6f), RoundedCornerShape(Dimens.radiusPanel)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(52.dp).clip(RoundedCornerShape(3.dp)).background(accent))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            label?.let { Text(it, style = Cb.type.label, color = c.textSecondary) }
            Text(text, style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) { Spacer(Modifier.width(12.dp)); CbButton(action, onAction, kind = ButtonKind.PRIMARY, modifier = Modifier.width(110.dp)) }
    }
}

@Composable
fun Toast(text: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cb.colors
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.paper).padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Cb.type.body, color = c.onPaper)
        if (action != null) {
            Spacer(Modifier.width(16.dp))
            Box(Modifier.defaultMinSize(minHeight = Dimens.touch).clickable(onClick = onAction).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(action, style = Cb.type.button, color = c.teal)
            }
        }
    }
}

@Composable
fun Banner(text: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cb.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.warn.copy(alpha = 0.18f))
            .border(BorderStroke(1.dp, c.warn), RoundedCornerShape(16.dp)).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠ $text", style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary, modifier = Modifier.weight(1f))
        if (action != null) CbButton(action, onAction, kind = ButtonKind.SECONDARY, modifier = Modifier.width(120.dp))
    }
}

// simple glyphs for the home icon row
fun DrawScope.glyphArchive(c: Color) {
    val w = size.width
    drawRoundRect(c, Offset(w * 0.1f, w * 0.2f), Size(w * 0.8f, w * 0.6f), CornerRadius(w * 0.12f), style = Stroke(w * 0.09f))
    drawLine(c, Offset(w * 0.1f, w * 0.42f), Offset(w * 0.9f, w * 0.42f), w * 0.09f)
}
fun DrawScope.glyphBuddy(c: Color) {
    val w = size.width
    drawCircle(c, w * 0.42f, style = Stroke(w * 0.09f))
    drawCircle(c, w * 0.06f, Offset(w * 0.36f, w * 0.45f)); drawCircle(c, w * 0.06f, Offset(w * 0.64f, w * 0.45f))
}
fun DrawScope.glyphSettings(c: Color) {
    val w = size.width
    drawCircle(c, w * 0.18f, style = Stroke(w * 0.09f))
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val o = Offset(w / 2 + (w * 0.34f * Math.cos(a)).toFloat(), w / 2 + (w * 0.34f * Math.sin(a)).toFloat())
        val o2 = Offset(w / 2 + (w * 0.46f * Math.cos(a)).toFloat(), w / 2 + (w * 0.46f * Math.sin(a)).toFloat())
        drawLine(c, o, o2, w * 0.09f, StrokeCap.Round)
    }
}
fun DrawScope.glyphBack(c: Color) {
    val w = size.width
    drawLine(c, Offset(w * 0.6f, w * 0.15f), Offset(w * 0.25f, w * 0.5f), w * 0.1f, StrokeCap.Round)
    drawLine(c, Offset(w * 0.25f, w * 0.5f), Offset(w * 0.6f, w * 0.85f), w * 0.1f, StrokeCap.Round)
}
fun DrawScope.glyphClose(c: Color) {
    val w = size.width
    drawLine(c, Offset(w * 0.2f, w * 0.2f), Offset(w * 0.8f, w * 0.8f), w * 0.1f, StrokeCap.Round)
    drawLine(c, Offset(w * 0.8f, w * 0.2f), Offset(w * 0.2f, w * 0.8f), w * 0.1f, StrokeCap.Round)
}
