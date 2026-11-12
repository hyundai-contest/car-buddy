package ai.carbuddy.ui.screens

import ai.carbuddy.data.BuddyEntity
import ai.carbuddy.story.YearRecap
import ai.carbuddy.ui.ShareImage
import ai.carbuddy.ui.components.BuddyAvatar
import ai.carbuddy.ui.components.ButtonKind
import ai.carbuddy.ui.components.CbButton
import ai.carbuddy.ui.components.CbIconButton
import ai.carbuddy.ui.components.EpisodeIcon
import ai.carbuddy.ui.components.Pill
import ai.carbuddy.ui.components.glyphClose
import ai.carbuddy.ui.t
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.Dimens
import ai.carbuddy.ui.theme.Motion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The slides of the year in review, in order. */
private enum class YearSlide { INTRO, TRIPS, DISTANCE, WHEN, CITIES, EPISODES, COMPANY, BEST, BUDDY, SHARE }

private val PURPLE = Color(0xFF9C8CF2)

/** Saved share picture width (9:16 → 1080 × 1920). */
private const val SHARE_WIDTH_PX = 1080f

private fun num(n: Int) = "%,d".format(n)

/** "164시간" / "45분": long totals in hours, short ones in minutes. */
private fun hours(min: Int) = if (min >= 60) "${num(min / 60)}시간" else "${min}분"

/**
 * 연말 결산 (Spotify-style year in review): one big number per slide, the buddy narrating, then one
 * 9:16 summary card to save as a picture or carry to a phone by QR. Parked only (AppRoot).
 * Portrait: stacked; Landscape: words left, picture right (CLAUDE.md 규칙 8).
 */
