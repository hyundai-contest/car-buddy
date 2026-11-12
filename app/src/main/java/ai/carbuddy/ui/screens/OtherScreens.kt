package ai.carbuddy.ui.screens

import ai.carbuddy.BuildConfig
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.data.SettingsEntity
import ai.carbuddy.trip.CardItem
import ai.carbuddy.trip.RuntimeUi
import ai.carbuddy.trip.TripRuntime
import ai.carbuddy.ui.components.Bubble
import ai.carbuddy.ui.components.BuddyAvatar
import ai.carbuddy.ui.components.ButtonKind
import ai.carbuddy.ui.components.CbButton
import ai.carbuddy.ui.components.CbIconButton
import ai.carbuddy.ui.components.EpisodeIcon
import ai.carbuddy.ui.components.Panel
import ai.carbuddy.ui.components.Pill
import ai.carbuddy.ui.components.glyphBack
import ai.carbuddy.ui.components.itemGlyph
import ai.carbuddy.ui.t
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Dimens.edge(Cb.landscape))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CbIconButton(t("back"), onBack) { glyphBack(it) }
            Spacer(Modifier.width(16.dp))
            Text(title, style = Cb.type.title, color = Cb.colors.textPrimary)
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

// ------------------------------------------------------------------------------- V2 invite

@Composable
fun InviteScreen(ui: RuntimeUi, onBack: () -> Unit) {
    val c = Cb.colors
    SubScreen(t("invite_title"), onBack) {
        // Companion server arrives in PT (SPEC Step 11–13). Until then the documented error state is shown.
        val qr = @Composable { m: Modifier ->
            Box(m.aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(c.bgSurface).border(2.dp, c.outline, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                Text("QR", style = Cb.type.display, color = c.textSecondary.copy(alpha = 0.4f))
            }
        }
        val info = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(t("invite_hint"), style = Cb.type.body, color = c.textPrimary)
                Text(t("invite_error"), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.warn)
                Text(t("invite_offline"), style = Cb.type.caption, color = c.textSecondary)
                if (ui.passengers > 1) Pill(t("home_together", "n" to ui.passengers), c.teal, c.onAccent)
                CbButton(t("invite_done"), onBack, Modifier.fillMaxWidth())
            }
        }
        if (Cb.landscape) Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) { qr(Modifier.weight(1f)); Box(Modifier.weight(1f)) { info() } }
        else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            qr(Modifier.fillMaxWidth(0.6f)); info()
        }
    }
}

// ------------------------------------------------------------------------------- V7 archive

