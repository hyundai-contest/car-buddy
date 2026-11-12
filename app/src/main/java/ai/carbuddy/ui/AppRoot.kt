package ai.carbuddy.ui

import ai.carbuddy.devtools.DevOverlay
import ai.carbuddy.devtools.DrivingDevView
import ai.carbuddy.story.CopyBook
import ai.carbuddy.trip.TripRuntime
import ai.carbuddy.ui.screens.ArchiveScreen
import ai.carbuddy.ui.screens.CardScreen
import ai.carbuddy.ui.screens.CeremonyScreen
import ai.carbuddy.ui.screens.DexScreen
import ai.carbuddy.ui.screens.HomeScreen
import ai.carbuddy.ui.screens.InviteScreen
import ai.carbuddy.ui.screens.OnboardingScreen
import ai.carbuddy.ui.screens.SettingsScreen
import ai.carbuddy.ui.theme.Cb
import ai.carbuddy.ui.theme.CarBuddyTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import ai.carbuddy.voice.UiMode
import ai.carbuddy.ui.screens.LiveDrivingScreen
import ai.carbuddy.ui.screens.LiveParkedScreen
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

sealed interface Screen {
    data object Home : Screen
    data object Invite : Screen
    data object Archive : Screen
    data object Dex : Screen
    data object Settings : Screen
    data class CardDetail(val tripId: String) : Screen
    /** 연말 결산; null year = the recap year now (December: this year, January: last year). */
    data class YearRecap(val year: Int? = null) : Screen
}

val LocalCopy = staticCompositionLocalOf<CopyBook> { error("no CopyBook") }

/** Shorthand for copy lookups inside composables. */
@Composable fun t(key: String, vararg vars: Pair<String, Any?>): String = LocalCopy.current.get(key, *vars)

@Composable
fun AppRoot(runtime: TripRuntime, copy: CopyBook, debug: Boolean, moveToBack: () -> Unit, requestPermissions: () -> Unit, openPermissionSettings: () -> Unit = requestPermissions) {
    val ui by runtime.ui.collectAsStateWithLifecycle()
    val ceremony by runtime.ceremony.state.collectAsStateWithLifecycle()
    val settings by runtime.settings.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    // V10 opens instead of home while a trip is open; [홈] dismisses it for that trip
    val liveDismissed = remember { mutableStateOf<String?>(null) }
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else moveToBack() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        CarBuddyTheme(landscape = landscape) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val reduceMotion = androidx.compose.runtime.remember {
                runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
            }
            CompositionLocalProvider(LocalCopy provides copy, ai.carbuddy.ui.theme.LocalReduceMotion provides reduceMotion) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Cb.colors.bgBase).safeDrawingPadding()) {
                    // UiModeResolver (CLAUDE.md 규칙 3): driving → V10 driving mode only, or nothing at all
                    when (ui.uiMode) {
                        UiMode.BLOCKED -> {
                            // the system shows its own block screen; we draw nothing (debug: developer view for the video)
                            if (debug) DrivingDevView(ui, runtime)
                            return@BoxWithConstraints
                        }
                        UiMode.DRIVING_DO -> {
                            LiveDrivingScreen(ui, runtime)
                            if (debug) DevOverlay(ui, runtime)
                            return@BoxWithConstraints
                        }
                        UiMode.PARKED -> Unit
                    }
                    val c = ceremony
                    // 체험 모드: the banner gets its own strip on top, so it never covers a screen's close button
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(top = if (ui.demo != null) 64.dp else 0.dp)) {
                    when {
                        !settings.onboarded -> OnboardingScreen(requestPermissions) { r -> runtime.finishOnboarding(r) }
                        c != null -> {
                            BackHandler { runtime.ceremony.close() }
                            CeremonyScreen(c, runtime.ceremony, onComplete = { tripId ->
                                runtime.ceremony.finish(); stack.retainAll { it == Screen.Home }; go(Screen.CardDetail(tripId))
                            })
                        }
                        else -> {
                            BackHandler { back() }
                            when (val s = stack.last()) {
                                Screen.Home -> if (ui.trip != null && liveDismissed.value != ui.trip?.id) {
                                    LiveParkedScreen(ui, runtime, onHome = { liveDismissed.value = ui.trip?.id }, onInvite = { go(Screen.Invite) })
                                } else HomeScreen(ui, runtime, onNavigate = ::go, onOpenLive = { liveDismissed.value = null }, onAllowLocation = openPermissionSettings)
                                Screen.Invite -> InviteScreen(ui, onBack = ::back)
                                Screen.Archive -> ArchiveScreen(ui.cards, onOpen = { go(Screen.CardDetail(it)) }, onBack = ::back, onYearRecap = { go(Screen.YearRecap()) })
                                Screen.Dex -> DexScreen(runtime, ui, onBack = ::back)
                                Screen.Settings -> SettingsScreen(settings, runtime, ui.places, onBack = ::back)
                                is Screen.CardDetail -> CardScreen(s.tripId, ui, runtime, onBack = ::back)
                                is Screen.YearRecap -> ai.carbuddy.ui.screens.YearRecapRoute(s.year, ui, runtime.pipeline.config.yearRecapMinTrips, onBack = ::back)
                            }
                        }
                    }
                    }
                    // 중간 상영: asked, never auto-played (11/11 결정)
                    ui.halftimeOffer?.takeIf { c == null && settings.onboarded }?.let { offer ->
                        HalftimeOfferCard(ui.buddy, { runtime.openHalftime(offer.tripId) }, runtime::dismissHalftimeOffer)
                    }
                    if (debug) DevOverlay(ui, runtime)
                    ui.demo?.let { DemoBanner(it) { runtime.endDemo() } }   // 체험 모드: always visible on the parked screens
                }
            }
        }
    }
}


