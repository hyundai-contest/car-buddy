package ai.carbuddy.ui.screens

import ai.carbuddy.ceremony.CeremonyController
import ai.carbuddy.ceremony.CeremonyState
import ai.carbuddy.ceremony.CeremonyStep
import ai.carbuddy.ui.components.Bubble
import ai.carbuddy.ui.components.BuddyAvatar
import ai.carbuddy.ui.components.ButtonKind
import ai.carbuddy.ui.components.CbButton
import ai.carbuddy.ui.components.AwardCard
import ai.carbuddy.ui.components.Pill
import ai.carbuddy.ui.components.ReceiptCard
import ai.carbuddy.ui.components.Toast
import ai.carbuddy.ui.components.ROUTE_BG
import ai.carbuddy.ui.components.RouteCanvas
import ai.carbuddy.ui.components.episodeColor
import ai.carbuddy.ui.components.moodFor
import ai.carbuddy.ui.t
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.Dimens
import ai.carbuddy.ui.theme.Motion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CeremonyScreen(s: CeremonyState, controller: CeremonyController, onComplete: (String) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val fadeInMs = ai.carbuddy.ui.theme.motionMs(Motion.LONG); val fadeOutMs = ai.carbuddy.ui.theme.motionMs(Motion.MEDIUM)
        AnimatedContent(
            targetState = s.index,
            transitionSpec = { fadeIn(tween(fadeInMs)) togetherWith fadeOut(tween(fadeOutMs)) },
            label = "step",
        ) { index ->
            val state = if (index == s.index) s else s.copy(index = index)
            when (val step = state.step) {
                CeremonyStep.Route -> RouteSlide(state, controller)
                is CeremonyStep.Slide -> ScreeningSlide(state, step, controller)
                CeremonyStep.Receipt -> ReceiptStep(state, controller, onComplete)
                CeremonyStep.Awards -> AwardsStep(state, controller, onComplete)
                CeremonyStep.HalftimeEnd -> HalftimeEndStep(state, controller)
            }
        }
        if (s.undoEpisodeId != null) {
            Toast(t("screening_hide_toast"), t("screening_undo"), controller::undoHide, Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}

/** Segmented progress: one bar per step, the current one fills with time. */
@Composable
private fun StepProgress(s: CeremonyState, modifier: Modifier = Modifier) {
    val c = Cb.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.steps.indices.forEach { i ->
            val frac = when {
                i < s.index -> 1f
                i > s.index -> 0f
                else -> (s.elapsedMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
            }
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.textSecondary.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(c.textPrimary))
            }
        }
    }
}

@Composable
private fun TopBar(badge: String, sub: String?, onClose: () -> Unit, onFull: (() -> Unit)? = null) {
    val c = Cb.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill(badge, c.cyan, c.onAccent)
        sub?.let { Spacer(Modifier.width(14.dp)); Text(it, style = Cb.type.body, color = c.textSecondary) }
        Spacer(Modifier.weight(1f))
        if (onFull != null) Box(Modifier.size(width = 120.dp, height = Dimens.touch).clickable(onClick = onFull), contentAlignment = Alignment.Center) {
            Text(t("screening_full"), style = Cb.type.label, color = c.cyan)
        }
        Box(Modifier.size(Dimens.touch).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Text("${t("screening_close")} ✕", style = Cb.type.label, color = c.textSecondary)
        }
    }
}

// ------------------------------------------------------------------------------- V3 screening