@Composable
fun ArchiveScreen(cards: List<CardItem>, onOpen: (String) -> Unit, onBack: () -> Unit, onYearRecap: () -> Unit = {}) {
    var filter by remember { mutableStateOf(0) }
    var typeFilter by remember { mutableStateOf<EpisodeType?>(null) }
    var routineOpen by remember { mutableStateOf(false) }
    val c = Cb.colors
    SubScreen(t("archive_title"), onBack) {
        Column {
            // 연말 결산 entry (the sample shows until there are enough trips)
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(16.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(c.amber.copy(alpha = 0.35f), c.bgSurface)))
                    .clickable(onClick = onYearRecap).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val year = remember { ai.carbuddy.story.YearRecapBuilder().recapYear(System.currentTimeMillis()) }
                Column(Modifier.weight(1f)) {
                    Text(t("year_entry", "year" to year), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
                    Text(t("year_entry_sub"), style = Cb.type.label, color = c.textSecondary)
                }
                Text("›", style = Cb.type.title, color = c.amber)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("archive_all", "archive_with_friends", "archive_alone").forEachIndexed { i, k ->
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).clickable { filter = i }) {
                        Pill(t(k), if (filter == i) c.cyan else c.bgSurface, if (filter == i) c.onAccent else c.textPrimary, border = c.outline)
                    }
                }
            }
            // episode-type filter: only the types that appear in the archive
            val types = EpisodeType.entries.filter { t -> cards.any { item -> item.card.episodes.any { it.type == t.name } } }
            if (types.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (listOf<EpisodeType?>(null) + types).forEach { type ->
                        val on = typeFilter == type
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).clickable { typeFilter = type }) {
                            Pill(type?.let { t("name_${it.name}") } ?: t("archive_all_types"), if (on) c.amber else c.bgSurface, if (on) c.onAccent else c.textPrimary, border = c.outline)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val shown = cards.filter { when (filter) { 1 -> it.card.passengers > 1; 2 -> it.card.passengers <= 1; else -> true } }
                .filter { item -> typeFilter == null || item.card.episodes.any { it.type == typeFilter!!.name } }
            // '일상' (routine) cards fold into one group at the end (SPEC 6.4, UX 보관함)
            val (routine, special) = shown.partition { it.card.routine }
            if (shown.isEmpty()) Text(t("archive_empty"), style = Cb.type.body, color = c.textSecondary)
            LazyVerticalGrid(GridCells.Fixed(if (Cb.landscape) 2 else 1), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(special, key = { it.card.cardId }) { CardPreview(it.card, onClick = { onOpen(it.card.tripId) }) }
                if (routine.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "routine") {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(16.dp)).background(c.bgSurface)
                                .clickable { routineOpen = !routineOpen }.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t("archive_routine", "n" to routine.size), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary, modifier = Modifier.weight(1f))
                            Text(t(if (routineOpen) "archive_fold" else "archive_unfold"), style = Cb.type.label, color = c.cyan)
                        }
                    }
                    if (routineOpen) items(routine, key = { it.card.cardId }) { CardPreview(it.card, onClick = { onOpen(it.card.tripId) }) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- V8 buddy & dex

@Composable
fun DexScreen(runtime: TripRuntime, ui: RuntimeUi, onBack: () -> Unit) {
    val cards = ui.cards
    var tab by remember { mutableStateOf(0) }
    val c = Cb.colors
    val buddy = ui.buddy
    val seen = cards.flatMap { item -> item.card.episodes.map { it.type to item.card.dateLabel } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.min() }
    val supported = runtime.pipeline.episodes.activeTypes()
    SubScreen(t("dex_title"), onBack) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("dex_tab_buddy", "dex_tab_episodes", "dex_tab_regions").forEachIndexed { i, k ->
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).clickable { tab = i }) {
                        Pill(t(k), if (tab == i) c.cyan else c.bgSurface, if (tab == i) c.onAccent else c.textPrimary, border = c.outline)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            when (tab) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BuddyAvatar(140.dp, item = buddy.equipped, stage = buddy.stage)
                        Spacer(Modifier.width(24.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t("stage_${buddy.stage}"), style = Cb.type.title, color = c.textPrimary)
                            val next = when (buddy.stage) { "EGG" -> "SPROUT" to 5; "SPROUT" -> "COMPANION" to 20; "COMPANION" -> "VETERAN" to 60; else -> null }
                            next?.let { (to, goal) ->
                                Text(t("dex_stage_progress", "from" to t("stage_${buddy.stage}"), "to" to t("stage_$to"), "n" to buddy.tripCount, "goal" to goal), style = Cb.type.body, color = c.textSecondary)
                            }
                            Text("${buddy.totalKm.toInt()}km", style = Cb.type.label, color = c.textSecondary)
                        }
                    }
                    Text(t("dex_items"), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
                    LazyVerticalGrid(GridCells.Fixed(if (Cb.landscape) 4 else 2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ui.items) { it -> ItemCell(it, worn = buddy.equipped == it.id) { runtime.equipItem(if (buddy.equipped == it.id) null else it.id) } }
                    }
                }
                1 -> LazyVerticalGrid(GridCells.Fixed(if (Cb.landscape) 5 else 3), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(EpisodeType.entries.filter { it.dex }) { type ->
                        val first = seen[type.name]
                        val unsupported = type.name !in supported
                        Panel(Modifier.fillMaxWidth().alpha(if (first == null) 0.6f else 1f), padding = 14.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                EpisodeIcon(type.name, t("name_${type.name}"), box = 48.dp)
                                Text(t("name_${type.name}"), style = Cb.type.label, color = c.textPrimary, textAlign = TextAlign.Center)
                                Text(
                                    when { unsupported -> t("dex_unsupported"); first != null -> t("dex_first_seen", "date" to first.substring(2, 10)); else -> t("hint_${type.name}") },
                                    style = Cb.type.caption, color = c.textSecondary, textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val cities = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(buddy.citiesJson) }.getOrDefault(emptyList())
                    Text(t("dex_regions_count", "n" to cities.size), style = Cb.type.title, color = c.textPrimary)
                    RegionMap(cities.toSet(), Modifier.fillMaxWidth().height(if (Cb.landscape) 440.dp else 520.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        cities.forEach { Pill(it, c.bgSurface2, c.textPrimary) }
                    }
                }
            }
        }
    }
}

