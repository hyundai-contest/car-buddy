package ai.carbuddy.ui.screens

import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.story.EpisodeTexts
import ai.carbuddy.story.Scorer
import ai.carbuddy.trip.RuntimeUi
import ai.carbuddy.trip.TripRuntime
import ai.carbuddy.ui.LocalCopy
import ai.carbuddy.ui.components.BuddyAvatar
import ai.carbuddy.ui.components.BuddyMood
import ai.carbuddy.ui.components.ButtonKind
import ai.carbuddy.ui.components.CbButton
import ai.carbuddy.ui.components.CbIconButton
import ai.carbuddy.ui.components.EpisodeIcon
import ai.carbuddy.ui.components.Pill
import ai.carbuddy.ui.components.ROUTE_BG
import ai.carbuddy.ui.components.RouteCanvas
import ai.carbuddy.ui.components.glyphBack
import ai.carbuddy.ui.t
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One-line text for an episode in progress (live list, driving mode). */
@Composable
private fun liveLine(e: EpisodeEntity): String {
    val texts = EpisodeTexts(LocalCopy.current)
    val se = Scorer.merge(listOf(e), emptyMap()).first()
    return texts.short(se)
}

/**
 * V10 정차 모드 (UX_SPEC 3장, 시안 9a): how the trip is going so far, without the receipt,
 * awards or votes (kept for the arrival screening).
 */
@Composable
fun LiveParkedScreen(ui: RuntimeUi, runtime: TripRuntime, onHome: () -> Unit, onInvite: () -> Unit) {
    val c = Cb.colors
    val live = ui.live
    var confirmOff by remember { mutableStateOf(false) }
    val header = live.restingMin?.let { m -> live.restingPlace?.let { t("live_resting_place", "place" to it, "n" to m) } ?: t("live_resting", "n" to m) } ?: t("live_header", "time" to duration(live.elapsedMin), "km" to live.km)
    val route = @Composable { m: Modifier ->
        Box(m.clip(RoundedCornerShape(24.dp)).background(ROUTE_BG)) {
            live.route?.let { RouteCanvas(it, Modifier.fillMaxSize(), animate = false, showInset = false, liveDot = true) }
                ?: Text(t("live_empty"), style = Cb.type.body, color = c.textSecondary, modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
        }
    }
    val episodes = @Composable { m: Modifier ->
        Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (live.episodes.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BuddyAvatar(56.dp); Spacer(Modifier.width(14.dp))
                    Text(t("live_empty"), style = Cb.type.body, color = c.textSecondary)
                }
            } else {
                live.episodes.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EpisodeIcon(e.type, t("name_${e.type}"))
                        Spacer(Modifier.width(12.dp))
                        Text(timeOf(e.startT), style = Cb.type.label, color = c.textSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text(liveLine(e), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(t("live_more"), style = Cb.type.caption, color = c.textSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(t("live_people"), style = Cb.type.label, color = c.textSecondary)
                Spacer(Modifier.width(12.dp))
                Pill(t("live_people_n", "n" to ui.passengers), c.teal, c.onAccent)
                Spacer(Modifier.weight(1f))
                var soon by remember { mutableStateOf(false) }
                CbButton(ai.carbuddy.ui.components.soonLabel("invite_title"), {
                    if (ai.carbuddy.core.Features.COMPANION_SERVER) onInvite() else soon = true
                }, kind = ButtonKind.SECONDARY)
                if (soon) ai.carbuddy.ui.components.ComingSoonDialog { soon = false }
            }
        }
    }
    var confirmEnd by remember { mutableStateOf(false) }
    val buttons = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 중간 상영 (NEXT_DESIGN A): while resting, the story so far is one tap away
            val tripId = ui.trip?.id
            if (live.restingMin != null && live.episodes.isNotEmpty() && tripId != null) {
                CbButton(t("halftime_live_button"), { runtime.openHalftime(tripId) }, Modifier.fillMaxWidth())
            }
            CbButton(if (recentlyMarked(ui)) t("live_marked") else t("live_mark"), runtime::markMoment, Modifier.fillMaxWidth(),
                kind = if (live.restingMin != null && live.episodes.isNotEmpty()) ButtonKind.SECONDARY else ButtonKind.PRIMARY)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // card from what we have (V10) / no card, stop until the next ignition (SPEC 5.8)
                CbButton(t("live_endNow"), { confirmEnd = true }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                CbButton(t("live_recordOff"), { confirmOff = true }, Modifier.weight(1f), kind = ButtonKind.GHOST)
            }
        }
    }
    val edge = Dimens.edge(Cb.landscape)
    Column(Modifier.fillMaxSize().padding(edge)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CbIconButton(t("live_home"), onHome) { glyphBack(it) }
            Spacer(Modifier.width(16.dp))
            Text(header, style = Cb.type.title, color = c.textPrimary)
        }
        Spacer(Modifier.height(20.dp))
        if (Cb.landscape) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                route(Modifier.weight(0.55f).fillMaxHeight())
                Column(Modifier.weight(0.45f).fillMaxHeight()) {
                    Text(t("live_count", "n" to live.episodes.size), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.amber)
                    Spacer(Modifier.height(12.dp))
                    episodes(Modifier.weight(1f))
                    Spacer(Modifier.height(16.dp))
                    buttons()
                }
            }
        } else {
            route(Modifier.fillMaxWidth().weight(0.42f))
            Spacer(Modifier.height(16.dp))
            episodes(Modifier.weight(0.58f))
            Spacer(Modifier.height(16.dp))
            buttons()
        }
    }
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        text = { Text(t("live_recordOffConfirm"), style = Cb.type.body) },
        confirmButton = { TextButton(onClick = { runtime.stopRecording(); confirmOff = false; onHome() }) { Text(t("live_recordOffYes"), style = Cb.type.button, color = c.warn) } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(t("settings_cancel"), style = Cb.type.button) } },
    )
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        text = { Text(t("live_endNowConfirm"), style = Cb.type.body) },
        confirmButton = { TextButton(onClick = { runtime.endTripNow(); confirmEnd = false }) { Text(t("live_endNowYes"), style = Cb.type.button, color = c.cyan) } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(t("settings_cancel"), style = Cb.type.button) } },
    )
}

