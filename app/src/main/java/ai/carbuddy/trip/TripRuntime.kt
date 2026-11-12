package ai.carbuddy.trip

import ai.carbuddy.card.Card
import ai.carbuddy.card.RouteShape
import ai.carbuddy.ceremony.AmbientController
import ai.carbuddy.ceremony.CeremonyController
import ai.carbuddy.ceremony.CeremonyHost
import ai.carbuddy.ceremony.NotifyKind
import ai.carbuddy.ceremony.VehicleControlPort
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.TripState
import ai.carbuddy.core.time.AppClock
import ai.carbuddy.data.BuddyEntity
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.data.PlaceEntity
import ai.carbuddy.data.SettingsEntity
import ai.carbuddy.data.TripEntity
import ai.carbuddy.place.WeeklyDigest
import ai.carbuddy.place.WeeklyDigestBuilder
import ai.carbuddy.route.RouteMark
import ai.carbuddy.route.RouteShaper
import ai.carbuddy.signal.BootReason
import ai.carbuddy.signal.EventLine
import ai.carbuddy.signal.Gear
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.PowerBoot
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.SignalHub
import ai.carbuddy.signal.SignalJson
import ai.carbuddy.signal.Tick
import ai.carbuddy.signal.adapter.SignalAdapter
import ai.carbuddy.episode.CapabilityRegistry
import ai.carbuddy.signal.adapter.replay.ReplayAdapter
import ai.carbuddy.story.CopyBook
import ai.carbuddy.ui.Notifier
import ai.carbuddy.voice.SafetyGate
import ai.carbuddy.voice.SpeakPurpose
import ai.carbuddy.voice.TtsPort
import ai.carbuddy.voice.UiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class HalftimeOffer(val tripId: String, val remainSec: Int?)

data class CardItem(val card: Card, val status: String, val trip: TripEntity?, val viewed: Boolean = false)

data class ReplayStatus(val running: Boolean = false, val name: String? = null, val speed: Float = 60f, val position: Long = 0, val useLogTime: Boolean = true)

/** V10 여정 중 data (UX_SPEC 3장). */
data class LiveInfo(
    val elapsedMin: Int = 0,
    val km: Int = 0,
    val restingMin: Int? = null,
    /** User-given name of the place we are stopped at (frequent places), else null. */
    val restingPlace: String? = null,
    val route: RouteShape? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val markedAt: Long? = null,
)

/** Everything the UI observes. Produced on the pipeline thread after each event. */
data class RuntimeUi(
    val tripState: TripState = TripState.IDLE,
    val trip: TripEntity? = null,
    val uiMode: UiMode = UiMode.PARKED,
    val canShowUi: Boolean = true,
    /** CarUxRestrictions max string length while restricted (V10 driving mode), null = unknown. */
    val maxDrivingChars: Int? = null,
    /** Signal families the live adapters could not read on this car (DevOverlay). */
    val unsupported: List<String> = emptyList(),
    /** Location (Android or Pleos fused) granted; home shows status card (a) when not, after onboarding. */
    val locationGranted: Boolean = true,
    /** 체험 모드 running (banner + no real recording), else null. */
    val demo: DemoStatus? = null,
    /** 확장 A item list with progress (V8 버디 탭). */
    val items: List<ai.carbuddy.story.BuddyEngine.ItemProgress> = emptyList(),
    val episodes: List<EpisodeEntity> = emptyList(),
    val now: Long = 0,
    val speedKmh: Float = 0f,
    val gear: String = "UNKNOWN",
    val belt: Boolean? = null,
    val remainSec: Int? = null,
    val destName: String? = null,
    val passengers: Int = 1,
    val recentEvents: List<String> = emptyList(),
    val captured: List<String> = emptyList(),
    val cards: List<CardItem> = emptyList(),
    /** 배웅 (NEXT_DESIGN 3.1): the buddy's one line at belt release, null when there is nothing to say. */
    val farewell: ai.carbuddy.story.FarewellLine? = null,
    /** 중간 상영 offer (NEXT_DESIGN A): asked on screen, played only on [보기]. */
    val halftimeOffer: HalftimeOffer? = null,
    val justCompletedTripId: String? = null,
    val replay: ReplayStatus = ReplayStatus(),
    val lastRecap: String? = null,
    val buddy: BuddyEntity = BuddyEntity(),
    val live: LiveInfo = LiveInfo(),
    val weekly: WeeklyDigest? = null,
    val places: List<PlaceEntity> = emptyList(),
)