@Composable
private fun ScreeningSlide(s: CeremonyState, step: CeremonyStep.Slide, controller: CeremonyController) {
    val c = Cb.colors
    val e = s.episode(step.episodeId) ?: return
    val slideNo = s.steps.take(s.index + 1).count { it is CeremonyStep.Slide }
    val nextStep = s.steps.getOrNull(s.index + 1)
    val nextLabel = when (nextStep) {
        is CeremonyStep.Slide -> t("screening_next", "name" to (s.episode(nextStep.episodeId)?.name ?: ""))
        CeremonyStep.Receipt -> t("screening_next_receipt")
        CeremonyStep.HalftimeEnd -> t("halftime_next")
        else -> t("screening_next", "name" to "")
    }
    val badge = when { s.halftime -> "halftime_badge"; s.summary -> "screening_summary_badge"; else -> "screening_badge" }
    val note = if (e.type == "LOST_LOOP") t("screening_track_note") else null
    val visual = @Composable { m: Modifier ->
        Box(m.clickable(remember { MutableInteractionSource() }, null) { controller.togglePause() }) {
            EpisodeVisual(e, s.tracks[e.id].orEmpty(), note, Modifier.fillMaxSize())
            if (s.paused) Pill(t("screening_paused"), c.paper, c.onPaper, Modifier.align(Alignment.Center))
        }
    }
    val text = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${e.time} · ${e.name}", style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.amber)
            Text(e.text, style = Cb.type.display, color = c.textPrimary)
            if (e.buddyComment.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                BuddyAvatar(56.dp, moodFor(e.type))
                Spacer(Modifier.width(14.dp))
                Bubble(e.buddyComment)
            }
        }
    }
    val buttons = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CbButton(if (Cb.landscape) t("screening_hide") else t("screening_hide_short"), controller::hideCurrent, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
            CbButton(nextLabel, controller::next, Modifier.weight(1.4f))
        }
    }
    val swipe = Modifier.pointerInput(s.index) {
        var total = 0f
        detectHorizontalDragGestures(onDragEnd = {
            if (total < -80) controller.next() else if (total > 80) controller.previous()
            total = 0f
        }) { _, dx -> total += dx }
    }
    val edge = Dimens.edge(Cb.landscape)
    if (Cb.landscape) {
        Column(Modifier.fillMaxSize().then(swipe).padding(edge)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TopBar(t(badge), t("screening_progress", "i" to slideNo, "n" to s.slideCount), controller::close, if (s.summary) controller::showFull else null) }
                Spacer(Modifier.width(24.dp))
                StepProgress(s, Modifier.width(320.dp))
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                visual(Modifier.weight(0.6f).fillMaxHeight())
                Column(Modifier.weight(0.4f).fillMaxHeight()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { text() }
                    buttons()
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().then(swipe).padding(edge)) {
            TopBar(t(if (s.halftime) "halftime_badge_short" else "screening_badge_short", "i" to slideNo, "n" to s.slideCount), null, controller::close, if (s.summary) controller::showFull else null)
            Spacer(Modifier.height(16.dp))
            StepProgress(s, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            visual(Modifier.fillMaxWidth().weight(0.45f))
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(0.55f).verticalScroll(rememberScrollState())) { text() }
            Spacer(Modifier.height(16.dp))
            buttons()
        }
    }
}

// ------------------------------------------------------------------------------- 중간 상영 end card