/**
 * V8 지역 도감 (UX_SPEC): the country silhouette (KoreaOutline) with every built-in 시·군·구 as a
 * dot, visited ones large and amber. No routes, only places (SPEC 1장 원칙 5).
 */
@Composable
private fun RegionMap(visited: Set<String>, modifier: Modifier) {
    val c = Cb.colors
    val places = ai.carbuddy.core.geo.OfflineCityResolver.PLACES
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(Dimens.radiusPanel)).background(c.bgSurface).padding(16.dp)) {
        // frame = the country silhouette (+ a little margin)
        val outline = ai.carbuddy.core.geo.KoreaOutline.all
        val latMin = outline.flatten().minOf { it.first } - 0.1; val latMax = outline.flatten().maxOf { it.first } + 0.1
        val lngMin = outline.flatten().minOf { it.second } - 0.1; val lngMax = outline.flatten().maxOf { it.second } + 0.1
        // keep the aspect ratio of the map (1° lng ≈ 0.8 × 1° lat at these latitudes)
        val geoW = (lngMax - lngMin) * 0.8; val geoH = latMax - latMin
        val scale = minOf(size.width / geoW, size.height / geoH).toFloat()
        val ox = (size.width - (geoW * scale).toFloat()) / 2
        val oy = (size.height - (geoH * scale).toFloat()) / 2
        fun at(lat: Double, lng: Double) = androidx.compose.ui.geometry.Offset(ox + ((lng - lngMin) * 0.8 * scale).toFloat(), oy + ((latMax - lat) * scale).toFloat())
        // silhouette (UX_SPEC 지역 도감): filled land + a thin coast line
        outline.forEach { poly ->
            val path = androidx.compose.ui.graphics.Path().apply {
                poly.forEachIndexed { i, (la, ln) -> val o = at(la, ln); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                close()
            }
            drawPath(path, c.bgSurface2)
            drawPath(path, c.outline, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        places.filter { it.full !in visited }.forEach { drawCircle(c.textSecondary.copy(alpha = 0.45f), 3.dp.toPx(), at(it.lat, it.lng)) }
        places.filter { it.full in visited }.forEach { drawCircle(c.amber, 8.dp.toPx(), at(it.lat, it.lng)) }
    }
}

/** V8 item: owned → tap to wear (again to take off); locked → its rule and progress. */
@Composable
private fun ItemCell(p: ai.carbuddy.story.BuddyEngine.ItemProgress, worn: Boolean, onTap: () -> Unit) {
    val c = Cb.colors
    Panel(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touch).alpha(if (p.have) 1f else 0.55f)
            .then(if (p.have) Modifier.clickable(onClick = onTap) else Modifier)
            .then(if (worn) Modifier.border(2.dp, c.amber, RoundedCornerShape(Dimens.radiusPanel)) else Modifier),
        padding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (p.have) c.amber else c.bgSurface2), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) { itemGlyph(p.id, size.width, androidx.compose.ui.graphics.Color(0xFF12203A)) }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(t("item_${p.id}"), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
                Text(
                    when { worn -> t("dex_item_equipped"); p.have -> t("dex_item_tap"); else -> t("dex_item_progress", "rule" to t("item_rule_${p.id}"), "now" to p.now, "goal" to p.goal) },
                    style = Cb.type.caption, color = if (worn) c.amber else c.textSecondary,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------- V9 settings

@Composable
fun SettingsScreen(settings: SettingsEntity, runtime: TripRuntime, places: List<ai.carbuddy.data.PlaceEntity>, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val c = Cb.colors
    SubScreen(t("settings_title"), onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            @Composable fun toggle(key: String, value: Boolean, set: (SettingsEntity, Boolean) -> SettingsEntity) {
                Row(
                    Modifier.fillMaxWidth().height(Dimens.touch).clip(RoundedCornerShape(16.dp)).background(c.bgSurface)
                        .clickable { runtime.updateSettings { set(it, !value) } }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t(key), style = Cb.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Switch(value, { v -> runtime.updateSettings { set(it, v) } }, colors = SwitchDefaults.colors(checkedTrackColor = c.cyan))
                }
            }
            @Composable fun section(key: String) = Text(t(key), style = Cb.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 12.dp))
            section("settings_section_record")
            toggle("settings_record_paused", settings.recordPaused) { s, v -> s.copy(recordPaused = v) }
            toggle("settings_guest_record", settings.guestRecord) { s, v -> s.copy(guestRecord = v) }
            toggle("settings_short_trip", settings.shortTripScreening) { s, v -> s.copy(shortTripScreening = v) }
            section("settings_section_sound")
            toggle("settings_recap", settings.recapOn) { s, v -> s.copy(recapOn = v) }
            toggle("settings_screening_voice", settings.screeningVoiceOn) { s, v -> s.copy(screeningVoiceOn = v) }
            toggle("settings_ambient", settings.ambientOn) { s, v -> s.copy(ambientOn = v) }
            toggle("settings_clip", settings.clipOn) { s, v -> s.copy(clipOn = v) }
            section("settings_section_places")
            if (places.isEmpty()) Text(t("settings_places_empty"), style = Cb.type.caption, color = c.textSecondary)
            places.forEachIndexed { i, p ->
                // no map: "자주 머무는 곳 1" + preset names (UX V9, 키보드 입력 최소화)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.bgSurface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name ?: t("place_label", "n" to i + 1), style = Cb.type.body.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("place_name_home", "place_name_work", "place_name_school", "place_name_parents").forEach { k ->
                            val name = t(k)
                            Box(Modifier.clip(RoundedCornerShape(999.dp)).clickable { runtime.renamePlace(p.id, if (p.name == name) null else name) }) {
                                Pill(name, if (p.name == name) c.cyan else c.bgSurface2, if (p.name == name) c.onAccent else c.textPrimary)
                            }
                        }
                    }
                }
            }
            // 체험 모드: a scenario played on the parked car, nothing kept (tester build)
            section("demo_section")
            Text(t("demo_desc"), style = Cb.type.caption, color = c.textSecondary)
            var demoRefused by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                CbButton(t("demo_scenario_a"), { if (runtime.startDemo("scenario_a.jsonl")) onBack() else demoRefused = true }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                CbButton(t("demo_scenario_b"), { if (runtime.startDemo("commute_b.jsonl")) onBack() else demoRefused = true }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
            }
            if (demoRefused) AlertDialog(
                onDismissRequest = { demoRefused = false },
                text = { Text(t("demo_refused"), style = Cb.type.body) },
                confirmButton = { TextButton(onClick = { demoRefused = false }) { Text(t("coming_soon_ok"), style = Cb.type.button) } },
            )
            section("settings_section_phone")
            Text(ai.carbuddy.ui.components.soonLabel("pair_title"), style = Cb.type.body, color = c.textSecondary)
            Text(t("coming_soon_body"), style = Cb.type.caption, color = c.textSecondary)
            section("settings_section_privacy")
            toggle("settings_route_trim", settings.routeTrimOn) { s, v -> s.copy(routeTrimOn = v) }
            if (BuildConfig.DEBUG) toggle("settings_do_dev", settings.doApproved) { s, v -> s.copy(doApproved = v) }
            Spacer(Modifier.height(8.dp))
            CbButton(t("settings_delete_cards"), { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
            CbButton(t("settings_delete_route"), { runtime.deleteRouteRecords() }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
            CbButton(t("settings_delete_places"), { runtime.deletePlaces() }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
            Text(t("settings_privacy"), style = Cb.type.caption, color = c.textSecondary)
            Text(t("settings_version", "v" to BuildConfig.VERSION_NAME), style = Cb.type.caption, color = c.textSecondary)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text(t("settings_delete_confirm"), style = Cb.type.body) },
        confirmButton = { TextButton(onClick = { runtime.deleteAllCards(); confirm = false }) { Text(t("settings_delete"), style = Cb.type.button, color = c.warn) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(t("settings_cancel"), style = Cb.type.button) } },
    )
}

