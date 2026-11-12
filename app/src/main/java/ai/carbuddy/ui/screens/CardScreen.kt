package ai.carbuddy.ui.screens

import ai.carbuddy.card.Card
import ai.carbuddy.card.CardEpisode
import ai.carbuddy.trip.RuntimeUi
import ai.carbuddy.trip.TripRuntime
import ai.carbuddy.ui.components.Banner
import ai.carbuddy.ui.components.BuddyAvatar
import ai.carbuddy.ui.components.ButtonKind
import ai.carbuddy.ui.components.CbButton
import ai.carbuddy.ui.components.CbIconButton
import ai.carbuddy.ui.components.EpisodeRow
import ai.carbuddy.ui.components.Panel
import ai.carbuddy.ui.components.Pill
import ai.carbuddy.ui.components.ReceiptCard
import ai.carbuddy.ui.components.glyphBack
import ai.carbuddy.ui.t
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.Dimens
import ai.carbuddy.ui.theme.Motion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CardScreen(tripId: String, ui: RuntimeUi, runtime: TripRuntime, onBack: () -> Unit) {
    val item = ui.cards.firstOrNull { it.card.tripId == tripId } ?: return
    val card = item.card
    var back by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardEpisode?>(null) }
    var pickTitle by remember { mutableStateOf(false) }
    var sendToPhone by remember { mutableStateOf(false) }
    val flip by animateFloatAsState(if (back) 180f else 0f, tween(ai.carbuddy.ui.theme.motionMs(Motion.MEDIUM)), label = "flip")
    val edge = Dimens.edge(Cb.landscape)

    val face = @Composable { m: Modifier ->
        Box(m.graphicsLayer { rotationY = flip; cameraDistance = 24f * density }) {
            if (flip <= 90f) CardFront(card, Modifier.fillMaxWidth(), onTitle = { pickTitle = true }, onEpisodeLongPress = { editing = it }, buddyName = ui.buddy.name)
            else CardBack(card, Modifier.fillMaxWidth().graphicsLayer { rotationY = 180f })
        }
    }
    val bottomBar = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CbButton(ai.carbuddy.ui.components.soonLabel("card_sendToPhone"), { sendToPhone = true }, Modifier.weight(1f))
            CbButton(if (back) t("card_front") else t("card_back"), { back = !back }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
        }
    }
    Column(Modifier.fillMaxSize().padding(edge)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CbIconButton(t("back"), onBack) { glyphBack(it) }
            Spacer(Modifier.width(16.dp))
            Text(card.dateLabel, style = Cb.type.title, color = Cb.colors.textPrimary, modifier = Modifier.weight(1f))
        }
        // 배웅: on the card only the thing to mind (the story is the card itself)
        ui.farewell?.speakText?.let { mind ->
            Spacer(Modifier.height(12.dp))
            Banner(mind, ui.farewell.closeSeat?.let { t("exit_close") }, { ui.farewell.closeSeat?.let(runtime::closeWindow) })
        }
        Spacer(Modifier.height(16.dp))
        if (Cb.landscape) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState())) { face(Modifier) }
                Column(Modifier.weight(0.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Panel {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${card.route.fromCity} → ${card.route.toCity}", style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = Cb.colors.textPrimary)
                            Text("${card.route.distanceKm}km · ${duration(card.route.durationMin)}", style = Cb.type.label, color = Cb.colors.textSecondary)
                            AwardPills(card)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    bottomBar()
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { face(Modifier) }
            Spacer(Modifier.height(16.dp))
            bottomBar()
        }
    }

    editing?.let { e ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(e.name, style = Cb.type.title) },
            text = { Text(e.text, style = Cb.type.body) },
            confirmButton = {
                TextButton(onClick = { runtime.hideEpisode(tripId, e.id, !e.hidden); editing = null }) {
                    Text(if (e.hidden) t("card_unhide") else t("card_hide"), style = Cb.type.button)
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(t("settings_cancel"), style = Cb.type.button) } },
        )
    }
    if (pickTitle) {
        AlertDialog(
            onDismissRequest = { pickTitle = false },
            title = { Text(t("card_pick_title"), style = Cb.type.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.titleCandidates.forEach { title ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (title == card.title) Cb.colors.bgSurface2 else Cb.colors.bgSurface)
                                .clickable { runtime.chooseTitle(tripId, title); pickTitle = false }.padding(18.dp),
                        ) { Text(title, style = Cb.type.body, color = Cb.colors.textPrimary) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTitle = false }) { Text(t("settings_cancel"), style = Cb.type.button) } },
        )
    }
    if (sendToPhone && !ai.carbuddy.core.Features.COMPANION_SERVER) ai.carbuddy.ui.components.ComingSoonDialog { sendToPhone = false }
    else if (sendToPhone) {
        AlertDialog(
            onDismissRequest = { sendToPhone = false },
            title = { Text(t("card_sendToPhone"), style = Cb.type.title) },
            // TODO(PT, Step 13): driver-only QR from the companion server (POST /api/trips/{id}/join-tokens)
            text = { Text(t("invite_offline") + "\n" + t("card_phone_qr_hint"), style = Cb.type.body) },
            confirmButton = { TextButton(onClick = { sendToPhone = false }) { Text(t("invite_done"), style = Cb.type.button) } },
        )
    }
}