/**
 * V10 주행 모드 (DO approved only, 시안 9b): a still buddy, one line, the latest episode, one big
 * button. No animation, scrolling, list or map; content changes only when an episode appears.
 */
@Composable
fun LiveDrivingScreen(ui: RuntimeUi, runtime: TripRuntime) {
    val c = Cb.colors
    val latest = ui.live.episodes.lastOrNull()
    val marked = recentlyMarked(ui)
    BoxWithConstraints(Modifier.fillMaxSize().padding(Dimens.edge(Cb.landscape))) {
        val narrow = maxHeight < 420.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BuddyAvatar(if (narrow) 72.dp else 120.dp, if (marked) BuddyMood.SPARKLE else BuddyMood.DEFAULT, ui.buddy.equipped)
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(if (marked) t("live_marked") else t("live_count", "n" to ui.live.episodes.size), style = Cb.type.title, color = c.textPrimary)
                    if (latest != null && !narrow) {
                        // CarUxRestrictions.getMaxRestrictedStringLength() when the service reports it
                        Text(liveLine(latest).take(minOf(MAX_DRIVING_CHARS, ui.maxDrivingChars ?: MAX_DRIVING_CHARS)), style = Cb.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            CbButton(t("live_mark"), runtime::markMoment, Modifier.fillMaxWidth().heightIn(min = 128.dp))
        }
    }
}

private const val MAX_DRIVING_CHARS = 24

private fun recentlyMarked(ui: RuntimeUi) = ui.live.markedAt?.let { ui.now - it < 5_000 } == true

private fun timeOf(t: Long): String =
    java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

