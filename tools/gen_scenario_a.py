"""Scenario A — Saturday, four friends to Gangneung (기획안 v13 4장 A, card mockup).

Usage: python3 tools/gen_scenario_a.py > docs/replay/scenario_a.jsonl

Timeline (KST 2026-11-07, ETA 13:10 → arrival 14:02, 231 km):
  10:00 depart Seoul Mapo, 4 seats occupied, nav dest "카페" (ETA 13:10)
  10:40–11:15 jam on the Yeongdong expressway            → JAM_ENDURED 35 min
  10:52 passenger 26°C, 10:55 driver 19°C … 11:25 equal   → TEMP_WAR
  11:35–11:57 rest area (P + belt off 22 min, ignition off 11:36–11:56) → REST_STOP 22 min
  12:31 destination → 안목해변                              → DETOUR (+18 min on receipt)
  12:58–13:05 same intersection passed 3 times, 2 re-routes → LOST_LOOP 3 laps, 7 min
  13:10 destination back to 카페
  13:22 wiper LOW, 13:24 HIGH … 13:31 (7 min), 13:33 OFF, 13:36 window opens → SUDDEN_RAIN
  13:59 remain ≤ 3 min                                     → ARRIVING (recap)
  14:02:00 arrived, 14:02:05 P (ceremony), 14:04 driver belt off → ENDED
"""
import io
import sys

from replaygen import Drive, Log, Polyline, hms, offset, parse_start

START = parse_start("2026-11-07T10:00:00+09:00")
TARGET_KM = 231.0

MAPO = (37.556300, 126.923600)
CAFE = (37.892000, 128.826000)          # Jumunjin side of Gangneung
BEACH = (37.771700, 128.947300)          # 안목해변
X = (37.770000, 128.935000)              # the intersection we keep passing
REST = (37.495000, 128.065000)           # highway rest area

SEATS_ALL = {"DRIVER": True, "PASSENGER": True, "REAR_LEFT": True, "REAR_RIGHT": True}
WINDOWS_CLOSED = {"DRIVER": 0, "PASSENGER": 0, "REAR_LEFT": 0, "REAR_RIGHT": 0}

# Geometry (approximate expressway path)
SEOUL_TO_JAM = Polyline([MAPO, (37.5450, 127.0300), (37.5300, 127.1100), (37.4200, 127.1300),
                         (37.2800, 127.1000), (37.2900, 127.3500)])
JAM = Polyline([(37.2900, 127.3500), (37.3000, 127.4100)])
JAM_TO_REST = Polyline([(37.3000, 127.4100), (37.3000, 127.6500), (37.3500, 127.9500), REST])
REST_TO_GN = Polyline([REST, (37.5600, 128.4000), (37.6900, 128.7300), (37.7550, 128.8600)])
GN_TO_X = Polyline([(37.7550, 128.8600), (37.7650, 128.9000), offset(X, -3000, 0), offset(X, -400, 0), X])
L = 365  # loop side in metres
LAP1 = Polyline([X, offset(X, 0, L), offset(X, L, L), offset(X, L, 0), X])
LAP2 = Polyline([X, offset(X, 0, -L), offset(X, -L, -L), offset(X, -L, 0), X])
X_TO_COAST = Polyline([X, offset(X, 700, -700), (37.7705, 128.9460), (37.7900, 128.9300)])   # leave on a new road (SE)
COAST_TO_CAFE = Polyline([(37.7900, 128.9300), (37.8300, 128.8900), (37.8700, 128.8450), CAFE])


