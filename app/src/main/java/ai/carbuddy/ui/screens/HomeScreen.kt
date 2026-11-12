package ai.carbuddy.ui.screens

import ai.carbuddy.card.Card
import ai.carbuddy.ceremony.CeremonyController
import ai.carbuddy.core.model.TripState
import ai.carbuddy.trip.CardItem
import ai.carbuddy.trip.RuntimeUi
import ai.carbuddy.trip.TripRuntime
import ai.carbuddy.ui.Screen
import ai.carbuddy.ui.components.Banner
import ai.carbuddy.ui.components.Bubble
import ai.carbuddy.ui.components.BuddyAvatar
import ai.carbuddy.ui.components.ButtonKind
import ai.carbuddy.ui.components.CbButton
import ai.carbuddy.ui.components.CbIconButton
import ai.carbuddy.ui.components.EpisodeIcon
import ai.carbuddy.ui.components.StatusCard
import ai.carbuddy.ui.components.glyphArchive
import ai.carbuddy.ui.components.glyphBuddy
import ai.carbuddy.ui.components.glyphSettings
import ai.carbuddy.ui.components.signed
import ai.carbuddy.ui.t
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the home status card shows (UX_SPEC V1 우선순위 (a)–(h)). Exit warnings are banners above it. */
private sealed interface HomeStatus {
    data object NoLocation : HomeStatus
    data class InTrip(val paused: Boolean, val episodes: Int) : HomeStatus
    data class Resume(val item: CardItem) : HomeStatus
    data class Completed(val tripId: String) : HomeStatus
    data class Weekly(val d: ai.carbuddy.place.WeeklyDigest) : HomeStatus
    data class YearRecap(val year: Int) : HomeStatus
    data class RecordOff(val paused: Boolean) : HomeStatus
    data object Idle : HomeStatus
}

private fun homeStatus(ui: RuntimeUi, settings: ai.carbuddy.data.SettingsEntity, yearRecap: Int? = null): HomeStatus {
    if (!ui.locationGranted && settings.onboarded) return HomeStatus.NoLocation                      // (a) first: nothing is recorded without it
    if (ui.trip != null) return HomeStatus.InTrip(ui.tripState == TripState.PAUSED, ui.episodes.count { !it.hidden })
    val resume = ui.cards.firstOrNull()?.takeIf { item ->
        val step = item.trip?.ceremonyStep
        step != CeremonyController.FINISHED && !item.viewed && item.card.visibleEpisodes.size >= 2 && item.trip?.routine != true
    }
    if (resume != null) return HomeStatus.Resume(resume)
    ui.justCompletedTripId?.let { return HomeStatus.Completed(it) }
    ui.weekly?.let { return HomeStatus.Weekly(it) }
    yearRecap?.let { return HomeStatus.YearRecap(it) }
    if (settings.recordOff || settings.recordPaused) return HomeStatus.RecordOff(settings.recordPaused)
    return HomeStatus.Idle
}

