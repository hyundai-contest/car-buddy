"""Scenario B — Wednesday commute alone, no destination (기획안 v13 4장 B, E2E 7장).

Usage: python3 tools/gen_commute_b.py [days_offset] > docs/replay/commute_b.jsonl

  18:30 leave office, driver only, no navigation destination, passenger window 15 %, fuel 12 %
  18:40–19:05 jam                                   → JAM_ENDURED 25 min (title "25분을 버틴 수요일")
  19:08–19:18 convenience store, ignition off 8 min → provisional arrival, resumed → '들른 곳 10분'
  19:29 enter underground garage: GPS accuracy drops, no fix after 19:30 → parking memo prompt
  19:31:30 P, 19:32 belt off                        → provisional arrival (PAUSED) + exit check
  next day 08:00 PowerBoot                          → NO_DEST_TIMEOUT, card FINAL (ended 19:31:30)
"""
import io
import sys
from datetime import timedelta

from replaygen import Drive, Log, Polyline, hms, parse_start

OFFSET_DAYS = int(sys.argv[1]) if len(sys.argv) > 1 else 0
START = parse_start("2026-11-04T18:30:00+09:00") + timedelta(days=OFFSET_DAYS)
OFFICE = (37.501800, 127.039600)
SHOP = (37.535000, 126.991000)
HOME = (37.558500, 126.978000)
ROUTE1 = Polyline([OFFICE, (37.5100, 127.0200), (37.5180, 127.0050)])
JAM = Polyline([(37.5180, 127.0050), (37.5300, 126.9950)])
TO_SHOP = Polyline([(37.5300, 126.9950), SHOP])
TO_HOME = Polyline([SHOP, (37.5450, 126.9850), HOME])


def main():
    log = Log(START)
    d = Drive(log)
    T = lambda h, mi, s=0: hms(START, h, mi, s)
    log.add(0, "SeatOccupancy", seats={"DRIVER": True, "PASSENGER": False, "REAR_LEFT": False, "REAR_RIGHT": False})
    log.add(0, "DriverBelt", buckled=True)
    log.add(0, "HvacTarget", driverC=23.0, passengerC=23.0)
    log.add(0, "Wiper", front="OFF")
    log.add(0, "WindowPos", pos={"DRIVER": 0, "PASSENGER": 15, "REAR_LEFT": 0, "REAR_RIGHT": 0})
    log.add(0, "LowBeam", on=True)
    log.add(0, "TurnSignal", on=False)
    log.add(0, "TrunkOpen", open=False)
    log.add(0, "FuelPercent", percent=12)
    log.add(0, "Gear", gear="D")
    d.leg(T(18, 30), T(18, 40), ROUTE1, lambda f, t: min(f * 10, 1) * 40 + 5)
    d.leg(T(18, 40), T(19, 5), JAM, lambda f, t: 0.0 if (t // 5000) % 29 in (0, 1, 2) else 5 + ((t // 5000) % 4) * 3.0)
    d.leg(T(19, 5), T(19, 8), TO_SHOP, lambda f, t: 30 if f < 0.85 else 30 * (1 - f) / 0.15 + 3)
    # convenience store: P, belt off, ignition off 8 min, back on, leave at 19:18
    log.add(T(19, 8), "Gear", gear="P")
    log.add(T(19, 8, 20), "DriverBelt", buckled=False)
    d.park(T(19, 8), T(19, 9))
    log.add(T(19, 9), "PowerCycle", offSec=480, mode="REBOOT")
    boot = T(19, 17) + 1000
    log.add(boot, "Gear", gear="P")
    log.add(boot, "Speed", kmh=0.0)
    log.add(boot, "DriverBelt", buckled=False)
    log.add(boot, "SeatOccupancy", seats={"DRIVER": True, "PASSENGER": False, "REAR_LEFT": False, "REAR_RIGHT": False})
    log.add(boot, "WindowPos", pos={"DRIVER": 0, "PASSENGER": 15, "REAR_LEFT": 0, "REAR_RIGHT": 0})
    log.add(boot, "LowBeam", on=True)
    log.add(boot, "FuelPercent", percent=12)
    log.add(boot + 4000, "Location", lat=round(SHOP[0], 6), lng=round(SHOP[1], 6), accuracyM=6.0, bearing=None)
    log.add(T(19, 17, 40), "DriverBelt", buckled=True)
    log.add(T(19, 18), "Gear", gear="D")
    accuracy = lambda f: 5.0 if f < 0.83 else (45.0 if f < 0.92 else None)   # garage: worse, then no fix
    d.leg(T(19, 18), T(19, 31), TO_HOME, lambda f, t: 38 if f < 0.9 else 38 * (1 - f) / 0.1 + 3, accuracy=accuracy)
    log.add(T(19, 31), "Speed", kmh=0.0)
    log.add(T(19, 31, 30), "Gear", gear="P")
    log.add(T(19, 32), "DriverBelt", buckled=False)
    log.add(T(19, 32, 30), "LowBeam", on=False)
    # Ignition off → process dies. Next morning the head unit boots again.
    next_boot = int((START.replace(hour=8, minute=0) + timedelta(days=1) - START).total_seconds() * 1000)
    log.add(next_boot, "PowerBoot", reason="BOOT")
    buf = io.StringIO()
    log.dump(buf)
    sys.stdout.reconfigure(encoding="utf-8") if hasattr(sys.stdout, "reconfigure") else None
    sys.stdout.write(buf.getvalue())
    sys.stderr.write("commute B: %.1f km, %d lines\n" % (d.km, len(log.rows) + 1))


if __name__ == "__main__":
    main()