private val CARD_NAVY = androidx.compose.ui.graphics.Color(0xFF101C33)

fun duration(min: Int): String = if (min >= 60) "${min / 60}시간 ${min % 60}분" else "${min}분"

@Composable
fun AwardPills(card: Card) {
    val c = Cb.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        card.awards.forEach { a ->
            if (a.grand) Pill("🏆 ${a.name}", c.amber, c.onAccent) else Pill(a.name, c.paper, c.onPaper)
        }
    }
}

/** Card front (mockup 1): header · buddy + title · route · timeline · receipt · awards. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardFront(card: Card, modifier: Modifier = Modifier, onTitle: () -> Unit = {}, onEpisodeLongPress: (CardEpisode) -> Unit = {}, buddyName: String = "버디") {
    val c = Cb.colors
    Column(
        modifier.clip(RoundedCornerShape(Dimens.radiusCard)).background(if (c.isDark) CARD_NAVY else c.bgSurface),
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(if (card.ambient == "CYAN") c.cyan else c.amber))
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${card.dateLabel} · ${t("card_trip_no", "n" to card.tripNo)}", style = Cb.type.label, color = c.textSecondary, modifier = Modifier.weight(1f))
                Pill(t("card_episodes", "n" to card.visibleEpisodes.size), c.cyan, c.onAccent)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BuddyAvatar(84.dp, item = card.buddy.item, stage = card.buddy.stage)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.clickable(onClick = onTitle)) {
                    Text(card.title, style = Cb.type.display, color = c.textPrimary)
                    Text("${card.route.fromCity} → ${card.route.toCity} · ${card.route.distanceKm}km · ${duration(card.route.durationMin)}", style = Cb.type.label, color = c.textSecondary)
                    // 확장 A: the buddy's change shows on the card front
                    val copyBook = ai.carbuddy.ui.LocalCopy.current
                    val news = listOfNotNull(
                        card.buddy.grewTo?.let { t("card_buddy_grew", "name" to buddyName, "stage" to t("stage_$it")) },
                        card.buddy.newItems.takeIf { it.isNotEmpty() }?.let { l -> t("card_buddy_item", "items" to l.joinToString(" · ") { copyBook.get("item_$it") }) },
                    )
                    if (news.isNotEmpty()) Text(news.joinToString("  "), style = Cb.type.label.copy(fontWeight = FontWeight.Bold), color = c.amber)
                }
            }
            card.routeShape?.let { shape ->
                Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(Dimens.radiusPanel)).background(ai.carbuddy.ui.components.ROUTE_BG)) {
                    ai.carbuddy.ui.components.RouteCanvas(shape, Modifier.fillMaxSize(), animate = false, showPins = false)
                    Text(t("route_title"), style = Cb.type.label, color = c.textSecondary, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                }
            }
            if (card.episodes.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusPanel)).background(c.bgSurface2.copy(alpha = 0.6f)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    card.gaps.forEach { g ->
                        Text(t("card_gap", "from" to g.fromLabel, "to" to g.toLabel), style = Cb.type.label, color = c.textSecondary.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    card.episodes.forEach { e ->
                        val clip = card.attachments.clips.firstOrNull { it.clipId == e.clipId }
                        EpisodeRow(
                            e, t("card_star"), t("card_hidden_label"),
                            Modifier.combinedClickable(onClick = {}, onLongClick = { onEpisodeLongPress(e) }),
                            extra = clip?.let { "\"${it.caption}\" — ${it.by}" },
                        )
                    }
                }
            } else {
                Text(card.buddyLine ?: t("card_zero"), style = Cb.type.body, color = c.textSecondary)
            }
            ReceiptCard(card.receipt, receiptTexts(card.receipt), Modifier.fillMaxWidth(), compact = true, starType = card.grand?.type)
            if (card.awards.isNotEmpty()) AwardPills(card)
            if (card.attachments.driverMoments.isNotEmpty()) {
                Text("${t("driver_moment")} · ${card.attachments.driverMoments.size}", style = Cb.type.label, color = c.amber)
            }
        }
    }
}

/** Driver-only back (확장 B): exit check, parking memo, fuel. Never shared. */
@Composable
fun CardBack(card: Card, modifier: Modifier = Modifier) {
    val c = Cb.colors
    val b = card.back
    Column(
        modifier.clip(RoundedCornerShape(Dimens.radiusCard)).background(c.bgSurface).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(t("card_back_title"), style = Cb.type.title, color = c.textPrimary)
        val problems = b.windowsOpen.map { t("card_back_window", "seat" to t("seat_$it")) } +
            listOfNotNull(t("card_back_trunk").takeIf { b.trunkOpen }, t("card_back_lights").takeIf { b.lightsOn })
        if (problems.isEmpty()) Text("✓ ${t("card_back_all_closed")}", style = Cb.type.body, color = c.teal)
        problems.forEach { Text("⚠ $it", style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.warn) }
        Text(t("card_back_parking"), style = Cb.type.label, color = c.textSecondary)
        Text(b.parkingMemo ?: t("card_back_parking_none"), style = Cb.type.body, color = c.textPrimary)
        if (b.fuelLow) Text(t("card_back_fuel_low"), style = Cb.type.body, color = c.amber)
    }
}