/** NEXT_DESIGN A: "여기까지가 전반전" — the rest waits for the arrival; closes by itself or on [출발하자]. */
@Composable
private fun HalftimeEndStep(s: CeremonyState, controller: CeremonyController) {
    val c = Cb.colors
    val body = s.remainMin?.let { t("halftime_end_remain", "time" to duration(it)) } ?: t("halftime_end_later")
    val text = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(t("halftime_end_title"), style = Cb.type.display, color = c.textPrimary)
            Text(body, style = Cb.type.body, color = c.textSecondary)
        }
    }
    val edge = Dimens.edge(Cb.landscape)
    Column(Modifier.fillMaxSize().padding(edge)) {
        TopBar(t("halftime_badge"), null, controller::close)
        Spacer(Modifier.height(16.dp))
        StepProgress(s, Modifier.fillMaxWidth())
        if (Cb.landscape) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                BuddyAvatar(160.dp, item = s.card.buddy.item, stage = s.card.buddy.stage)
                Box(Modifier.weight(1f)) { text() }
            }
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                BuddyAvatar(140.dp, item = s.card.buddy.item, stage = s.card.buddy.stage)
                Spacer(Modifier.height(28.dp))
                text()
            }
        }
        CbButton(t("halftime_go"), controller::finish, Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------------------- V4 receipt

@Composable
private fun ReceiptStep(s: CeremonyState, controller: CeremonyController, onComplete: (String) -> Unit) {
    val r = s.card.receipt
    val visible = (s.elapsedMs / 300).toInt()          // lines print 0.3 s apart (Medium)
    val texts = receiptTexts(r, long = true)
    val receipt = @Composable { m: Modifier ->
        ReceiptCard(r, texts, m, detailed = true, footer = t("receipt_footer"), visibleLines = visible, starType = s.card.grand?.type)
    }
    val edge = Dimens.edge(Cb.landscape)
    Column(Modifier.fillMaxSize().padding(edge)) {
        TopBar(t("receipt_title"), null, controller::close)
        Spacer(Modifier.height(16.dp))
        StepProgress(s, Modifier.fillMaxWidth())
        if (Cb.landscape) {
            Row(Modifier.weight(1f).padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BuddyAvatar(140.dp, item = s.card.buddy.item, stage = s.card.buddy.stage)
                    Spacer(Modifier.height(20.dp))
                    Bubble(r.buddyLine ?: t("receipt_buddy_line"))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { receipt(Modifier.fillMaxWidth()) }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.Center) {
                receipt(Modifier.fillMaxWidth())
            }
        }
        // no awards (no episodes): the receipt is the last step
        if (s.index >= s.steps.lastIndex) CbButton(t("ceremony_complete"), { onComplete(s.tripId) }, Modifier.fillMaxWidth())
        else CbButton(t("screening_next", "name" to t("ceremony_badge")), controller::next, Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------------------- V5 awards

@Composable
private fun AwardsStep(s: CeremonyState, controller: CeremonyController, onComplete: (String) -> Unit) {
    val c = Cb.colors
    val card = s.card
    val awards = card.awards
    val anyVotes = awards.any { it.votes > 0 }
    val cards = @Composable { horizontal: Boolean ->
        val items = awards.mapIndexed { i, a -> i to a }
        if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items.forEach { (i, a) -> AwardCard(a, i < s.revealedAwards, t("ceremony_votes", "n" to a.votes), t("ceremony_grand"), Modifier.weight(1f).height(200.dp), showVotes = anyVotes) }
        } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items.forEach { (i, a) -> AwardCard(a, i < s.revealedAwards, t("ceremony_votes", "n" to a.votes), t("ceremony_grand"), Modifier.fillMaxWidth(), showVotes = anyVotes) }
        }
    }
    val voteArea = @Composable {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusPanel)).background(c.bgSurface).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (anyVotes) {
                listOf("민", "준", "서").take(maxOf(1, card.passengers - 1)).forEach {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.teal), contentAlignment = Alignment.Center) { Text(it, style = Cb.type.label, color = c.onAccent) }
                    Spacer(Modifier.width(8.dp))
                }
                Text(t("ceremony_vote_note", "n" to card.passengers - 1), style = Cb.type.body, color = c.textPrimary)
            } else {
                BuddyAvatar(48.dp)
                Spacer(Modifier.width(14.dp))
                // no companion server / nobody voted → the buddy picks (UX_SPEC V5 상태)
                Text(t(if (card.passengers > 1) "ceremony_buddyPick" else "ceremony_buddyPick_solo"), style = Cb.type.body, color = c.textPrimary)
            }
        }
    }
    val buttons = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CbButton(t("ceremony_complete"), { onComplete(s.tripId) }, Modifier.weight(1f))
            CbButton(t("ceremony_replay"), controller::replay, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
        }
    }
    val edge = Dimens.edge(Cb.landscape)
    val header = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(t("ceremony_badge"), c.amber, c.onAccent)
            Spacer(Modifier.width(14.dp))
            Text(card.title, style = Cb.type.body, color = c.textSecondary, modifier = Modifier.weight(1f))
            Box(Modifier.size(Dimens.touch).clickable(onClick = controller::close), contentAlignment = Alignment.Center) {
                Text("${t("screening_close")} ✕", style = Cb.type.label, color = c.textSecondary)
            }
        }
    }
    if (Cb.landscape) {
        Row(Modifier.fillMaxSize().padding(edge), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(0.64f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                header()
                Text(t("ceremony_title"), style = Cb.type.display, color = c.textPrimary)
                cards(true)
                voteArea()
                Spacer(Modifier.weight(1f))
                Row { Box(Modifier.width(520.dp)) { buttons() } }
            }
            Column(Modifier.weight(0.36f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                val r = card.receipt
                ReceiptCard(r, receiptTexts(r, long = true), Modifier.fillMaxWidth(), detailed = true, footer = t("receipt_footer"), starType = card.grand?.type)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(edge)) {
            header()
            Spacer(Modifier.height(16.dp))
            StepProgress(s, Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(t("ceremony_title"), style = Cb.type.display, color = c.textPrimary)
                cards(false)
                voteArea()
            }
            buttons()
        }
    }
}


// ------------------------------------------------------------------------------- V3 첫 장: 오늘의 길

@Composable
private fun RouteSlide(s: CeremonyState, controller: CeremonyController) {
    val c = Cb.colors
    val card = s.card
    val shape = card.routeShape ?: return
    val firstSlide = s.steps.getOrNull(s.index + 1) as? CeremonyStep.Slide
    val nextName = firstSlide?.let { s.episode(it.episodeId)?.short } ?: t("receipt_title")
    val route = @Composable { m: Modifier ->
        Box(m.clip(RoundedCornerShape(24.dp)).background(ROUTE_BG)) {
            RouteCanvas(
                shape, Modifier.fillMaxSize(), startLabel = card.route.fromCity, endLabel = card.route.toCity,
                insetLabel = shape.insets.firstOrNull()?.let { t("route_zoom", "label" to it.label) },
            )
            Text(t("route_trimNote"), style = Cb.type.caption, color = c.textSecondary, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
        }
    }
    val stats = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            listOf(
                duration(card.route.durationMin) to t("route_stat_time"),
                "${card.route.distanceKm}km" to t("route_stat_km"),
                "${card.visibleEpisodes.size}" to t("route_stat_episodes"),
            ).forEach { (v, l) ->
                Column { Text(v, style = Cb.type.title, color = c.textPrimary); Text(l, style = Cb.type.caption, color = c.textSecondary) }
            }
        }
    }
    val buttons = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CbButton(t("route_skip"), controller::next, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
            CbButton(t("screening_next", "name" to nextName), controller::next, Modifier.weight(1.4f))
        }
    }
    val edge = Dimens.edge(Cb.landscape)
    if (Cb.landscape) {
        Column(Modifier.fillMaxSize().padding(edge)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TopBar(t(if (s.halftime) "halftime_badge" else "screening_badge"), t("route_title"), controller::close, if (s.summary) controller::showFull else null) }
                Spacer(Modifier.width(24.dp))
                StepProgress(s, Modifier.width(320.dp))
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                route(Modifier.weight(0.6f).fillMaxHeight())
                Column(Modifier.weight(0.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(card.dateLabel, style = Cb.type.label, color = c.cyan)
                    Text(t("route_title"), style = Cb.type.display, color = c.textPrimary)
                    Text("${card.route.fromCity} → ${card.route.toCity}", style = Cb.type.body, color = c.textSecondary)
                    stats()
                    Column(
                        Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusPanel)).background(c.bgSurface)
                            .verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        card.visibleEpisodes.forEach { e ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { controller.jumpTo(e.id) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(episodeColor(e.type, c.amber, c.cyan)))
                                Spacer(Modifier.width(12.dp))
                                Text(e.time, style = Cb.type.label, color = c.textSecondary)
                                Spacer(Modifier.width(12.dp))
                                Text(e.short, style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary, modifier = Modifier.weight(1f))
                                if (e.star) Text("★ " + t("card_star"), style = Cb.type.caption, color = c.amber)
                            }
                        }
                    }
                    buttons()
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(edge)) {
            TopBar(t(if (s.halftime) "halftime_badge" else "screening_badge"), null, controller::close, if (s.summary) controller::showFull else null)
            Spacer(Modifier.height(16.dp))
            StepProgress(s, Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text(t("route_title") + " · " + card.dateLabel, style = Cb.type.title, color = c.textPrimary)
            Spacer(Modifier.height(12.dp))
            route(Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(16.dp))
            stats()
            Spacer(Modifier.height(16.dp))
            buttons()
        }
    }
}
