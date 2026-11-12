"""Shared helpers for replay-log generators (SPEC 4.3 JSONL format).

Python 3.8+, standard library only. Generators print JSONL to stdout.
"""
import json
import math
from datetime import datetime

EARTH_R = 6371000.0


def hms(base, h, m, s=0):
    """ms offset of clock time h:m:s from base (a datetime)."""
    t = base.replace(hour=h, minute=m, second=s)
    return int((t - base).total_seconds() * 1000)


def haversine(a, b):
    lat1, lng1 = math.radians(a[0]), math.radians(a[1])
    lat2, lng2 = math.radians(b[0]), math.radians(b[1])
    dlat, dlng = lat2 - lat1, lng2 - lng1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlng / 2) ** 2
    return 2 * EARTH_R * math.asin(math.sqrt(h))


def bearing(a, b):
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlng = math.radians(b[1] - a[1])
    y = math.sin(dlng) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlng)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def offset(origin, east_m, north_m):
    lat = origin[0] + north_m / 111320.0
    lng = origin[1] + east_m / (111320.0 * math.cos(math.radians(origin[0])))
    return (lat, lng)


class Polyline:
    def __init__(self, pts):
        self.pts = pts
        self.cum = [0.0]
        for i in range(1, len(pts)):
            self.cum.append(self.cum[-1] + haversine(pts[i - 1], pts[i]))

    @property
    def length(self):
        return self.cum[-1]

    def at(self, d):
        d = max(0.0, min(d, self.length))
        for i in range(1, len(self.pts)):
            if d <= self.cum[i] or i == len(self.pts) - 1:
                seg = self.cum[i] - self.cum[i - 1]
                f = 0.0 if seg == 0 else (d - self.cum[i - 1]) / seg
                a, b = self.pts[i - 1], self.pts[i]
                return (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
        return self.pts[-1]


class Log:
    """Collects events as (dt, seq, dict) and prints them sorted by dt (stable)."""

    def __init__(self, start_at):
        self.start_at = start_at
        self.rows = []
        self.seq = 0

    def add(self, dt, typ, **fields):
        row = {"dt": int(dt), "type": typ}
        row.update(fields)
        self.rows.append((int(dt), self.seq, row))
        self.seq += 1

    def dump(self, out):
        out.write(json.dumps({"dt": 0, "type": "Meta", "startAt": self.start_at.isoformat()}, ensure_ascii=False) + "\n")
        for _, _, row in sorted(self.rows, key=lambda r: (r[0], r[1])):
            out.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


class Drive:
    """Moves a car along legs; emits Speed + Location every `step_ms` and integrates distance."""

    def __init__(self, log, step_ms=5000):
        self.log = log
        self.step = step_ms
        self.km = 0.0
        self.pos = None
        self.last_speed = None

    def leg(self, t0, t1, geometry, speed_fn, accuracy=5.0, emit_location=True):
        """speed_fn(frac, t_ms) -> km/h. Geometry is traversed proportionally to integrated speed."""
        n = max(1, (t1 - t0) // self.step)
        speeds = [max(0.0, speed_fn(i / n, t0 + i * self.step)) for i in range(n + 1)]
        dist = [0.0]
        for i in range(1, n + 1):
            dist.append(dist[-1] + (speeds[i - 1] + speeds[i]) / 2 / 3.6 * self.step / 1000.0)
        total = dist[-1] or 1.0
        prev = geometry.at(0) if geometry else self.pos
        for i in range(n + 1):
            t = t0 + i * self.step
            if i < n:
                kmh = round(speeds[i], 1)
                if kmh != self.last_speed:
                    self.log.add(t, "Speed", kmh=kmh)
                    self.last_speed = kmh
            p = geometry.at(dist[i] / total * geometry.length) if geometry else self.pos
            if emit_location and i < n:
                acc = accuracy(i / n) if callable(accuracy) else accuracy
                if acc is not None:
                    b = bearing(prev, p) if haversine(prev, p) > 1 else None
                    self.log.add(t, "Location", lat=round(p[0], 6), lng=round(p[1], 6), accuracyM=acc,
                                 bearing=None if b is None else round(b, 1))
            prev = p
            self.pos = p
        self.km += dist[-1] / 1000.0

    def park(self, t0, t1, every_ms=30000):
        if self.last_speed != 0.0:
            self.log.add(t0, "Speed", kmh=0.0)
            self.last_speed = 0.0
        t = t0
        while t < t1:
            self.log.add(t, "Location", lat=round(self.pos[0], 6), lng=round(self.pos[1], 6), accuracyM=5.0, bearing=None)
            t += every_ms


def parse_start(s):
    return datetime.fromisoformat(s)