def eta_minutes(t):
    """Navi ETA (minutes after 10:00) to the *current* destination, for RemainTime."""
    def lerp(t, t0, v0, t1, v1):
        return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    m = t / 60000.0
    if m <= 40: return 190                                   # 13:10
    if m <= 75: return lerp(m, 40, 190, 75, 225)             # jam pushes ETA to 13:45
    if m <= 95: return 225
    if m <= 117: return lerp(m, 95, 225, 117, 247)           # parked: ETA slides to 14:07
    if m <= 151: return lerp(m, 117, 247, 151, 191)          # clear road: 13:11 at 12:31
    if m <= 190:                                             # destination = beach
        if m <= 177: return m + lerp(m, 151, 22, 177, 5)
        return m + 5 - 0.5 * ((m - 177) % 2)                 # circling ~5 min away
    if m <= 230: return lerp(m, 190, 216, 230, 242)          # back to cafe: 13:36 → 14:02
    return 242


def build(k):
    log = Log(START)
    d = Drive(log)
    T = lambda h, mi, s=0: hms(START, h, mi, s)

    # Initial state
    log.add(0, "SeatOccupancy", seats=SEATS_ALL)
    log.add(0, "DriverBelt", buckled=True)
    log.add(0, "HvacTarget", driverC=22.0, passengerC=22.0)
    log.add(0, "Wiper", front="OFF")
    log.add(0, "WindowPos", pos=WINDOWS_CLOSED)
    log.add(0, "LowBeam", on=False)
    log.add(0, "TurnSignal", on=False)
    log.add(0, "TrunkOpen", open=False)
    log.add(0, "FuelPercent", percent=62)
    log.add(0, "RouteStarted", destName="카페", destLat=CAFE[0], destLng=CAFE[1], etaOffsetSec=11400)
    log.add(0, "Gear", gear="D")
    # RemainTime every minute, added first so a same-time destination change comes after it
    t = 0
    while t <= T(14, 2):
        remain = max(0, int(round((eta_minutes(t) * 60000 - t) / 1000)))
        log.add(t, "RemainTime", remainSec=remain)
        t += 60000
    for extra in (T(11, 57, 5), T(12, 31, 1), T(13, 10, 1)):   # fresh value right after a change/resume
        log.add(extra, "RemainTime", remainSec=int(round((eta_minutes(extra) * 60000 - extra) / 1000)))

    def cruise(base):
        return lambda f, t: base(f) * k

    # 10:00–10:40 city then expressway
    d.leg(T(10, 0), T(10, 40), SEOUL_TO_JAM,
          cruise(lambda f: min(f * 40, 1) * (45 if f < 0.35 else 95) + (5 if int(f * 80) % 2 else 0)))
    # 10:40–11:15 jam: crawl 4–16 km/h with short stops
    d.leg(T(10, 40), T(11, 15), JAM,
          lambda f, t: 0.0 if (t // 5000) % 37 in (0, 1, 2) else 6 + ((t // 5000) % 5) * 2.2)
    # 11:15–11:35 expressway, slow into rest area
    d.leg(T(11, 15), T(11, 35), JAM_TO_REST, cruise(lambda f: 100 if f < 0.92 else 100 * (1 - f) / 0.08 + 8))
    # 11:35–11:57 rest stop
    log.add(T(11, 35), "Gear", gear="P")
    log.add(T(11, 35, 30), "DriverBelt", buckled=False)
    d.park(T(11, 35), T(11, 57))
    # ignition off for 20 minutes (SPEC 4.3 PowerCycle REBOOT); after boot the adapters report
    # their current values and the navigation restores the same route
    log.add(T(11, 36), "PowerCycle", offSec=1200, mode="REBOOT")
    boot = T(11, 56) + 1000
    log.add(boot, "Gear", gear="P")
    log.add(boot, "Speed", kmh=0.0)
    log.add(boot, "DriverBelt", buckled=False)
    log.add(boot, "SeatOccupancy", seats=SEATS_ALL)
    log.add(boot, "HvacTarget", driverC=22.0, passengerC=22.0)
    log.add(boot, "Wiper", front="OFF")
    log.add(boot, "WindowPos", pos=WINDOWS_CLOSED)
    log.add(boot + 4000, "Location", lat=round(REST[0], 6), lng=round(REST[1], 6), accuracyM=8.0, bearing=None)
    log.add(boot + 10000, "RouteStarted", destName="카페", destLat=CAFE[0], destLng=CAFE[1], etaOffsetSec=7800)
    log.add(T(11, 56, 30), "DriverBelt", buckled=True)
    log.add(T(11, 57), "Gear", gear="D")
    # 11:57–12:31 clear expressway ("뚫린 구간")
    d.leg(T(11, 57), T(12, 31), REST_TO_GN, cruise(lambda f: min(f * 30, 1) * 105 + 8))
    # 12:31 detour to the beach, coastal road
    d.leg(T(12, 31), T(12, 58), GN_TO_X, lambda f, t: 55 if f < 0.85 else 30)
    # 12:58–13:05 two laps around X → passes at 12:58:00, 13:01:30, 13:05:00
    d.leg(T(12, 58), T(13, 1, 30), LAP1, lambda f, t: 25)
    d.leg(T(13, 1, 30), T(13, 5), LAP2, lambda f, t: 25)
    # 13:05–13:10 along the sea
    d.leg(T(13, 5), T(13, 10), X_TO_COAST, lambda f, t: 30)
    # 13:10–14:02 to the cafe, rain slows us down, short light stops
    d.leg(T(13, 10), T(14, 2), COAST_TO_CAFE,
          lambda f, t: 0.0 if (t // 5000) % 90 in (0, 1, 2, 3) else (28 if T(13, 22) <= t < T(13, 34) else 36) * (1 if f < 0.97 else (1 - f) / 0.03 + 0.05))
    d.park(T(14, 2), T(14, 5))

    # Navigation events
    log.add(T(12, 31), "DestinationChanged", destName="안목해변", destLat=BEACH[0], destLng=BEACH[1])
    log.add(T(12, 59, 30), "ReRouteEnded")
    log.add(T(13, 3), "ReRouteEnded")
    log.add(T(13, 10), "DestinationChanged", destName="카페", destLat=CAFE[0], destLng=CAFE[1])
    log.add(T(14, 2), "DestinationArrived")
    log.add(T(14, 2, 5), "Gear", gear="P")

    # Cabin events
    log.add(T(10, 52), "HvacTarget", driverC=22.0, passengerC=26.0)
    log.add(T(10, 55), "HvacTarget", driverC=19.0, passengerC=26.0)
    log.add(T(11, 25), "HvacTarget", driverC=22.0, passengerC=22.0)
    log.add(T(13, 22), "Wiper", front="LOW")
    log.add(T(13, 24), "Wiper", front="HIGH")
    log.add(T(13, 31), "Wiper", front="LOW")
    log.add(T(13, 33), "Wiper", front="OFF")
    log.add(T(13, 36), "WindowPos", pos={"DRIVER": 0, "PASSENGER": 40, "REAR_LEFT": 30, "REAR_RIGHT": 0})
    log.add(T(13, 45), "WindowPos", pos=WINDOWS_CLOSED)
    log.add(T(13, 58, 50), "TurnSignal", on=True)     # recap must wait for the turn signal
    log.add(T(13, 59, 5), "TurnSignal", on=False)
    log.add(T(14, 4), "DriverBelt", buckled=False)
    return log, d


def main():
    _, d1 = build(1.0)
    _, d0 = build(0.0)           # cruise legs contribute nothing at k=0
    k = (TARGET_KM - d0.km) / (d1.km - d0.km)
    log, d = build(k)
    buf = io.StringIO()
    log.dump(buf)
    sys.stdout.reconfigure(encoding="utf-8") if hasattr(sys.stdout, "reconfigure") else None
    sys.stdout.write(buf.getvalue())
    sys.stderr.write("scenario A: %.1f km, cruise factor %.3f, %d lines\n" % (d.km, k, len(log.rows) + 1))


if __name__ == "__main__":
    main()