@Composable
fun YearRecapScreen(
    recap: YearRecap,
    buddy: BuddyEntity,
    years: List<Int>,
    minTrips: Int,
    onYear: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var index by remember(recap.year, recap.sample) { mutableIntStateOf(0) }
    val slides = YearSlide.entries
    val slide = slides[index]
    val accent = when (slide) {
        YearSlide.INTRO, YearSlide.SHARE -> Cb.colors.amber
        YearSlide.TRIPS, YearSlide.CITIES, YearSlide.BUDDY -> Cb.colors.cyan
        YearSlide.DISTANCE, YearSlide.COMPANY -> Cb.colors.teal
        else -> PURPLE
    }
    val next = { if (index < slides.lastIndex) index++ }
    val edge = Dimens.edge(Cb.landscape)
    val fadeInMs = ai.carbuddy.ui.theme.motionMs(Motion.MEDIUM)
    val fadeOutMs = ai.carbuddy.ui.theme.motionMs(Motion.SHORT)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), Cb.colors.bgBase, Cb.colors.bgBase)))) {
        Column(Modifier.fillMaxSize().padding(edge)) {
            // progress segments (one per slide) + close
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    slides.forEachIndexed { i, _ ->
                        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (i <= index) Cb.colors.textPrimary else Cb.colors.textPrimary.copy(alpha = 0.22f)))
                    }
                }
                Spacer(Modifier.width(16.dp))
                if (recap.sample) { Pill(t("year_sample_mark"), Cb.colors.amber, Cb.colors.onAccent); Spacer(Modifier.width(12.dp)) }
                CbIconButton(t("year_close"), onBack) { glyphClose(it) }
            }
            Spacer(Modifier.height(16.dp))
            AnimatedContent(
                slide, Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(tween(fadeInMs)) togetherWith fadeOut(tween(fadeOutMs)) },
                label = "slide",
            ) { s ->
                // tapping the slide moves on, like a story (the share slide has its own buttons)
                val tap = if (s == YearSlide.SHARE) Modifier else Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = next)
                Box(Modifier.fillMaxSize().then(tap)) { SlideBody(s, recap, buddy, accent, years, minTrips, onYear, onRestart = { index = 0 }) }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (index > 0) CbButton(t("year_prev"), { index-- }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                else Spacer(Modifier.weight(1f))
                when (slide) {
                    YearSlide.SHARE -> CbButton(t("year_close"), onBack, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                    YearSlide.INTRO -> CbButton(t("year_start"), next, Modifier.weight(1f), kind = ButtonKind.AMBER)
                    else -> CbButton(t("year_next"), next, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SlideBody(
    s: YearSlide, r: YearRecap, buddy: BuddyEntity, accent: Color,
    years: List<Int>, minTrips: Int, onYear: (Int) -> Unit, onRestart: () -> Unit,
) {
    val c = Cb.colors
    val big = Cb.type.display.copy(fontSize = if (Cb.landscape) 96.sp else 80.sp, lineHeight = if (Cb.landscape) 110.sp else 92.sp)
    val words: @Composable (String?, String?, String?, List<String>) -> Unit = { kicker, number, body, notes ->
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            kicker?.let { Text(it, style = Cb.type.title, color = c.textSecondary) }
            number?.let { Text(it, style = big, color = accent, maxLines = 2) }
            body?.let { Text(it, style = Cb.type.title, color = c.textPrimary) }
            notes.forEach { Text(it, style = Cb.type.body, color = c.textSecondary) }
        }
    }
    // landscape: words left, picture right; portrait: picture under the words
    val layout: @Composable (@Composable () -> Unit, (@Composable () -> Unit)?) -> Unit = { left, right ->
        if (Cb.landscape) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Box(Modifier.weight(1f)) { left() }
            if (right != null) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { right() }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            left()
            if (right != null) { Spacer(Modifier.height(32.dp)); right() }
        }
    }
    when (s) {
        YearSlide.INTRO -> layout({
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(t("year_intro_kicker", "name" to buddy.name), style = Cb.type.title, color = c.textSecondary)
                Text(t("year_intro_title", "year" to r.year), style = big.copy(fontSize = if (Cb.landscape) 56.sp else 44.sp, lineHeight = if (Cb.landscape) 68.sp else 56.sp), color = c.textPrimary)
                Text(t("year_intro_body"), style = Cb.type.body, color = c.textSecondary)
                if (r.sample) Text(t("year_sample_note", "n" to minTrips), style = Cb.type.label, color = c.amber)
                if (years.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    years.take(4).forEach { y ->
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).clickable { onYear(y) }) {
                            Pill("$y", if (y == r.year) c.amber else c.bgSurface, if (y == r.year) c.onAccent else c.textPrimary, border = c.outline)
                        }
                    }
                }
            }
        }, { BuddyAvatar(if (Cb.landscape) 220.dp else 160.dp, item = buddy.equipped, stage = buddy.stage) })

        YearSlide.TRIPS -> layout({
            words(t("year_trips_kicker"), t("year_trips_big", "n" to num(r.trips)), t("year_trips_body", "days" to r.days),
                listOfNotNull(t("year_routine_note", "n" to r.routineTrips).takeIf { r.routineTrips > 0 }))
        }, { DotsPicture(r.days.coerceAtMost(366), accent) })

        YearSlide.DISTANCE -> layout({
            val compare = when {
                r.earthPercent >= 10 -> t("year_km_earth", "pct" to r.earthPercent)
                r.seoulBusan >= 1 -> t("year_km_busan", "n" to r.seoulBusan)
                else -> t("year_km_short")
            }
            words(t("year_km_kicker"), t("year_km_big", "km" to num(r.km)), compare, listOf(t("year_time_note", "time" to hours(r.drivingMin))))
        }, { EarthPicture((r.km / YearRecap.EARTH_KM).toFloat(), accent) })

        YearSlide.WHEN -> layout({
            val day = t("year_weekdays").split(',').getOrElse(r.topWeekday.value - 1) { "" }
            words(t("year_when_kicker"), t("year_when_big", "month" to r.topMonth), t("year_when_body", "n" to r.topMonthTrips),
                listOf(t("year_tod", "tod" to t("year_tod_${r.timeOfDay.name}")), t("year_weekday", "day" to day)))
        }, null)

        YearSlide.CITIES -> layout({
            words(t("year_cities_kicker"), t("year_cities_big", "n" to r.cities), null, emptyList())
        }, {
            RankList(t("year_cities_body"), r.topCities.map { t("year_city_row", "city" to it.name, "n" to it.n) }, accent)
        })

        YearSlide.EPISODES -> layout({
            val notes = listOfNotNull(
                t("year_jam", "time" to hours(r.jamMin)).takeIf { r.jamMin > 0 },
                t("year_rain", "n" to r.rainTrips).takeIf { r.rainTrips > 0 },
                t("year_loops", "n" to r.lostLoops).takeIf { r.lostLoops > 0 },
            )
            if (r.episodes == 0) words(t("year_eps_kicker"), null, t("year_eps_none"), emptyList())
            else words(t("year_eps_kicker"), t("year_eps_big", "n" to num(r.episodes)), null, notes)
        }, if (r.topEpisodes.isEmpty()) null else ({
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(t("year_eps_body"), style = Cb.type.label, color = c.textSecondary)
                r.topEpisodes.forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = Cb.type.title, color = accent, modifier = Modifier.width(36.dp))
                        EpisodeIcon(e.key, e.name, box = 52.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(t("year_eps_row", "name" to e.name, "n" to e.n), style = Cb.type.title, color = c.textPrimary)
                    }
                }
            }
        }))

        YearSlide.COMPANY -> layout({
            if (r.withCompany == 0) words(t("year_company_kicker"), null, t("year_company_none"), emptyList())
            else words(t("year_company_kicker"), t("year_company_big", "n" to num(r.withCompany)), t("year_company_body", "max" to r.maxPassengers), emptyList())
        }, { SeatsPicture(r.maxPassengers, accent) })

        YearSlide.BEST -> layout({
            val b = r.best
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(t("year_best_kicker"), style = Cb.type.title, color = c.textSecondary)
                if (b != null) {
                    Text(b.title, style = big.copy(fontSize = if (Cb.landscape) 56.sp else 44.sp, lineHeight = if (Cb.landscape) 68.sp else 56.sp), color = accent)
                    Text(t("year_best_meta", "date" to b.dateLabel, "from" to b.fromCity, "to" to b.toCity), style = Cb.type.title, color = c.textPrimary)
                }
                r.longest?.let { Text(t("year_longest", "date" to it.dateLabel, "km" to num(it.km)), style = Cb.type.body, color = c.textSecondary) }
            }
        }, null)

        YearSlide.BUDDY -> layout({
            words(t("year_buddy_kicker", "name" to buddy.name), null, t("year_buddy_body", "stage" to t("stage_${buddy.stage}")), emptyList())
        }, { BuddyAvatar(if (Cb.landscape) 240.dp else 180.dp, item = buddy.equipped, stage = buddy.stage) })

        YearSlide.SHARE -> ShareSlide(r, buddy, onRestart)
    }
}

