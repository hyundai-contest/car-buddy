package ai.carbuddy.episode

import kotlinx.serialization.Serializable

/** Trip life-cycle numbers (SPEC 5장 "DetectorConfig.trip"). */
@Serializable
data class TripConfig(
    // start (SPEC 5.5)
    val drivingConfirmSec: Int = 3,          // Gear=D and Speed > drivingSpeedKmh for this long → DRIVING
    val drivingSpeedKmh: Float = 5f,
    val pendingRouteMin: Int = 10,           // a route set before departure is kept this long
    // parking / pauses (SPEC 5, 5.3, 5.6)
    val parkedSpeedZeroSec: Int = 10,        // gear unreadable: Speed=0 this long counts as P
    val idleBeltOnMin: Int = 30,             // P with the belt still on this long → PAUSED from its start
    val noDestPauseMin: Int = 30,            // no destination, or a stop that looks like the end (away / guidance over): final after this
    val nearDestPauseMin: Int = 10,          // stop within arriveNearKm of the destination, or at a frequent place: final after this
    val destPauseMaxMin: Int = 120,          // stop on the way with guidance still on (rest area, meal): final after this (MAX_GAP)
    val awayFromDestKm: Double = 2.0,        // stopped this much farther from the destination than the closest point of the last 30 min → heading away
    val arriveBeltUnknownSec: Int = 120,     // at the destination with no belt signal: arrival is final after this
    // arrival (SPEC 5.6)
    val arrivingRemainSec: Int = 180,        // RemainTime ≤ this → ARRIVING
    val arrivingExitRemainSec: Int = 300,    // back to DRIVING if remain grows past this
    val arriveRadiusM: Double = 300.0,       // "목적지 300m 이내"
    val arriveNearKm: Double = 1.0,          // navigation ended/cancelled and P within this of the destination
    val waypointRadiusM: Double = 300.0,     // a stop this close to a waypoint is a planned stop
    // recovery (SPEC 5.1–5.3)
    val recoverJumpM: Double = 300.0,        // first fix after recovery this far from the stop → we missed driving (trip_gap)
    val naviWaitSec: Int = 90,
    val recoverFixWaitSec: Int = 60,         // wait this long for the first location after recovery
    val signalGapMin: Int = 3,               // silence longer than this then signals again → SignalResumed
    val lastSignalFlushSec: Int = 10,        // last-known values are persisted at least this often
    val clockBackwardToleranceSec: Int = 5,
    // card
    val minTripKm: Double = 1.0,             // shorter → discarded (no card)
    val minTripMin: Int = 5,
)

/**
 * Every detection / trip-judgement number lives here (SPEC 1장 원칙 4, CLAUDE.md 규칙 5).
 * Defaults are the 기획안 v13 initial values; tune from real drive logs without code changes.
 */