/**
 * Process-wide runtime: the single consumer of [SignalHub] (one thread, events in order), the 1 s
 * clock tick, replay control, and exposure routing (UX_SPEC F3–F5, SPEC 10.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripRuntime(
    private val hub: SignalHub,
    private val clock: AppClock,
    val pipeline: TripPipeline,
    private val gate: SafetyGate,
    private val tts: TtsPort,
    private val copy: CopyBook,
    private val notifier: Notifier,
    private val ambient: AmbientController,
    private val vehicle: VehicleControlPort,
    private val adapters: List<SignalAdapter>,
    private val replaySource: (String) -> String,
    val capabilities: CapabilityRegistry = CapabilityRegistry(),
    private val metrics: ai.carbuddy.core.metrics.MetricsLog = ai.carbuddy.core.metrics.MetricsLog.None,
    private val sounds: ai.carbuddy.voice.SoundPort = ai.carbuddy.voice.SoundPort.None,
    private val demoTrips: DemoTrips = DemoTrips.InMemory(),
    private val replayGuard: ai.carbuddy.signal.adapter.ReplayGuard? = null,
) {
    private val pipelineThread = Dispatchers.Default.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + pipelineThread)
    private val _ui = MutableStateFlow(RuntimeUi())
    val ui: StateFlow<RuntimeUi> = _ui.asStateFlow()
    private val _settings = MutableStateFlow(SettingsEntity())
    val settings: StateFlow<SettingsEntity> = _settings.asStateFlow()

    @Volatile var uiVisible = false
    /** Set by BootReceiver before start (SPEC 5.2 진입점 ①); APP_START otherwise. */
    @Volatile var bootReason: BootReason = BootReason.APP_START
    private var started = false
    private var replayJob: Job? = null
    private var replay: ReplayAdapter? = null
    private var awaitingN4 = false
    private var n4TripId: String? = null
    private var lastLiveAt = 0L
    private val zone = ZoneId.of("Asia/Seoul")
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val shaper = RouteShaper(pipeline.config)
    private val weekly = WeeklyDigestBuilder(pipeline.store, zone)

    init { pipeline.settings = { _settings.value } }

    val ceremony = CeremonyController(scope, object : CeremonyHost {
        override fun loadCard(tripId: String) = pipeline.card(tripId)
        override fun hideEpisode(tripId: String, episodeId: String, hidden: Boolean) = pipeline.setEpisodeHidden(tripId, episodeId, hidden).also { refreshCards() }
        override fun saveProgress(tripId: String, step: Int?) = pipeline.setCeremonyStep(tripId, step).also { refreshCards() }
        override fun loopTrack(tripId: String, episodeId: String): List<Pair<Double, Double>> {
            val ep = pipeline.store.episodes(tripId).firstOrNull { it.id == episodeId } ?: return emptyList()
            return pipeline.store.trackPoints(tripId).filter { it.t in (ep.startT - 30_000)..(ep.endT + 30_000) }.map { it.lat to it.lng }
                .ifEmpty {
                    pipeline.store.signals(ep.startT - 30_000, ep.endT + 30_000).filter { it.type == "Location" }
                        .mapNotNull { (SignalJson.parseLine(it.payloadJson) as? EventLine)?.build(it.t) as? Location }
                        .map { it.lat to it.lng }
                }
        }
        override fun speakComment(text: String) {
            if (_settings.value.screeningVoiceOn && gate.canSpeak(clock.now(), SpeakPurpose.SCREENING)) tts.speak(text, "comment")
        }
        override fun viewed(tripId: String) { pipeline.budget.viewed(tripId, clock.now()); refreshCards() }
        override fun sound(sfx: ai.carbuddy.voice.Sfx) {
            if (_settings.value.screeningVoiceOn && gate.canSpeak(clock.now(), SpeakPurpose.SCREENING)) sounds.play(sfx)
        }
        override fun metric(tripId: String, name: String, props: Map<String, Any?>) {
            val arrived = pipeline.store.trip(tripId)?.let { it.arrivedAt ?: it.endedAt }
            mlog(name, props + ("hoursSinceArrival" to arrived?.let { (clock.now() - it) / 3_600_000.0 }))
        }
    }, ambient) { _settings.value.ambientOn }

    /** Idempotent: called by the foreground service and the activity. */
    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch {
            _settings.value = pipeline.store.settings()
            settingsLoaded = true
            demoTrips.all().takeIf { it.isNotEmpty() }?.let { ids -> ids.forEach { pipeline.store.deleteTrip(it) }; demoTrips.clear(); Timber.tag("CB/demo").i("removed %d leftover demo trips", ids.size) }
            ambient.recoverIfNeeded()
            pipeline.store.purgeSignalsBefore(clock.now() - SIGNAL_RETENTION_MS)
            refreshCards()
        }
        scope.launch { hub.events.collect { handle(it) } }
        scope.launch {
            delay(50)                          // let the collector subscribe first
            hub.emit(PowerBoot(clock.now(), bootReason))
            startAdapters()
        }
        scope.launch(Dispatchers.Default) {
            // the replay brings its own ticks on the virtual clock
            while (isActive) {
                delay(1_000)
                if (replayJob?.isActive != true) hub.tryEmit(Tick(clock.now()))
                // UX F2: buddy voice stops the moment navigation guidance starts
                if (tts.speaking && gate.navGuidanceActive()) { tts.stop(); Timber.tag("CB/voice").i("stopped for nav guidance") }
            }
        }
        Timber.tag("CB/runtime").i("started with %d adapters", adapters.size)
    }

    /** F0 done: required consents given, optional ones and the buddy name saved. */
    fun finishOnboarding(r: ai.carbuddy.ui.screens.OnboardingResult) = scope.launch {
        val s = pipeline.store.settings().copy(onboarded = true, recapOn = r.recapOn, ambientOn = r.ambientOn, syncOn = r.syncOn)
        pipeline.store.saveSettings(s); _settings.value = s
        pipeline.store.saveBuddy(pipeline.store.buddy().copy(name = r.buddyName))
        refreshCards()
    }

    /** V8: wear one owned item (or none). The dex screen only exists while parked. */
    fun equipItem(item: String?) = scope.launch { pipeline.buddy.equip(item); refreshCards() }

    /** MainActivity reports the location grant on every resume and after permission results. */
    fun setLocationGranted(granted: Boolean) { _ui.update { it.copy(locationGranted = granted) } }

    /** Dev panel: wipe every table (cards, trips, budget, places, buddy) for repeatable video takes. */
    fun resetAllData(thenRestart: () -> Unit) = scope.launch {
        stopReplay()
        ambient.restore()
        pipeline.store.wipeAll()
        Timber.tag("CB/runtime").i("all data reset")
        withContext(Dispatchers.Main) { thenRestart() }   // in-memory state goes with the process
    }

    /** UX 9장 events — never for 체험 trips. */
    private fun mlog(name: String, props: Map<String, Any?> = emptyMap()) { if (!pipeline.demoMode) metrics.log(name, props) }

    // ------------------------------------------------------------------ 체험 모드

    /**
     * 체험 모드: plays a scenario log through the real pipeline so a tester sees departure, episodes,
     * arrival and the screening on a parked car. Nothing is kept: the trips are deleted on [endDemo]
     * (or on the next start), the buddy, places, notification budget and metrics are untouched.
     * Real motion (gear D/R or > 5 km/h) stops it at once. @return false when it cannot start now.
     */
    fun startDemo(scenario: String, speed: Float = DEMO_SPEED): Boolean {
        val c = pipeline.ctx
        if (_ui.value.trip != null || c.isMoving() || (c.motionKnown && !c.isParked(clock.now()))) return false
        pipeline.demoMode = true
        replayGuard?.onRealMotion = { endDemo(stoppedByMotion = true) }
        ceremony.close()
        farewellInput = null
        _ui.update { it.copy(demo = DemoStatus(scenario), farewell = null, justCompletedTripId = null) }
        startReplay(scenario, speed, useLogTime = true)
        return true
    }

    /** Ends the 체험: stops the replay, deletes its trips and cards, gives the car its real state back. */
    fun endDemo(stoppedByMotion: Boolean = false) = scope.launch {
        if (!pipeline.demoMode && demoTrips.all().isEmpty()) { _ui.update { it.copy(demo = null) }; return@launch }
        replayGuard?.onRealMotion = null
        stopReplay()
        ceremony.close()
        pipeline.forgetActiveTrip()
        demoTrips.all().forEach { pipeline.store.deleteTrip(it) }
        demoTrips.clear()
        pipeline.demoMode = false
        clock.override = null; replay = null; capabilities.replayActive = false
        pipeline.ctx.resetMotion()
        replayGuard?.resendReal()
        refreshCards()
        _ui.update {
            it.copy(demo = null, trip = null, passengers = 1, tripState = TripState.IDLE, live = LiveInfo(),
                farewell = null, justCompletedTripId = null, captured = emptyList(), lastRecap = null)
        }
        Timber.tag("CB/demo").i("demo ended%s", if (stoppedByMotion) " (the car moved)" else "")
    }

    private fun startAdapters() =
        adapters.forEach { a -> runCatching { a.start() }.onFailure { Timber.tag("CB/signal").w(it, "adapter %s", a.name) } }

    /** After a runtime permission grant (CAR_SPEED, location): adapters that were waiting register now. */
    fun refreshAdapters() { if (started) scope.launch(Dispatchers.Main) { startAdapters() } }

    @Volatile private var settingsLoaded = false

    private fun handle(e: SignalEvent) {
        // UX_SPEC 8장: nothing is recorded before the F0 consent (replays are dev tools, always allowed)
        if (settingsLoaded && !_settings.value.onboarded && replayJob?.isActive != true) return
        val out = try { pipeline.process(e) } catch (t: Exception) {
            Timber.tag("CB/runtime").e(t, "pipeline failed on %s", e); return
        }
        val now = e.t
        val canShow = gate.canShowUi(now)
        var refresh = false
        for (o in out) when (o) {
            is PipelineEvent.StateChanged -> {
                if (pipeline.demoMode && o.transition.from == TripState.IDLE) demoTrips.add(o.transition.tripId)
                if (o.transition.from == TripState.IDLE && farewellInput != null) { farewellInput = null; _ui.update { it.copy(farewell = null) } }
                if (o.transition.to == TripState.DRIVING || o.transition.to == TripState.ARRIVING) { ceremony.interrupt(); withdrawHalftime() }
                if (o.transition.to == TripState.PAUSED || o.transition.to == TripState.PARKED_AT_DEST) lastLiveAt = 0
                refresh = refresh || o.transition.from == TripState.IDLE
            }
            is PipelineEvent.EpisodeCaptured -> if (o.isNew) _ui.update { it.copy(captured = (listOf("${time(o.episode.startT)} ${copy.get("name_${o.episode.type}")}") + it.captured).take(8)) }
            is PipelineEvent.RecapSpoken -> _ui.update { it.copy(lastRecap = o.text) }
            is PipelineEvent.Arrived -> { refresh = true; onArrived(o.card, o.trip, canShow, now) }
            is PipelineEvent.Halftime -> onHalftime(o, canShow)
            is PipelineEvent.Ended -> { refresh = true; onEnded(o) }
            is PipelineEvent.Booted -> {
                awaitingN4 = true
                if (pipeline.trips.trip == null && pipeline.ctx.gear == GearPos.P) { awaitingN4 = false; offerNextRide(now) }
                _settings.value = pipeline.store.settings()   // record-off may have been reset
                _ui.update { it.copy(weekly = weekly.build(now)) }
                refresh = true
            }
        }
        // N4: first parked moment after a boot; Gear D withdraws it (SPEC 10.2)
        if (e is Gear) {
            if (e.gear == GearPos.P && awaitingN4 && pipeline.trips.trip == null) { awaitingN4 = false; offerNextRide(now) }
            if (e.gear == GearPos.D) {
                awaitingN4 = false
                n4TripId?.let { notifier.cancelNext(); pipeline.budget.ignored(it); n4TripId = null }
                withdrawHalftime()
            }
        }
        if (!canShow) ceremony.interrupt()
        if (refresh) refreshCards()
        val ctx = pipeline.ctx
        val trip = pipeline.trips.trip
        if (trip != null && (now - lastLiveAt > LIVE_REFRESH_MS || refresh)) { lastLiveAt = now; refreshLive(trip) }
        _ui.update { s ->
            s.copy(
                tripState = pipeline.trips.state, trip = trip, canShowUi = canShow,
                uiMode = gate.uiMode(now, _settings.value.doApproved),
                maxDrivingChars = gate.maxDrivingChars,
                unsupported = capabilities.unsupportedLive.map { it.name }.sorted(),
                episodes = pipeline.episodes.current,
                now = ctx.now, speedKmh = ctx.speedKmh, gear = ctx.gear.name, belt = ctx.driverBelt, remainSec = ctx.remainSec,
                destName = ctx.dest?.name, passengers = ctx.passengerCount(),
                recentEvents = if (e is Tick) s.recentEvents else (listOf("${time(e.t)} ${describe(e)}") + s.recentEvents).take(12),
                replay = s.replay.copy(position = replay?.position ?: 0),
                live = if (trip == null) LiveInfo() else s.live.copy(
                    elapsedMin = ((now - trip.startedAt) / 60_000).toInt(), km = trip.distanceKm.toInt(),
                    restingMin = trip.pausedAt?.takeIf { trip.state == TripState.PAUSED.name }?.let { ((now - it) / 60_000).toInt() },
                    restingPlace = if (trip.state == TripState.PAUSED.name) ctx.lastLocation?.let { pipeline.places.nameAt(it.lat, it.lng) } else null,
                    episodes = pipeline.episodes.current.filterNot { it.hidden }.sortedBy { it.startT },
                ),
            )
        }
    }

    /** V10 '지금까지의 길': not trimmed (car screen only, UX V10). */
    private fun refreshLive(trip: TripEntity) {
        val marks = pipeline.episodes.current.filterNot { it.hidden }.map {
            RouteMark(it.id, EpisodeType.valueOf(it.type), it.startT, it.endT, copy.get("name_${it.type}"), 1.0)
        }
        val shape = runCatching {
            shaper.shape(pipeline.store.trackPoints(trip.id), marks, pipeline.store.gaps(trip.id), trim = false, minKm = 0.2, insets = false)
        }.getOrNull()
        _ui.update { it.copy(live = it.live.copy(route = shape)) }
    }

    private fun onArrived(card: Card, trip: TripEntity, canShow: Boolean, now: Long) {
        if (pipeline.demoMode) { if (canShow) ceremony.start(card.tripId, entry = "demo"); return }
        val eligible = pipeline.budget.n1Eligible(trip, card, now) ||
            (_settings.value.shortTripScreening && card.visibleEpisodes.isNotEmpty() && !trip.routine)
        if (!eligible) return                                   // quiet card (routine / short)
        pipeline.budget.record(NotifyKind.N1, card.cardId, now)
        if (uiVisible && canShow) ceremony.start(card.tripId, entry = "auto")
        else { notifier.arrival(card.tripId, card.visibleEpisodes.size); mlog("notify_shown", mapOf("kind" to "N1")) }
    }

    /**
     * 중간 상영 (NEXT_DESIGN A, 11/11 결정): never plays by itself. On screen → an offer card with
     * [보기] [나중에]; off screen → notification N5. Both are withdrawn at gear D.
     */
    private var halftimeNotified: String? = null

    private fun onHalftime(o: PipelineEvent.Halftime, canShow: Boolean) {
        if (canShow && (uiVisible || pipeline.demoMode)) {
            _ui.update { it.copy(halftimeOffer = HalftimeOffer(o.tripId, o.remainSec)) }
            mlog("notify_shown", mapOf("kind" to "N5", "where" to "screen"))
            return
        }
        if (pipeline.demoMode) return
        notifier.halftime(o.tripId)
        halftimeNotified = o.tripId
        mlog("notify_shown", mapOf("kind" to "N5"))
    }

    /** [나중에] on the offer card: nothing else happens; the [지금까지 보기] button stays on the parked screen. */
    fun dismissHalftimeOffer() {
        if (_ui.value.halftimeOffer != null) mlog("notify_action", mapOf("kind" to "N5", "action" to "later"))
        _ui.update { it.copy(halftimeOffer = null) }
    }

    private fun withdrawHalftime() {
        halftimeNotified?.let { notifier.cancelHalftime(); mlog("notify_action", mapOf("kind" to "N5", "action" to "auto_cancel")); halftimeNotified = null }
        if (_ui.value.halftimeOffer != null) _ui.update { it.copy(halftimeOffer = null) }
    }

    /**
     * Checked again when the launch actually runs: under a fast replay the pipeline thread can be busy
     * long after the car left the stop, and a late halftime must not start on the road or over the
     * arrival screening (seen on the emulator at 300x, 11/11).
     */
    private fun startHalftime(tripId: String, remainSec: Int?, entry: String) = scope.launch {
        val why = when {
            pipeline.trips.trip?.id != tripId || pipeline.trips.state != TripState.PAUSED -> "no longer resting at that stop"
            ceremony.state.value != null -> "a screening is already on screen"
            else -> null
        }
        if (why != null) { Timber.tag("CB/ceremony").i("halftime skipped: %s", why); return@launch }
        ceremony.start(tripId, entry = entry, halftime = true, maxSlides = pipeline.config.halftimeMaxSlides, remainSec = remainSec)
    }

    /** [지금까지 보기] on the parked live screen, or the N5 notification. Parked only. */
    fun openHalftime(tripId: String, fromNotification: Boolean = false) = scope.launch {
        notifier.cancelHalftime(); halftimeNotified = null
        _ui.update { it.copy(halftimeOffer = null) }
        if (!gate.canShowUi(clock.now())) return@launch
        if (fromNotification) mlog("notify_action", mapOf("kind" to "N5", "action" to "view"))
        val remain = pipeline.trips.trip?.takeIf { it.id == tripId }?.pauseRemainSec ?: pipeline.ctx.remainSec
        startHalftime(tripId, remain, if (fromNotification) "N5" else "live")
    }

    private fun offerNextRide(now: Long) {
        val c = pipeline.budget.n4Candidate(now) ?: return
        if (uiVisible) return                                    // home shows '이어 보기' instead
        val card = runCatching { Card.fromJson(c.json) }.getOrNull() ?: return
        val trip = pipeline.store.trip(c.tripId)
        val days = trip?.endedAt?.let { ChronoUnit.DAYS.between(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) } ?: 2
        val whenWord = copy.get(when (days) { 0L -> "noti_next_when_today"; 1L -> "noti_next_when_yesterday"; else -> "noti_next_when_before" })
        val place = card.route.toCity.substringAfterLast(' ').removeSuffix("시").removeSuffix("군").removeSuffix("구")
        notifier.nextRide(c.tripId, whenWord, place)
        pipeline.budget.record(NotifyKind.N4, c.id, now)
        mlog("notify_shown", mapOf("kind" to "N4"))
        n4TripId = c.tripId
    }

    private fun onEnded(o: PipelineEvent.Ended) {
        ceremony.refreshCard()
        o.card?.let { c ->
            mlog("trip_ended", mapOf("episodes" to c.visibleEpisodes.size, "hasDest" to (c.receipt.mode != "NO_DEST"),
                "participants" to c.passengers, "endReason" to o.reason?.name, "routine" to c.routine))
        }
        if (ceremony.state.value == null && o.card != null) _ui.update { it.copy(justCompletedTripId = o.tripId) }
        if (o.card != null && !pipeline.demoMode && o.reason != ai.carbuddy.core.model.EndReason.ARRIVED && o.reason != ai.carbuddy.core.model.EndReason.USER) {
            // completed without the driver seeing it (power off / no restart in time): N4 at the next parked moment
            if (pipeline.trips.trip == null && pipeline.ctx.gear == GearPos.P) offerNextRide(clock.now()) else awaitingN4 = true
        }
        val routine = o.card?.routine ?: (pipeline.store.trip(o.tripId)?.routine == true)
        farewellInput = FarewellInput(o.card, o.exit, routine)
        val line = farewells.compose(o.card, o.exit, routine)
        _ui.update { it.copy(farewell = line) }
        line?.speakText?.let { s -> if (gate.canSpeak(clock.now(), SpeakPurpose.EXIT_CHECK)) tts.speak(s, "exit") }
    }

    /** What the last farewell was built from, so closing one window can show the next thing to mind. */
    private data class FarewellInput(val card: Card?, val exit: ai.carbuddy.card.Back?, val routine: Boolean)
    private var farewellInput: FarewellInput? = null
    private val farewells = ai.carbuddy.story.FarewellComposer(copy)

    fun refreshCards() {
        val items = pipeline.store.cards().mapNotNull { c ->
            runCatching { CardItem(Card.fromJson(c.json), c.status, pipeline.store.trip(c.tripId), c.viewedAt != null) }.getOrNull()
        }
        _ui.update { it.copy(cards = items, buddy = pipeline.store.buddy(), places = pipeline.store.places(), items = pipeline.buddy.itemProgress()) }
    }

    // ------------------------------------------------------------------ UI actions (pipeline thread)

    /** @param fromNotification opened from N1/N4 (MainActivity intent) — logged as notify_action view. */
    fun openScreening(tripId: String, summary: Boolean = false, fromNotification: Boolean = false, entry: String = "app") = scope.launch {
        val step = if (summary) 0 else pipeline.store.trip(tripId)?.ceremonyStep?.takeIf { it >= 0 } ?: 0
        notifier.cancelArrival(); notifier.cancelNext()
        val kind = if (n4TripId == tripId) "N4" else "N1"
        if (n4TripId == tripId) n4TripId = null
        if (fromNotification) mlog("notify_action", mapOf("kind" to kind, "action" to "view"))
        ceremony.start(tripId, step, summary, entry = if (fromNotification) kind else entry)
    }

    fun ignored(tripId: String) = scope.launch {
        mlog("notify_action", mapOf("kind" to if (n4TripId == tripId) "N4" else "N1", "action" to "later"))
        pipeline.budget.ignored(tripId); if (n4TripId == tripId) n4TripId = null
    }

    fun hideEpisode(tripId: String, episodeId: String, hidden: Boolean) = scope.launch {
        if (hidden) pipeline.store.episodes(tripId).firstOrNull { it.id == episodeId }?.let { mlog("episode_hidden", mapOf("type" to it.type)) }
        pipeline.setEpisodeHidden(tripId, episodeId, hidden); refreshCards()
    }

    /** Dev: the last trip as a replay log in the app's external files (adb pull → docs/replay). */
    fun exportLastTrip(done: (String?) -> Unit) = scope.launch {
        val trip = pipeline.store.trips().filter { !it.discarded }.maxByOrNull { it.startedAt }
        val text = trip?.let { ai.carbuddy.signal.adapter.replay.ReplayExporter.export(pipeline.store, it.id) }
        val path = text?.let { t -> exportDir?.let { d -> java.io.File(d, "trip-${trip.id.take(8)}.jsonl").apply { parentFile?.mkdirs(); writeText(t) }.absolutePath } }
        withContext(Dispatchers.Main) { done(path) }
    }

    /** Set by the DI module: where dev exports go. */
    var exportDir: java.io.File? = null

    fun chooseTitle(tripId: String, title: String) = scope.launch {
        pipeline.updateOverrides(tripId) { it.copy(title = title) }; refreshCards()
    }

    fun closeWindow(seat: SeatPos) = scope.launch {
        if (!gate.canShowUi(clock.now()) || !vehicle.closeWindow(seat)) return@launch
        // the window is minded: drop it and show what is left (next window, trunk, lights, or just the story)
        val f = farewellInput?.let { it.copy(exit = it.exit?.copy(windowsOpen = it.exit.windowsOpen - seat.name)) }
        farewellInput = f
        _ui.update { it.copy(farewell = f?.let { i -> farewells.compose(i.card, i.exit, i.routine) }) }
    }

    /** "지금 기억해" (V10). */
    fun markMoment() = scope.launch {
        val now = clock.now()
        if (pipeline.markMoment(now)) {
            _ui.update { it.copy(live = it.live.copy(markedAt = now)) }
            if (gate.canSpeak(now, SpeakPurpose.SCREENING)) sounds.play(ai.carbuddy.voice.Sfx.MARK)   // 1 s confirmation (UX)
        }
    }

    /** "이 여정 기록 끄기" (V10), confirmed by the user. */
    fun forgetActiveTrip() = scope.launch {
        pipeline.forgetActiveTrip(); refreshCards()
        _ui.update { it.copy(trip = null, tripState = TripState.IDLE, live = LiveInfo()) }
    }

    /** V10 [기록 멈추기]: no card for this trip, and no recording until the next ignition. */
    fun stopRecording() = scope.launch {
        pipeline.stopRecording()
        val s = pipeline.store.settings(); _settings.value = s
        notifier.updateService(false)
        refreshCards()
        _ui.update { it.copy(trip = null, tripState = TripState.IDLE, live = LiveInfo()) }
    }

    /**
     * V10 [여기서 끝내기]: end the trip now and make its card from what we have, then show the
     * screening right away (the user asked, parked; not counted as an N1 notification).
     */
    fun endTripNow() = scope.launch {
        val out = pipeline.endActiveTrip(clock.now())
        _ui.update { it.copy(trip = null, tripState = TripState.IDLE, live = LiveInfo()) }
        refreshCards()
        out.filterIsInstance<PipelineEvent.Ended>().forEach { ended ->
            onEnded(ended)
            val card = ended.card ?: return@forEach
            if (gate.canShowUi(clock.now())) {
                ceremony.start(card.tripId, entry = "end_now")
                _ui.update { it.copy(justCompletedTripId = null) }
            }
        }
    }

    /** "오늘은 기록 안 함" (V1, parked only; cleared on the next boot). */
    fun setRecordOff(off: Boolean) = updateSettings { it.copy(recordOff = off) }

    fun dismissFarewell() { farewellInput = null; _ui.update { it.copy(farewell = null) } }
    fun dismissCompleted() = _ui.update { it.copy(justCompletedTripId = null) }
    fun dismissWeekly() = _ui.update { it.copy(weekly = null) }

    fun updateSettings(f: (SettingsEntity) -> SettingsEntity) = scope.launch {
        val s = f(pipeline.store.settings()); pipeline.store.saveSettings(s); _settings.value = s
        notifier.updateService(!s.recordOff && !s.recordPaused)
    }

    fun renamePlace(id: Long, name: String?) = scope.launch { pipeline.places.rename(id, name); refreshCards() }
    fun deletePlaces() = scope.launch { pipeline.store.deletePlaces(); refreshCards() }
    fun deleteRouteRecords() = scope.launch { pipeline.store.deleteAllTrackPoints() }
    fun deleteAllCards() = scope.launch { pipeline.store.deleteAllCards(); refreshCards() }

    // ------------------------------------------------------------------ replay (dev)

    fun startReplay(name: String, speed: Float, useLogTime: Boolean) {
        stopReplay()
        val adapter = ReplayAdapter(hub).also { it.speed = speed }
        replay = adapter
        capabilities.replayActive = true          // the log brings every signal family itself
        clock.override = adapter.clock
        _ui.update { it.copy(replay = ReplayStatus(true, name, speed, 0, useLogTime), captured = emptyList(), lastRecap = null) }
        replayJob = scope.launch(Dispatchers.Default) {
            runCatching {
                val lines = SignalJson.parseLog(replaySource(name))
                adapter.play(lines, fallbackStart = System.currentTimeMillis(), useLogStartTime = useLogTime)
            }.onFailure { Timber.tag("CB/replay").e(it, "replay failed") }
            _ui.update { it.copy(replay = it.replay.copy(running = false)) }
        }
    }

    fun setReplaySpeed(speed: Float) { replay?.speed = speed; _ui.update { it.copy(replay = it.replay.copy(speed = speed)) } }

    fun stopReplay() {
        replayJob?.cancel(); replayJob = null
        _ui.update { it.copy(replay = it.replay.copy(running = false)) }
    }

    /** Dev: back to wall-clock time after a replay. */
    fun useWallClock() { stopReplay(); clock.override = null; replay = null; capabilities.replayActive = false }

    private fun time(t: Long) = Instant.ofEpochMilli(t).atZone(zone).format(hms)

    private fun describe(e: SignalEvent): String {
        val json = SignalJson.encode(e, e.t)
        return json.removePrefix("{\"dt\":0,\"type\":").replace("\"", "").removeSuffix("}").take(70)
    }

    companion object {
        /** 체험 speed: scenario A (4 h) in about 2 minutes. */
        const val DEMO_SPEED = 120f
        const val SIGNAL_RETENTION_MS = 7L * 24 * 3600 * 1000
        const val LIVE_REFRESH_MS = 60_000L
    }
}