@Composable
fun HomeScreen(ui: RuntimeUi, runtime: TripRuntime, onNavigate: (Screen) -> Unit, onOpenLive: () -> Unit = {}, onAllowLocation: () -> Unit = {}) {
    val settings by runtime.settings.collectAsStateWithLifecycle()
    // 연말 결산: December (this year) and January (last year), until opened, only with a real recap
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cfg = runtime.pipeline.config
    val yearRecap = androidx.compose.runtime.remember(ui.cards) {
        val now = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        val b = ai.carbuddy.story.YearRecapBuilder()
        val y = b.recapYear(System.currentTimeMillis())
        y.takeIf { (now.monthValue >= cfg.yearRecapHomeFromMonth || now.monthValue == 1) && !YearRecapSeen.seen(ctx, it) &&
            b.build(it, recapItems(ui.cards), cfg.yearRecapMinTrips) != null }
    }
    val status = homeStatus(ui, settings, yearRecap)
    val buddy = ui.buddy
    // 배웅 (NEXT_DESIGN 3.1): the farewell is the buddy talking, so it takes the speech bubble
    val bubble = ui.farewell?.takeIf { ui.trip == null }?.text ?: when (status) {
        is HomeStatus.Resume -> t("home_resume_bubble", "city" to status.item.card.route.toCity.substringAfterLast(' ').removeSuffix("시").removeSuffix("군"))
        is HomeStatus.InTrip -> t(if (status.paused) "home_paused" else "home_driving", "n" to status.episodes)
        else -> if (settings.recordOff) t("home_recordOffOn") else t("home_idle")
    }
    val iconRow = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CbIconButton(t("home_archive"), { onNavigate(Screen.Archive) }) { glyphArchive(it) }
            CbIconButton(t("home_buddy"), { onNavigate(Screen.Dex) }) { glyphBuddy(it) }
            CbIconButton(t("home_settings"), { onNavigate(Screen.Settings) }) { glyphSettings(it) }
            Spacer(Modifier.weight(1f))
            // "오늘은 기록 안 함": until the next ignition (UX V1 상단 토글, parked only)
            Text(t("home_recordOff"), style = Cb.type.label, color = Cb.colors.textSecondary)
            Spacer(Modifier.width(10.dp))
            Switch(settings.recordOff, { runtime.setRecordOff(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Cb.colors.amber))
        }
    }
    val buddyBlock = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BuddyAvatar(if (Cb.landscape) 140.dp else 120.dp, item = buddy.equipped, stage = buddy.stage)
            Spacer(Modifier.width(20.dp))
            Bubble(bubble, Modifier.weight(1f, fill = false))
        }
    }
    val statusBlock = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.farewell?.closeSeat?.takeIf { ui.trip == null }?.let { seat ->
                CbButton(t("farewell_close_btn", "seat" to t("seat_${seat.name}")), { runtime.closeWindow(seat) }, Modifier.fillMaxWidth())
            }
            when (status) {
                HomeStatus.NoLocation -> StatusCard(null, t("home_noLocation"), t("home_noLocation_action"), onAllowLocation, accent = Cb.colors.warn)
                is HomeStatus.Resume -> StatusCard(
                    t("home_resume_label"), t("home_resume_title", "title" to status.item.card.title, "n" to status.item.card.visibleEpisodes.size),
                    t("home_view"), { runtime.openScreening(status.item.card.tripId, summary = status.item.trip?.ceremonyStep == null, entry = "home") },
                )
                is HomeStatus.Completed -> StatusCard(t("home_done_label"), t("home_done"), t("home_open"), {
                    runtime.dismissCompleted(); onNavigate(Screen.CardDetail(status.tripId))
                }, accent = Cb.colors.amber)
                is HomeStatus.InTrip -> StatusCard(null, t(if (status.paused) "home_paused" else "home_driving", "n" to status.episodes), t("home_view"), onOpenLive, accent = Cb.colors.teal)
                is HomeStatus.Weekly -> StatusCard(
                    t("home_weekly_label"),
                    t("home_weekly", "n" to status.d.trips, "jam" to duration(status.d.jamMinutes), "rain" to status.d.rainDays),
                    t("home_open"), { runtime.dismissWeekly(); onNavigate(Screen.Archive) },
                )
                is HomeStatus.YearRecap -> StatusCard(
                    t("year_home_label"), t("year_home", "year" to status.year), t("home_open"),
                    { onNavigate(Screen.YearRecap(status.year)) }, accent = Cb.colors.amber,
                )
                is HomeStatus.RecordOff -> StatusCard(
                    null, t(if (status.paused) "home_recordPausedCard" else "home_recordOffCard"), t("home_recordOn"),
                    { if (status.paused) runtime.updateSettings { it.copy(recordPaused = false) } else runtime.setRecordOff(false) }, accent = Cb.colors.amber,
                )
                HomeStatus.Idle -> if (ui.passengers > 1) StatusCard(null, t("home_together", "n" to ui.passengers), null, {}, accent = Cb.colors.teal)
            }
        }
    }
    val inviteButton = @Composable {
        var soon by remember { mutableStateOf(false) }
        CbButton(ai.carbuddy.ui.components.soonLabel("invite_title"), {
            if (ai.carbuddy.core.Features.COMPANION_SERVER) onNavigate(Screen.Invite) else soon = true
        }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        if (soon) ai.carbuddy.ui.components.ComingSoonDialog { soon = false }
    }
    val edge = Dimens.edge(Cb.landscape)

    if (Cb.landscape) {
        Row(Modifier.fillMaxSize().padding(edge), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                iconRow(); buddyBlock(); statusBlock()
                Spacer(Modifier.weight(1f)); inviteButton()
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 88.dp)) {
                Text(t("home_recent"), style = Cb.type.label, color = Cb.colors.textSecondary)
                Spacer(Modifier.height(12.dp))
                RecentCards(ui.cards, max = 3, onOpen = { onNavigate(Screen.CardDetail(it)) })
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(edge), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            iconRow(); buddyBlock(); statusBlock()
            Text(t("home_recent"), style = Cb.type.label, color = Cb.colors.textSecondary)
            RecentCards(ui.cards, max = 1, onOpen = { onNavigate(Screen.CardDetail(it)) })
            Spacer(Modifier.height(8.dp)); inviteButton()
        }
    }
}

@Composable
private fun RecentCards(cards: List<CardItem>, max: Int, onOpen: (String) -> Unit) {
    if (cards.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("home_empty"), style = Cb.type.body, color = Cb.colors.textSecondary)
            CardPreview(exampleTitle = "바다로 새 버린 주말", card = null, modifier = Modifier.alpha(0.35f), onClick = {})
        }
        return
    }
    if (max == 1) CardPreview(card = cards.first().card, onClick = { onOpen(cards.first().card.tripId) })
    else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        cards.take(max).forEach { CardPreview(card = it.card, modifier = Modifier.weight(1f), onClick = { onOpen(it.card.tripId) }) }
        repeat(max - minOf(max, cards.size)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
fun CardPreview(card: Card?, modifier: Modifier = Modifier, exampleTitle: String? = null, onClick: () -> Unit) {
    val c = Cb.colors
    val star = card?.episodes?.firstOrNull { it.star }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusPanel)).background(c.bgSurface)
            .border(1.dp, c.outline.copy(alpha = 0.6f), RoundedCornerShape(Dimens.radiusPanel))
            .clickable(enabled = card != null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(if (card?.ambient == "CYAN" || card == null) c.cyan else c.amber))
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val meta = card?.let { buildString { append(it.dateLabel.substring(5)); if (it.routine) append(" · 일상"); star?.let { s -> append(" · ★ "); append(s.name) } } } ?: "11.07 토 · ★ 뺑뺑이"
            Text(meta, style = Cb.type.caption, color = c.textSecondary)
            Text(card?.title ?: exampleTitle.orEmpty(), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = card?.let {
                val n = it.visibleEpisodes.size
                val tail = if (it.receipt.mode == "LATE") signed(it.receipt.totalMin) + "분" else if (it.passengers <= 1) t("home_alone") else t("home_friends", "n" to it.passengers - 1)
                "${t("card_episodes", "n" to n)} · $tail"
            } ?: "에피소드 5 · +52분"
            Text(sub, style = Cb.type.caption, color = c.textSecondary)
            if (card != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                card.visibleEpisodes.sortedByDescending { it.score }.take(3).forEach { EpisodeIcon(it.type, it.name, box = 32.dp) }
            }
        }
    }
}