/** Last slide: the 9:16 summary card + save as a picture / carry to the phone by QR. */
@Composable
private fun ShareSlide(r: YearRecap, buddy: BuddyEntity, onRestart: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var message by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    val saved = t("year_saved")
    val failed = t("year_save_failed")
    val shareText = recapShareText(r)

    val card = @Composable { m: Modifier ->
        // the screen shows the card as is; the saved picture is recorded again at 1080 px wide
        Box(m.aspectRatio(9f / 16f).drawWithContent {
            drawContent()
            val k = SHARE_WIDTH_PX / size.width
            layer.record(androidx.compose.ui.unit.IntSize(SHARE_WIDTH_PX.toInt(), (size.height * k).toInt())) {
                scale(k, Offset.Zero) { this@drawWithContent.drawContent() }
            }
        }) {
            RecapShareCard(r, buddy)
        }
    }
    val actions = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(t("year_share_kicker", "year" to r.year), style = Cb.type.label, color = Cb.colors.textSecondary)
            Text(t("year_share_title"), style = Cb.type.title, color = Cb.colors.textPrimary)
            CbButton(t("year_save"), {
                scope.launch {
                    val bmp = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
                    val uri = bmp?.let { ShareImage.saveToPictures(ctx, it, "carbuddy_${r.year}_${System.currentTimeMillis()}") }
                    message = if (uri != null) saved else failed
                }
            }, Modifier.fillMaxWidth(), kind = ButtonKind.AMBER)
            CbButton(t("year_qr"), { showQr = true }, Modifier.fillMaxWidth())
            CbButton(t("year_again"), onRestart, Modifier.fillMaxWidth(), kind = ButtonKind.GHOST)
            message?.let { Text(it, style = Cb.type.label, color = Cb.colors.teal) }
        }
    }
    if (Cb.landscape) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { card(Modifier.fillMaxHeight()) }
            Box(Modifier.weight(1f)) { actions() }
        }
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { card(Modifier.fillMaxHeight()) }
            Spacer(Modifier.height(20.dp))
            actions()
        }
    }
    if (showQr) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text(t("year_qr_title"), style = Cb.type.title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    QrCode(shareText, Modifier.size(280.dp))
                    Text(t("year_qr_body"), style = Cb.type.body)
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(t("year_qr_done"), style = Cb.type.button) } },
        )
    }
}