@Serializable
data class DetectorConfig(
    val trip: TripConfig = TripConfig(),

    // ---- LOST_LOOP / PARKING_HUNT
    val loopWindowMin: Int = 10,
    val reRouteMin: Int = 2,
    val loopLookbackMin: Int = 15,
    val loopExcludeMin: Int = 2,             // also the cool-down between two counted revisits
    val loopRadiusM: Double = 50.0,
    val loopRevisitMin: Int = 2,
    val loopMinSpeedKmh: Float = 5f,         // ignore fixes while (nearly) standing still
    val loopMinAwayM: Double = 150.0,        // must have left the point by this much before a revisit counts
    val loopMergeGapMin: Int = 5,            // a loop re-occurring within this is the same loop
    val parkingHuntRadiusM: Double = 500.0,  // a loop this close to the destination is a parking hunt (SPEC 6.2)

    // ---- DETOUR
    val detourMinDistKm: Double = 2.0,
    val detourMinStayMin: Int = 10,
    val detourRemainIncreaseMin: Int = 3,    // stop classified off-route if ETA grew more than this

    // ---- SUDDEN_RAIN
    val rainRiseMin: Int = 10,
    val rainHighMin: Int = 3,
    val rainWindowOpenMin: Int = 20,
    val rainWindowOpenPct: Int = 20,

    // ---- TEMP_WAR
    val tempDiffC: Float = 3.0f,
    val tempMinDurMin: Int = 10,

    // ---- JAM_ENDURED
    val jamKmh: Float = 20f,
    val jamMinMin: Int = 20,
    val jamRecoverSec: Int = 30,             // brief recoveries up to this are ignored
    val jamLostFactor: Double = 1.0,         // receipt share of jam time (SPEC 8장)

    // ---- REST_STOP (upper bound is the trip's destPauseMaxMin, SPEC 6.2)
    val restAfterStartMin: Int = 30,
    val restMinDistKm: Double = 2.0,
    val restMinMin: Int = 5,
    val stopResolveTimeoutMin: Int = 3,      // wait this long for a RemainTime after resuming

    // ---- Exit check / parking memo / fuel (확장 B, SPEC 16장)
    val exitWindowOpenPct: Int = 5,
    val parkingWeakAccuracyM: Float = 30f,
    val parkingNoFixMin: Int = 2,
    val fuelLowPct: Int = 15,

    // ---- Receipt (SPEC 8장)
    val onTimeTolMin: Int = 5,
    val onTimeTolShortMin: Int = 3,          // trips planned under shortTripMin
    val shortTripMin: Int = 60,
    val tunnelMinSec: Int = 15,              // low beam on this long … (vehicle tally)
    val tunnelMaxMin: Int = 10,              // … but not longer than this = one tunnel

    // ---- PT detectors (기획안 v13 3장 표)
    val fullHouseSeats: Int = 4,             // FULL_HOUSE: this many occupied seats …
    val fullHouseMinSec: Int = 60,           // … for this long while on a trip
    val windRunPct: Int = 50,                // WIND_RUN: every window open at least this much …
    val windRunMin: Int = 3,                 // … for this long while moving
    val tunnelEpisodeCount: Int = 3,         // TUNNEL_PARADE: daytime tunnels (tunnelMinSec..tunnelMaxMin)
    val sunsetBearingDeg: Double = 30.0,     // SUNSET_DRIVE: heading within this of the sun …
    val sunsetMin: Int = 5,                  // … for this long
    val sunsetElevMinDeg: Double = -4.0,     // "sunset ±30 min" as a solar-elevation band in the west
    val sunsetElevMaxDeg: Double = 6.0,      //   (≈0.2°/min near the horizon at Korean latitudes)
    val sunsetSampleGapSec: Int = 30,        // longer location gaps do not count as heading time
    val swapMinEmptySec: Int = 5,            // DRIVER_SWAP: driver seat empty at least this long in P
    val cameraMinCount: Int = 5,             // CAMERA_CLEAN: alerts, all passed within the limit
    val cameraCheckSec: Int = 30,            // speed is checked against the limit this long after an alert
    val nightStartHour: Int = 0,             // NIGHT_DRIVE: local hours [start, end) …
    val nightEndHour: Int = 5,
    val nightMinMin: Int = 20,               // … at least this much driving

    // ---- Route (SPEC 10.1)
    val trackIntervalSec: Int = 5,
    val trackMinMoveM: Double = 50.0,
    val trackMaxAccuracyM: Float = 50f,
    val routeMaxPoints: Int = 400,
    val routeToleranceRatio: Double = 0.003,
    val routeTrimStartM: Double = 1_000.0,
    val routeTrimEndM: Double = 1_000.0,
    val routeMinKm: Double = 3.0,
    val routeMaxPins: Int = 5,
    val routeInsetRatio: Double = 0.03,
    val routeInsetMaxPoints: Int = 80,

    // ---- Places / routine (SPEC 6.4)
    val placeMinStayMin: Int = 30,
    val placeRadiusM: Double = 200.0,
    val placeWindowDays: Int = 14,
    val placeMinVisits: Int = 3,
    val routineRadiusM: Double = 300.0,
    val routineMinTrips: Int = 3,
    val promoteScore: Double = 5.0,          // an episode scoring at least this lifts a routine trip

    // ---- Voice (SPEC 9장)
    val recapRetrySec: Int = 10,
    val recapDeadlineBeforeArrivalSec: Int = 60,
    val recapMaxChars: Int = 35,
    val cameraAlertQuietSec: Int = 30,
    val momentCooldownSec: Int = 30,

    // ---- 중간 상영 (NEXT_DESIGN A)
    val halftimeMinPauseMin: Int = 5,        // a stop on the way at least this long …
    val halftimeMinEpisodes: Int = 2,        // … with at least this many visible episode types so far
    val halftimeMaxSlides: Int = 2,          // top-scoring slides shown (then the end card)

    // ---- Notifications (SPEC 10.2)
    val notifyMaxIgnores: Int = 3,
    val n1MinEpisodes: Int = 2,
    val n1MinTripMin: Int = 20,

    // ---- 연말 결산 (IMPLEMENTATION_NOTES)
    val yearRecapMinTrips: Int = 3,              // fewer finished trips in the year → the sample recap
    val yearRecapHomeFromMonth: Int = 12,        // home status card from Dec 1 (and through January for last year)

    // ---- Story (SPEC 7장)
    val maxEpisodesOnCard: Int = 6,
    val maxAwards: Int = 3,
)
