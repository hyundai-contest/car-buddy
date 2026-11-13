package ai.carbuddy.di

import ai.carbuddy.ceremony.AmbientController
import ai.carbuddy.ceremony.PleosAmbientController
import ai.carbuddy.ceremony.PleosVehicleControl
import ai.carbuddy.ceremony.VehicleControlPort
import ai.carbuddy.core.geo.CityResolver
import ai.carbuddy.core.geo.OfflineCityResolver
import ai.carbuddy.core.time.AppClock
import ai.carbuddy.data.AppDatabase
import ai.carbuddy.data.RoomTripStore
import ai.carbuddy.data.TripStore
import ai.carbuddy.episode.CapabilityRegistry
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.signal.SignalHub
import ai.carbuddy.signal.adapter.LiveEmitter
import ai.carbuddy.signal.adapter.SignalAdapters
import ai.carbuddy.signal.adapter.aaos.AaosSpeedAdapter
import ai.carbuddy.signal.adapter.aaos.AaosUxRestrictions
import ai.carbuddy.signal.adapter.aaos.CarConnection
import ai.carbuddy.signal.adapter.location.LocationAdapter
import ai.carbuddy.signal.adapter.navi.NaviHelperAdapter
import ai.carbuddy.signal.adapter.pleos.PleosVehicle
import ai.carbuddy.signal.adapter.pleos.PleosVehicleAdapter
import ai.carbuddy.story.CopyBook
import ai.carbuddy.story.ResCopyBook
import ai.carbuddy.trip.TripPipeline
import ai.carbuddy.trip.TripRuntime
import ai.carbuddy.ui.Notifier
import ai.carbuddy.voice.AndroidSpeakConditions
import ai.carbuddy.voice.AndroidTtsPort
import ai.carbuddy.voice.PleosTtsPort
import ai.carbuddy.voice.PrerollTtsPort
import ai.carbuddy.voice.ArrivalRecap
import ai.carbuddy.voice.SafetyGate
import ai.carbuddy.voice.SpeakPurpose
import ai.carbuddy.voice.TtsPort
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "carbuddy.db").addMigrations(AppDatabase.MIGRATION_2_3).fallbackToDestructiveMigration(true).build()

    @Provides @Singleton fun store(db: AppDatabase): TripStore = RoomTripStore(db.dao()) { db.clearAllTables() }
    @Provides @Singleton fun config(): DetectorConfig = DetectorConfig()
    @Provides @Singleton fun copy(@ApplicationContext c: Context): CopyBook = ResCopyBook(c)
    @Provides @Singleton fun cities(): CityResolver = OfflineCityResolver()
    @Provides @Singleton fun capabilities(): CapabilityRegistry = CapabilityRegistry()
    @Provides @Singleton fun tts(@ApplicationContext c: Context): TtsPort =
        PrerollTtsPort(PleosTtsPort(c) { AndroidTtsPort(c) })
    @Provides @Singleton fun notifier(@ApplicationContext c: Context, copy: CopyBook) = Notifier(c, copy)

    @Provides @Singleton fun pleosVehicle(@ApplicationContext c: Context) = PleosVehicle(c)
    @Provides @Singleton fun ambient(pleos: PleosVehicle, store: TripStore, caps: CapabilityRegistry): AmbientController = PleosAmbientController(pleos, store, caps)
    @Provides @Singleton fun vehicleControl(pleos: PleosVehicle): VehicleControlPort = PleosVehicleControl(pleos)
    @Provides @Singleton fun replayGuard() = ai.carbuddy.signal.adapter.ReplayGuard()
    @Provides @Singleton fun car(@ApplicationContext c: Context) = CarConnection(c)
    @Provides @Singleton fun uxRestrictions(car: CarConnection) = AaosUxRestrictions(car)
    @Provides @Singleton fun navi(@ApplicationContext c: Context, hub: SignalHub, clock: AppClock, caps: CapabilityRegistry, guard: ai.carbuddy.signal.adapter.ReplayGuard) =
        NaviHelperAdapter(c, LiveEmitter(hub, clock, { caps.replayActive }, guard), caps)

    @Provides @Singleton
    fun adapters(@ApplicationContext c: Context, config: DetectorConfig, pleos: PleosVehicle, hub: SignalHub, clock: AppClock, caps: CapabilityRegistry, car: CarConnection, ux: AaosUxRestrictions, navi: NaviHelperAdapter, guard: ai.carbuddy.signal.adapter.ReplayGuard): SignalAdapters {
        val out = LiveEmitter(hub, clock, { caps.replayActive }, guard)
        return SignalAdapters(listOf(PleosVehicleAdapter(pleos, out, caps), AaosSpeedAdapter(c, car, out, caps), LocationAdapter(c, out, caps, config.trip.recoverFixWaitSec * 1000L), navi, ux))
    }

    @Provides @Singleton
    fun pipeline(store: TripStore, config: DetectorConfig, copy: CopyBook, cities: CityResolver, caps: CapabilityRegistry) =
        TripPipeline(store, config, copy, cities, capabilities = caps)

    @Provides @Singleton
    fun runtime(
        @ApplicationContext c: Context, hub: SignalHub, clock: AppClock, pipeline: TripPipeline, copy: CopyBook,
        config: DetectorConfig, tts: TtsPort, notifier: Notifier, ambient: AmbientController,
        vehicle: VehicleControlPort, adapters: SignalAdapters, caps: CapabilityRegistry, ux: AaosUxRestrictions, navi: NaviHelperAdapter,
        guard: ai.carbuddy.signal.adapter.ReplayGuard,
    ): TripRuntime {
        lateinit var runtime: TripRuntime
        val gate = SafetyGate(
            ctx = { pipeline.ctx },
            conditions = AndroidSpeakConditions(c),
            settingOn = { purpose ->
                val s = runtime.settings.value
                when (purpose) { SpeakPurpose.ARRIVAL_RECAP -> s.recapOn; SpeakPurpose.SCREENING -> s.screeningVoiceOn; SpeakPurpose.EXIT_CHECK -> true }
            },
            ux = ux,
            demo = { pipeline.demoMode },
        )
        pipeline.recap = ArrivalRecap(gate, tts, copy, config, turns = navi)
        runtime = TripRuntime(
            hub, clock, pipeline, gate, tts, copy, notifier, ambient, vehicle, adapters.all,
            replaySource = { name -> c.assets.open(name).bufferedReader().use { it.readText() } },
            capabilities = caps,
            sounds = ai.carbuddy.voice.ToneSoundPort(),
            demoTrips = ai.carbuddy.trip.DemoTrips.Prefs(c.getSharedPreferences("carbuddy_demo", Context.MODE_PRIVATE)),
            replayGuard = guard,
            metrics = ai.carbuddy.core.metrics.FileMetricsLog(java.io.File(c.filesDir, "metrics/events.jsonl"), clock::now),
        )
        runtime.exportDir = c.getExternalFilesDir("exports")
        return runtime
    }
}
