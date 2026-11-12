package ai.carbuddy

import ai.carbuddy.core.geo.OfflineCityResolver
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.TripState
import ai.carbuddy.core.model.WiperMode
import ai.carbuddy.data.InMemoryTripStore
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.episode.EpisodeCandidate
import ai.carbuddy.episode.EpisodeDetector
import ai.carbuddy.signal.DestinationChanged
import ai.carbuddy.signal.DriverBelt
import ai.carbuddy.signal.Gear
import ai.carbuddy.signal.HvacTarget
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.ReRouteEnded
import ai.carbuddy.signal.RemainTime
import ai.carbuddy.signal.RouteStarted
import ai.carbuddy.signal.SeatOccupancy
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.SignalJson
import ai.carbuddy.signal.Speed
import ai.carbuddy.signal.Tick
import ai.carbuddy.signal.WindowPos
import ai.carbuddy.signal.Wiper
import ai.carbuddy.signal.adapter.replay.ReplayAdapter
import ai.carbuddy.story.XmlCopyBook
import ai.carbuddy.trip.PipelineEvent
import ai.carbuddy.trip.TripContext
import ai.carbuddy.trip.TripPipeline
import ai.carbuddy.voice.ArrivalRecap
import ai.carbuddy.voice.RecordingTtsPort
import ai.carbuddy.voice.SafetyGate
import java.io.File
import java.time.OffsetDateTime

object TestSupport {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "docs/replay").isDirectory }

    fun replay(name: String): List<SignalEvent> =
        ReplayAdapter.timeline(SignalJson.parseLog(File(root, "docs/replay/$name").readText()), fallbackStart = 0)

    fun copy() = XmlCopyBook(File(root, "app/src/main/res/values/strings_copy.xml").inputStream())

    val cities = OfflineCityResolver()

    fun pipeline(store: InMemoryTripStore = InMemoryTripStore(), config: DetectorConfig = DetectorConfig(), tts: RecordingTtsPort? = null): TripPipeline {
        var n = 0
        val p = TripPipeline(store, config, copy(), cities, newId = { "trip-${++n}-${store.trips().size}" })
        if (tts != null) p.recap = ArrivalRecap(SafetyGate({ p.ctx }), tts, copy(), config)
        return p
    }

    fun runAll(p: TripPipeline, events: List<SignalEvent>): List<PipelineEvent> = events.flatMap { p.process(it) }

    /** Result of a run where PowerCycle REBOOT/LATE_START markers really restarted the process. */
    class Run(val store: InMemoryTripStore, val out: List<PipelineEvent>, val pipeline: TripPipeline, val restarts: Int)

    /**
     * SPEC 4.3 PowerCycle: REBOOT / LATE_START throw the pipeline (engine, detectors, context) away
     * and build a new one from the same store; SUSPEND keeps the process (the gap → SignalResumed).
     */
    fun runWithPowerCycles(
        events: List<SignalEvent>,
        store: InMemoryTripStore = InMemoryTripStore(),
        config: DetectorConfig = DetectorConfig(),
        tts: RecordingTtsPort? = null,
        settings: (() -> ai.carbuddy.data.SettingsEntity)? = null,
    ): Run {
        var p = pipeline(store, config, tts).also { pp -> settings?.let { pp.settings = it } }
        var restarts = 0
        val out = mutableListOf<PipelineEvent>()
        for (e in events) {
            if (e is ai.carbuddy.signal.PowerCycle && e.mode != ai.carbuddy.signal.PowerCycleMode.SUSPEND) {
                p = pipeline(store, config, tts).also { pp -> settings?.let { pp.settings = it } }
                restarts++
                continue
            }
            out += p.process(e)
        }
        return Run(store, out, p, restarts)
    }

    fun transitions(out: List<PipelineEvent>) = out.filterIsInstance<PipelineEvent.StateChanged>().map { it.transition }

    /** Latest value of every vehicle-state signal before [cut], re-stamped at [t] (adapter registration after boot). */
    fun adapterSnapshot(events: List<SignalEvent>, cut: Long, t: Long): List<SignalEvent> =
        events.filter { it.t < cut && it !is Tick && it !is Location && SignalJson.typeName(it) in STATE_TYPES }
            .groupBy { SignalJson.typeName(it) }.values.map { it.last() }
            .map { e -> (SignalJson.parseLine(SignalJson.encode(e, e.t)) as ai.carbuddy.signal.EventLine).build(t) }

    private val STATE_TYPES = setOf("Speed", "Gear", "DriverBelt", "SeatOccupancy", "Wiper", "WindowPos", "LowBeam", "HighBeam",
        "HvacTarget", "TurnSignal", "FuelPercent", "TrunkOpen")

    fun kst(s: String): Long = OffsetDateTime.parse("${s}+09:00").toInstant().toEpochMilli()

    /** "2026-11-07T10:00:00" + minutes. */
    fun at(base: String, min: Double) = kst(base) + (min * 60_000).toLong()
}

