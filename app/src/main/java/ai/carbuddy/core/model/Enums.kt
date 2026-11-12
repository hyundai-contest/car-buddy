package ai.carbuddy.core.model

enum class GearPos { P, R, N, D, UNKNOWN }

enum class SeatPos { DRIVER, PASSENGER, REAR_LEFT, REAR_RIGHT }

enum class WiperMode { OFF, AUTO, LOW, HIGH }

/** Signal families a car may or may not support (CapabilityRegistry). */
enum class Capability {
    SPEED, GEAR, DRIVER_BELT, SEAT_OCCUPANCY, WIPER, WINDOW, LOW_BEAM, HIGH_BEAM,
    HVAC_TARGET, LOCATION, TURN_SIGNAL, FUEL, NAVI, DOOR, AMBIENT,
}

/** Ambient light colors available on the Pleos emulator (기획안 3장). */
enum class AmbientColor { CYAN, BLUE, PURPLE, WHITE }

/**
 * Episode catalogue (기획안 v13 3장, 15 dex cells).
 * Declaration order is the tie-break priority when scores are equal (SPEC 7장 표 순서).
 */
enum class EpisodeType(val base: Int, val ambient: AmbientColor, val mvp: Boolean, val dex: Boolean = true) {
    DETOUR(5, AmbientColor.CYAN, true),
    LOST_LOOP(5, AmbientColor.WHITE, true),
    SUDDEN_RAIN(5, AmbientColor.BLUE, true),
    TEMP_WAR(4, AmbientColor.WHITE, true),
    JAM_ENDURED(4, AmbientColor.WHITE, true),
    DRIVER_SWAP(4, AmbientColor.WHITE, false),
    REST_STOP(3, AmbientColor.WHITE, true),
    WIND_RUN(3, AmbientColor.CYAN, false),
    TUNNEL_PARADE(3, AmbientColor.PURPLE, false),
    SUNSET_DRIVE(3, AmbientColor.PURPLE, false),
    FULL_HOUSE(2, AmbientColor.WHITE, false),
    CAMERA_CLEAN(2, AmbientColor.WHITE, false),
    REVISIT(2, AmbientColor.WHITE, false),
    NIGHT_DRIVE(2, AmbientColor.PURPLE, false),
    BORDER_CROSS(2, AmbientColor.WHITE, false),
    /** LOST_LOOP near the destination: looking for a parking spot (SPEC 6.2 실사용 보정). Not a dex cell. */
    PARKING_HUNT(2, AmbientColor.WHITE, false, dex = false),
}

enum class TripState { IDLE, DRIVING, ARRIVING, PARKED_AT_DEST, PAUSED, ENDED }

/** Why a trip ended (SPEC 5장 endReason). */
/** ARRIVED_ELSEWHERE: a destination trip that ended away from its destination (no settlement against it). */
enum class EndReason { ARRIVED, ARRIVED_POWER_OFF, ARRIVED_ELSEWHERE, NO_DEST_PLACE, NO_DEST_TIMEOUT, MAX_GAP, PROFILE_SWITCH, USER }