/** The text the QR carries: counts and city-level names only. */
@Composable
fun recapShareText(r: YearRecap): String {
    val top = r.topEpisodes.firstOrNull()?.let { t("year_share_text_top", "name" to it.name, "n" to num(it.n)) }.orEmpty()
    val best = r.best?.let { t("year_share_text_best", "title" to it.title) }.orEmpty()
    return t("year_share_text", "year" to r.year, "trips" to num(r.trips), "km" to num(r.km), "time" to hours(r.drivingMin),
        "cities" to r.cities, "eps" to num(r.episodes), "top" to top, "best" to best)
}

/** 9:16 share image. Fixed dark look so the saved picture is the same in any theme. */
@Composable
fun RecapShareCard(r: YearRecap, buddy: BuddyEntity, modifier: Modifier = Modifier) {
    val c = ai.carbuddy.ui.theme.DarkColors
    BoxWithConstraints(modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF1B2D4F), c.bgBase, Color(0xFF2A1F4A))))) {
        val u = maxWidth / 360f   // scale everything with the card width (12sp floor kept by 360dp+ cards)
        val f = (u.value).coerceAtLeast(0.75f)
        Column(Modifier.fillMaxSize().padding(24.dp * f), verticalArrangement = Arrangement.spacedBy(10.dp * f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("year_card_title", "year" to r.year), style = Cb.type.display.copy(fontSize = 34.sp * f, lineHeight = 42.sp * f), color = c.textPrimary, modifier = Modifier.weight(1f))
                BuddyAvatar(64.dp * f, item = buddy.equipped, stage = buddy.stage)
            }
            if (r.sample) Text(t("year_sample_mark"), style = Cb.type.label.copy(fontSize = (13f * f).coerceAtLeast(12f).sp), color = c.amber)
            Spacer(Modifier.height(6.dp * f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp * f)) {
                Stat(t("year_card_trips"), "${num(r.trips)}번", c.cyan, f, Modifier.weight(1f))
                Stat(t("year_card_km"), "${num(r.km)}km", c.amber, f, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp * f)) {
                Stat(t("year_card_time"), hours(r.drivingMin), c.teal, f, Modifier.weight(1f))
                Stat(t("year_card_cities"), "${r.cities}곳", PURPLE, f, Modifier.weight(1f))
            }
            Stat(t("year_card_eps"), "${num(r.episodes)}개", c.textPrimary, f, Modifier.fillMaxWidth())
            r.topEpisodes.firstOrNull()?.let { e ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp * f)).background(Color.White.copy(alpha = 0.06f)).padding(14.dp * f)) {
                    Text(t("year_card_top"), style = Cb.type.label.copy(fontSize = (13f * f).coerceAtLeast(12f).sp), color = c.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EpisodeIcon(e.key, e.name, box = 36.dp * f)
                        Spacer(Modifier.width(10.dp * f))
                        Text("${e.name} · ${num(e.n)}번", style = Cb.type.title.copy(fontSize = 20.sp * f, lineHeight = 26.sp * f), color = c.textPrimary)
                    }
                }
            }
            r.best?.let { b ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp * f)).background(c.paper).padding(14.dp * f)) {
                    Text(t("year_card_best"), style = Cb.type.label.copy(fontSize = (13f * f).coerceAtLeast(12f).sp), color = c.onPaper.copy(alpha = 0.7f))
                    Text(b.title, style = Cb.type.title.copy(fontSize = 20.sp * f, lineHeight = 26.sp * f), color = c.onPaper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${b.fromCity} → ${b.toCity}", style = Cb.type.label.copy(fontSize = (13f * f).coerceAtLeast(12f).sp), color = c.onPaper.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(t("year_card_footer", "name" to buddy.name), style = Cb.type.label.copy(fontSize = (13f * f).coerceAtLeast(12f).sp), color = c.textSecondary)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, f: Float, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp * f)).background(Color.White.copy(alpha = 0.06f)).padding(14.dp * f)) {
        Text(label, style = Cb.type.label.copy(fontSize = (13f * f).coerceAtLeast(12f).sp), color = ai.carbuddy.ui.theme.DarkColors.textSecondary)
        Text(value, style = Cb.type.title.copy(fontSize = 26.sp * f, lineHeight = 32.sp * f, fontWeight = FontWeight.Bold), color = color, maxLines = 1)
    }
}