/** Feeds events to one detector with a hand-driven context (trip DRIVING by default). */
class DetectorHarness(val detector: EpisodeDetector, val config: DetectorConfig = DetectorConfig(), start: Long = 0L) {
    val ctx = TripContext(config).apply { tripId = "t"; tripStartedAt = start; state = TripState.DRIVING }
    private val latest = linkedMapOf<String, EpisodeCandidate>()
    val emitted = mutableListOf<EpisodeCandidate>()

    fun feed(vararg events: SignalEvent) = events.forEach { e ->
        ctx.update(e)
        detector.onEvent(e, ctx).forEach { latest[it.key] = it; emitted += it }
    }

    fun feed(events: List<SignalEvent>) = feed(*events.toTypedArray())

    fun end() = detector.onTripEnd(ctx).forEach { latest[it.key] = it; emitted += it }

    /** Distinct episodes (latest version of each key). */
    val episodes: List<EpisodeCandidate> get() = latest.values.toList()

    /** Simulates a process kill: state saved, new detector restored from it. */
    fun restart(newDetector: EpisodeDetector): DetectorHarness {
        val json = detector.saveState()
        newDetector.reset()
        json?.let { newDetector.restoreState(it) }
        val h = DetectorHarness(newDetector, config)
        h.ctx.tripId = ctx.tripId; h.ctx.tripStartedAt = ctx.tripStartedAt; h.ctx.state = ctx.state
        latest.forEach { (k, v) -> h.seed(k, v) }
        return h
    }

    private fun seed(k: String, v: EpisodeCandidate) { latest[k] = v }
}

// ---- tiny event builders (t in minutes from 0)
fun m(min: Double) = (min * 60_000).toLong()
fun speed(min: Double, kmh: Float) = Speed(m(min), kmh)
fun gear(min: Double, g: GearPos) = Gear(m(min), g)
fun belt(min: Double, on: Boolean) = DriverBelt(m(min), on)
fun wiper(min: Double, w: WiperMode) = Wiper(m(min), w)
fun window(min: Double, seat: SeatPos, pct: Int) = WindowPos(m(min), mapOf(seat to pct))
fun hvac(min: Double, d: Float, p: Float) = HvacTarget(m(min), d, p)
fun seats(min: Double, vararg occupied: SeatPos) = SeatOccupancy(m(min), SeatPos.entries.associateWith { it in occupied })
fun tick(min: Double) = Tick(m(min))
fun reroute(min: Double) = ReRouteEnded(m(min))
fun remain(min: Double, sec: Int) = RemainTime(m(min), sec)
fun route(min: Double, name: String, lat: Double, lng: Double, etaMin: Double) = RouteStarted(m(min), name, lat, lng, m(etaMin))
fun destChanged(min: Double, name: String, lat: Double, lng: Double) = DestinationChanged(m(min), name, lat, lng)
fun loc(min: Double, lat: Double, lng: Double, acc: Float = 5f) = Location(m(min), lat, lng, acc, null)
fun ticks(fromMin: Double, toMin: Double, everySec: Int = 5): List<Tick> =
    generateSequence(m(fromMin)) { it + everySec * 1000L }.takeWhile { it <= m(toMin) }.map { Tick(it) }.toList()