/** 중간 상영 offer (NEXT_DESIGN A): the buddy asks; [보기] plays it, [나중에] closes the card. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.HalftimeOfferCard(buddy: ai.carbuddy.data.BuddyEntity, onView: () -> Unit, onLater: () -> Unit) {
    val c = Cb.colors
    val ask = @Composable {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ai.carbuddy.ui.components.BuddyAvatar(72.dp, item = buddy.equipped, stage = buddy.stage)
            androidx.compose.foundation.layout.Spacer(Modifier.width(20.dp))
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(t("halftime_noti_title"), style = Cb.type.title, color = c.textPrimary)
                androidx.compose.material3.Text(t("halftime_noti_body"), style = Cb.type.body, color = c.textSecondary)
            }
        }
    }
    val later = @Composable { m: Modifier -> ai.carbuddy.ui.components.CbButton(t("halftime_offer_later"), onLater, m, kind = ai.carbuddy.ui.components.ButtonKind.SECONDARY) }
    val view = @Composable { m: Modifier -> ai.carbuddy.ui.components.CbButton(t("halftime_offer_view"), onView, m) }
    val card = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(24.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(c.bgSurface2)
    if (Cb.landscape) {
        // landscape (two windows): one row, buddy and question left, buttons right
        androidx.compose.foundation.layout.Row(
            card.width(860.dp).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { ask() }
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
            later(Modifier.width(140.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            view(Modifier.width(140.dp))
        }
    } else {
        // portrait (one window): question on top, the two buttons share the row below
        androidx.compose.foundation.layout.Column(
            card.fillMaxWidth().padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            ask()
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                later(Modifier.weight(1f)); view(Modifier.weight(1f))
            }
        }
    }
}

/** 체험 모드 banner: always visible while a demo runs or its card is open (nothing is being recorded). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DemoBanner(status: ai.carbuddy.trip.DemoStatus, onEnd: () -> Unit) {
    val c = ai.carbuddy.ui.theme.Cb.colors
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)).background(c.amber)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(t("demo_banner"), style = ai.carbuddy.ui.theme.Cb.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.onAccent)
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
        ai.carbuddy.ui.components.CbButton(t("demo_end"), onEnd, kind = ai.carbuddy.ui.components.ButtonKind.SECONDARY)
    }
}