@Composable
private fun RankList(title: String, rows: List<String>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = Cb.type.label, color = Cb.colors.textSecondary)
        rows.forEachIndexed { i, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = Cb.type.title, color = accent, modifier = Modifier.width(36.dp))
                Text(row, style = Cb.type.title, color = Cb.colors.textPrimary)
            }
        }
    }
}

/** One dot per day driven (a year's calendar feel). */
@Composable
private fun DotsPicture(days: Int, accent: Color) {
    val dim = Cb.colors.textPrimary.copy(alpha = 0.12f)
    Canvas(Modifier.fillMaxWidth().aspectRatio(2.2f)) {
        val cols = 31; val rows = 12
        val step = minOf(size.width / cols, size.height / rows)
        val r = step * 0.32f
        for (i in 0 until cols * rows) {
            val x = (i % cols) * step + step / 2; val y = (i / cols) * step + step / 2
            drawCircle(if (i < days) accent else dim, r, Offset(x, y))
        }
    }
}

/** A ring around the globe filled by the share of 40,075 km we drove. */
@Composable
private fun EarthPicture(fraction: Float, accent: Color) {
    val track = Cb.colors.textPrimary.copy(alpha = 0.14f)
    val globe = Cb.colors.bgSurface2
    Canvas(Modifier.size(if (Cb.landscape) 260.dp else 200.dp)) {
        val stroke = size.minDimension * 0.08f
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawCircle(globe, d * 0.38f)
        drawArc(track, 0f, 360f, false, tl, Size(d, d), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(accent, -90f, 360f * fraction.coerceIn(0.02f, 1f), false, tl, Size(d, d),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** Seats from above: the most people we carried at once. */
@Composable
private fun SeatsPicture(max: Int, accent: Color) {
    val empty = Cb.colors.textPrimary.copy(alpha = 0.14f)
    Canvas(Modifier.size(if (Cb.landscape) 220.dp else 170.dp)) {
        val w = size.width; val h = size.height
        val seat = Size(w * 0.3f, h * 0.3f)
        val spots = listOf(Offset(w * 0.15f, h * 0.15f), Offset(w * 0.55f, h * 0.15f), Offset(w * 0.15f, h * 0.55f), Offset(w * 0.55f, h * 0.55f))
        spots.forEachIndexed { i, o ->
            drawRoundRect(if (i < max.coerceAtLeast(1)) accent else empty, o, seat, androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
        }
    }
}

/** QR modules drawn directly (zxing BitMatrix), dark on white for any phone camera. */
@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { runCatching { ShareImage.qr(text) }.getOrNull() } ?: return
    Canvas(modifier.background(Color.White)) {
        val n = matrix.width
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) {
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** Remembers that this year's recap was opened, so home stops offering it (per car, local only). */
object YearRecapSeen {
    private const val PREFS = "carbuddy_year_recap"
    fun seen(ctx: android.content.Context, year: Int): Boolean =
        runCatching { ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt("seen", 0) >= year }.getOrDefault(false)
    fun mark(ctx: android.content.Context, year: Int) {
        runCatching { ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putInt("seen", year).apply() }
    }
}

/** Finished cards of the archive as the recap builder takes them. */
fun recapItems(cards: List<ai.carbuddy.trip.CardItem>): List<Pair<ai.carbuddy.card.Card, ai.carbuddy.data.TripEntity?>> =
    cards.filter { it.status == "FINAL" && it.trip?.discarded != true }.map { it.card to it.trip }

/**
 * Screen entry: the year asked for (or the recap year now), the real recap when there are enough
 * trips, else the sample. Opening a real recap marks it seen (home stops offering it).
 */
@Composable
fun YearRecapRoute(year: Int?, ui: ai.carbuddy.trip.RuntimeUi, minTrips: Int, onBack: () -> Unit) {
    val builder = remember { ai.carbuddy.story.YearRecapBuilder() }
    val items = remember(ui.cards) { recapItems(ui.cards) }
    var chosen by remember { mutableIntStateOf(year ?: builder.recapYear(System.currentTimeMillis())) }
    val years = remember(items) { builder.years(items).filter { y -> builder.build(y, items, minTrips) != null } }
    val real = remember(items, chosen) { builder.build(chosen, items, minTrips) }
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(real?.year) { real?.let { YearRecapSeen.mark(ctx, it.year) } }
    YearRecapScreen(real ?: YearRecap.sample(chosen), ui.buddy, years, minTrips, onYear = { chosen = it }, onBack = onBack)
}
