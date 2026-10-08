#!/usr/bin/env python3
"""Read-only observation review of a pulled camera.jsonl and an optional viewer.jsonl.

One JSON object per line (ts, run, event). Prints the phase-14.6 metrics per day and
marks, with ⚑, each number that crosses a review trigger in the roadmap's Observation
table. Field names that are not in the log yet are reported as missing; a field missing
on 90% or more of the events that should carry it cannot support a finding. Writes
nothing but this text.
"""

from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

# Observation-table thresholds. Names match the wording they implement.
WALL_CLOCK_DAY_MS = 24 * 60 * 60 * 1000
THROTTLE_DUTY_FRACTION = 0.25  # more than 25% of the wall-clock day
THROTTLE_DUTY_DAYS = 3  # on 3 days running
THROTTLE_CYCLES = 6  # 6 or more throttle/release cycles in a day
THROTTLE_TEMP_WINDOW_MS = 15 * 60 * 1000  # temperature has not fallen within 15 minutes
GAP_OVER_MS = 1000  # recording gap over 1 s
DRAIN_DISAGREE_FRACTION = 0.30  # disagrees by more than 30%
DRAIN_WINDOW_MIN_MS = 50 * 60 * 1000
DRAIN_WINDOW_MAX_MS = 70 * 60 * 1000
LATE_REVERT_MS = 2 * 60 * 1000  # later than 2 minutes
# Phase 14.1 sheds capture to 15 fps while idle or throttled; the power-saving
# release bound is two frames at that rate.
IDLE_FPS = 15
TWO_FRAMES_MS = 2 / IDLE_FPS * 1000
# Each power_saving line counts. At the 240 s cap a short engagement is two lines
# at most every 240 s, and the ramp to the cap peaks at 34 in one hour. 40 sits
# above that; a quiet period stuck under 2 min (60 lines) still flags.
POWER_SAVING_PER_HOUR = 40  # flapping more than 40 times an hour
SOUND_EVENTS_PER_DAY = 20  # more than 20 sound_event lines in a day
ALERT_DELAY_MS = 60 * 1000  # delay_ms over 60 s
# ResponsePolicy.DARK_LUMA_THRESHOLD: mean luma below this counts as dark.
DARK_LUMA = 50
# CameraService auto-spotlight holds the torch this long; an off after it is late.
AUTO_SPOTLIGHT_MS = 20_000
# Phase 14.1: a level releases 2 °C below itself, after the existing ten-minute dwell.
TEMP_FALL_C = 2.0
RELEASE_DWELL_MS = 10 * 60 * 1000
NULL_FINDING_FRACTION = 0.90
HEARTBEAT_GAP_MAX_MS = 10 * 60 * 1000  # heartbeats are ~5 min; a longer gap is not evidence
MISS_WINDOW_MS = 2_000  # "coincides with" a transition
CLIP_AFTER_DETECTION_MS = 30_000
ALERT_MATCH_MS = 5 * 60 * 1000
CRITICAL_ALERT_MS = 60 * 1000
HOUR_MS = 60 * 60 * 1000

FLAG = "\u2691"

THROTTLE_STEPS = ("fps", "live_bitrate", "screen", "charger_hold", "tier")
ENGAGE = {"engage", "engaged", "on", "start", "true", "1"}
RELEASE = {"release", "released", "off", "stop", "false", "0"}
LIGHT_OR_BELOW = {"none", "light", "0", "1"}
ABOVE_LIGHT = {"moderate", "severe", "critical", "emergency", "shutdown", "2", "3", "4", "5", "6"}
SOUND_REASONS = {"sound", "sound_event"}
DETECTION_REASONS = {"detection", "vision"}
MANUAL_REASONS = {"manual", "manual_clip", "clip"}

WATCHED = (
    "heartbeat",
    "segment",
    "prune",
    "alert",
    "sound_event",
    "audio_listen",
    "throttle",
    "power_saving",
    "health_state",
    "recording_cap",
    "recording_revert",
    "recording_timer_set",
    "spotlight",
    "service_start",
)

# Stable review lines. Each is the observation-table condition, verbatim enough
# that a reader can find the row, and each is asserted by tools/test_observe.py.
PHRASE_DUTY = "throttled for more than 25% of the wall-clock day on 3 days running"
PHRASE_CYCLES = (
    "6 or more throttle/release cycles in a day while the platform thermal status "
    "stays at LIGHT or below"
)
PHRASE_TEMP = "temperature has not fallen within 15 minutes of a throttle"
PHRASE_PAUSE = "any pause at all in a week"
PHRASE_NO_CLIP = "a detection during a cap produced no clip"
PHRASE_NO_RESUME = "no resume after the temperature fell"
PHRASE_GAP = "recording gap over 1 s coincides with a throttle step"
PHRASE_MISS_THROTTLE = "missed detection coincides with a throttle step"
PHRASE_DRAIN = "drain rate disagrees with the battery percentage change by more than 30%"
PHRASE_BATTERY = "battery axis paused at a percentage other than the configured threshold"
PHRASE_FLOOR = "floor_held false with no storage health_state"
PHRASE_CRITICAL = "critical entry with no alert"
PHRASE_LATE = "revert later than 2 minutes"
PHRASE_RESTART = "no revert after a restart that spanned the due time"
PHRASE_FRAMES = "power_saving release more than 2 frames after its trigger"
PHRASE_FLAP = "power_saving engage/release flapping more than 40 times an hour"
PHRASE_MISS_POWER = "detection missed at a power_saving transition"
PHRASE_MIC = "sound_event or listening interval while the microphone setting was off"
PHRASE_SOUNDS = "more than 20 sound_event lines in a day"
PHRASE_DELAY = "delay_ms over 60 s with idle true"
PHRASE_NO_VIEWER = "camera alert delivered with no viewer line"
PHRASE_DAYLIGHT = "spotlight on in daylight"
PHRASE_DARK = "spotlight no light in the dark"
PHRASE_SPOT_OFF = "spotlight off missing within the auto window"

PHRASE_ORDER = (
    PHRASE_DUTY,
    PHRASE_CYCLES,
    PHRASE_TEMP,
    PHRASE_PAUSE,
    PHRASE_NO_CLIP,
    PHRASE_NO_RESUME,
    PHRASE_GAP,
    PHRASE_MISS_THROTTLE,
    PHRASE_DRAIN,
    PHRASE_BATTERY,
    PHRASE_FLOOR,
    PHRASE_CRITICAL,
    PHRASE_LATE,
    PHRASE_RESTART,
    PHRASE_FRAMES,
    PHRASE_FLAP,
    PHRASE_MISS_POWER,
    PHRASE_MIC,
    PHRASE_SOUNDS,
    PHRASE_DELAY,
    PHRASE_NO_VIEWER,
    PHRASE_DAYLIGHT,
    PHRASE_DARK,
    PHRASE_SPOT_OFF,
)


def local_tz():
    return datetime.now().astimezone().tzinfo


def day_of(ts: float):
    return datetime.fromtimestamp(ts / 1000).date()


def day_bounds(day) -> tuple[int, int]:
    tz = local_tz()
    start = datetime(day.year, day.month, day.day, tzinfo=tz)
    end = start + timedelta(days=1)
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


def clock(ts: float) -> str:
    return datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S")


def num(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def text(value) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped if stripped else None


def as_bool(value) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if value == 1:
            return True
        if value == 0:
            return False
        return None
    token = text(value)
    if token is None:
        return None
    token = token.lower()
    if token in ENGAGE:
        return True
    if token in RELEASE:
        return False
    return None


def lower(value) -> str | None:
    token = text(value)
    return token.lower() if token else None


def thermal_is_light_or_below(value) -> bool | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        token = str(int(value))
    else:
        token = lower(value)
    if token is None:
        return None
    if token.startswith("thermal_"):
        token = token[len("thermal_") :]
    if token in LIGHT_OR_BELOW:
        return True
    if token in ABOVE_LIGHT:
        return False
    return None


def fmt_duration(ms: float) -> str:
    total = max(0, int(round(ms / 1000)))
    hours, rest = divmod(total, 3600)
    minutes, seconds = divmod(rest, 60)
    if hours:
        return f"{hours}h{minutes:02d}m"
    if minutes:
        return f"{minutes}m{seconds:02d}s"
    return f"{seconds}s"


def fmt_pct(fraction: float) -> str:
    return f"{fraction * 100:.1f}%"


def percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    index = max(0, min(len(ordered) - 1, int(round((pct / 100) * (len(ordered) - 1)))))
    return ordered[index]


def union_ms(intervals: list[tuple[float, float]], start: int, end: int) -> int:
    clipped = []
    for left, right in intervals:
        left = max(left, start)
        right = min(right, end)
        if right > left:
            clipped.append((left, right))
    clipped.sort()
    total = 0
    cur_left = cur_right = None
    for left, right in clipped:
        if cur_right is None or left > cur_right:
            if cur_right is not None:
                total += cur_right - cur_left
            cur_left, cur_right = left, right
        else:
            cur_right = max(cur_right, right)
    if cur_right is not None:
        total += cur_right - cur_left
    return int(total)


def peak_in_window(timestamps: list[float], window_ms: int) -> int:
    ordered = sorted(timestamps)
    peak = 0
    left = 0
    for right, ts in enumerate(ordered):
        while ts - ordered[left] > window_ms:
            left += 1
        peak = max(peak, right - left + 1)
    return peak


class Coverage:
    """How often a field the report reads was null or absent, per event type."""

    def __init__(self) -> None:
        self.present: Counter[tuple[str, str, str]] = Counter()
        self.missing: Counter[tuple[str, str, str]] = Counter()
        self.counts: Counter[tuple[str, str]] = Counter()

    def seen(self, source: str, event: str) -> None:
        self.counts[(source, event)] += 1

    def take(self, source: str, event: str, obj: dict, name: str):
        key = (source, event, name)
        if name not in obj or obj[name] is None:
            self.missing[key] += 1
            return None
        self.present[key] += 1
        return obj[name]

    def total(self, source: str, event: str, name: str) -> int:
        key = (source, event, name)
        return self.present[key] + self.missing[key]

    def usable(self, source: str, event: str, name: str) -> bool:
        total = self.total(source, event, name)
        if total == 0:
            return False
        return self.missing[(source, event, name)] / total < NULL_FINDING_FRACTION

    def lines(self) -> list[str]:
        out = []
        keys = set(self.present) | set(self.missing)
        by_event: dict[tuple[str, str], list[str]] = defaultdict(list)
        for source, event, name in sorted(keys):
            missing = self.missing[(source, event, name)]
            total = missing + self.present[(source, event, name)]
            rate = missing / total if total else 0
            note = " incomplete" if rate >= NULL_FINDING_FRACTION else ""
            label = f"{source} {event}.{name}" if source != "camera" else f"{event}.{name}"
            by_event[(source, event)].append(
                f"    {label}: missing {missing}/{total} ({rate:.0%}){note}"
            )
        seen = {(source, event) for source, event in self.counts}
        for source, event in sorted(seen):
            label = f"{source} {event}" if source != "camera" else event
            out.append(f"  {label}: {self.counts[(source, event)]}")
            out.extend(by_event.get((source, event), []))
        for event in WATCHED:
            if ("camera", event) not in seen:
                out.append(f"  {event}: 0 events")
        return out


def load_jsonl(path: Path) -> tuple[list[dict], int]:
    events = []
    unparsed = 0
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            raw = line.strip()
            if not raw:
                continue
            try:
                obj = json.loads(raw)
            except json.JSONDecodeError:
                unparsed += 1
                continue
            if not isinstance(obj, dict) or not isinstance(obj.get("event"), str):
                unparsed += 1
                continue
            ts = obj.get("ts")
            if isinstance(ts, bool) or not isinstance(ts, (int, float)):
                unparsed += 1
                continue
            events.append(obj)
    events.sort(key=lambda item: item["ts"])
    return events, unparsed


@dataclass
class Episode:
    start: float
    end: float
    temp: float | None
    day: object
    from_heartbeat: bool = False


@dataclass
class ThermalPause:
    start: float
    end: float | None
    temp: float | None
    day: object
    resumed: bool = False


@dataclass
class Result:
    coverage: Coverage
    days: dict = field(default_factory=dict)
    flags: list[tuple[str, str]] = field(default_factory=list)
    day_marks: dict = field(default_factory=lambda: defaultdict(set))
    event_counts: Counter = field(default_factory=Counter)
    viewer_supplied: bool = False
    log_end: float = 0
    mic_sound: Counter = field(default_factory=Counter)
    mic_listen: Counter = field(default_factory=Counter)
    viewer_alerts: list = field(default_factory=list)
    drain_note: str | None = None


def mark(result: Result, phrase: str, detail: str, day=None, key: str | None = None) -> None:
    result.flags.append((phrase, detail))
    if day is not None and key is not None:
        result.day_marks[day].add(key)


def starred(result: Result, day, key: str, text_value: str) -> str:
    if key in result.day_marks[day]:
        return f"{text_value} {FLAG}"
    return text_value


def analyze(camera: list[dict], viewer: list[dict], viewer_supplied: bool) -> Result:
    cov = Coverage()
    result = Result(coverage=cov, viewer_supplied=viewer_supplied)
    if camera:
        result.log_end = max(event["ts"] for event in camera)
    if viewer:
        result.log_end = max(result.log_end, max(event["ts"] for event in viewer))

    for event in camera:
        cov.seen("camera", event["event"])
        result.event_counts[event["event"]] += 1
    for event in viewer:
        cov.seen("viewer", event["event"])

    heartbeats = []
    throttle_events = []
    segments = []
    alerts = []
    sounds = []
    power_events = []
    for event in camera:
        kind = event["event"]
        if kind == "heartbeat":
            heartbeats.append(_heartbeat(cov, event))
        elif kind == "throttle":
            throttle_events.append(_throttle(cov, event))
        elif kind == "segment":
            segments.append(_segment(cov, event))
        elif kind == "alert":
            alerts.append(_alert(cov, event))
        elif kind == "sound_event":
            sounds.append(_sound(cov, event))
        elif kind == "power_saving":
            power_events.append(_power(cov, event))

    _audio(cov, camera, sounds, result)
    episodes, transitions, step_intervals = _throttle_intervals(throttle_events)
    _heartbeat_throttle(heartbeats, episodes)
    pauses = _thermal_pauses(cov, camera, heartbeats)
    _fill_days(result, camera, heartbeats, sounds, pauses)
    _duty_and_cycles(result, episodes, throttle_events, step_intervals)
    _temp_not_fallen(result, episodes, heartbeats, throttle_events, cov)
    _gaps_and_misses(result, segments, transitions, alerts, cov)
    _power_flags(result, power_events, sounds, alerts, segments, heartbeats, cov)
    _drain(result, heartbeats, cov)
    _battery_threshold(result, camera, cov)
    _floor_and_critical(result, camera, alerts, cov)
    _timers(result, camera, cov)
    _spotlight(result, camera, cov)
    _viewer(result, alerts, viewer, cov)
    _heat_pause_flags(result, pauses, alerts, segments, heartbeats, cov)
    return result


def _heartbeat(cov: Coverage, event: dict) -> dict:
    return {
        "ts": event["ts"],
        "temp": num(cov.take("camera", "heartbeat", event, "battery_temp_c")),
        "throttled": as_bool(cov.take("camera", "heartbeat", event, "thermal_throttled")),
        "pct": num(cov.take("camera", "heartbeat", event, "battery_pct")),
        "charging": as_bool(cov.take("camera", "heartbeat", event, "charging")),
        "drain": num(cov.take("camera", "heartbeat", event, "drain_pct_per_h")),
    }


def _throttle(cov: Coverage, event: dict) -> dict:
    return {
        "ts": event["ts"],
        "step": text(cov.take("camera", "throttle", event, "step")),
        "on": as_bool(cov.take("camera", "throttle", event, "state")),
        "temp": num(cov.take("camera", "throttle", event, "battery_temp_c")),
        "status": cov.take("camera", "throttle", event, "thermal_status"),
        "day": day_of(event["ts"]),
    }


def _segment(cov: Coverage, event: dict) -> dict:
    return {
        "ts": event["ts"],
        "gap": num(cov.take("camera", "segment", event, "gap_ms")),
        "duration": num(cov.take("camera", "segment", event, "duration_ms")),
    }


def _alert(cov: Coverage, event: dict) -> dict:
    kind = lower(cov.take("camera", "alert", event, "kind"))
    return {
        "ts": event["ts"],
        "kind": kind,
        "delivered": num(cov.take("camera", "alert", event, "delivered")),
    }


def _sound(cov: Coverage, event: dict) -> dict:
    return {
        "ts": event["ts"],
        "level": num(cov.take("camera", "sound_event", event, "level_db")),
        "category": text(cov.take("camera", "sound_event", event, "category")),
    }


def _power(cov: Coverage, event: dict) -> dict:
    return {
        "ts": event["ts"],
        "on": as_bool(cov.take("camera", "power_saving", event, "state")),
        "reason": lower(cov.take("camera", "power_saving", event, "reason")),
    }


def _audio(cov: Coverage, camera: list[dict], sounds: list[dict], result: Result) -> None:
    """Mic-off opens when listening stops because the setting is off, and closes when
    a later listen start shows the tap is running again. A start while it is still
    off, or a sound_event inside the open interval, is the privacy defect."""
    mic_off_from = None
    listening_from = None
    per_day_listen = Counter()
    for event in camera:
        if event["event"] != "audio_listen":
            continue
        state = as_bool(cov.take("camera", "audio_listen", event, "state"))
        reason = lower(cov.take("camera", "audio_listen", event, "reason"))
        ts = event["ts"]
        if reason == "mic_off":
            stopping = state is not True
            if listening_from is not None and not stopping:
                per_day_listen[day_of(ts)] += 1
            if stopping:
                listening_from = None
            if mic_off_from is None:
                mic_off_from = ts
            continue
        if state is True:
            if mic_off_from is not None:
                per_day_listen[day_of(ts)] += 1
                mic_off_from = None
            listening_from = ts
        elif state is False:
            listening_from = None
    per_day_sound = Counter()
    for sound in sounds:
        for left, right in _mic_off_intervals(camera):
            if left <= sound["ts"] < right:
                per_day_sound[day_of(sound["ts"])] += 1
                break
    result.mic_sound = per_day_sound
    result.mic_listen = per_day_listen


def _mic_off_intervals(camera: list[dict]) -> list[tuple[float, float]]:
    intervals = []
    opened = None
    for event in camera:
        if event["event"] != "audio_listen":
            continue
        state = as_bool(event.get("state"))
        reason = lower(event.get("reason"))
        ts = event["ts"]
        if reason == "mic_off" and opened is None:
            opened = ts
        elif state is True and reason != "mic_off" and opened is not None:
            intervals.append((opened, ts))
            opened = None
    if opened is not None and camera:
        intervals.append((opened, camera[-1]["ts"] + 1))
    return intervals


def _throttle_intervals(events: list[dict]):
    active: set[str] = set()
    step_open: dict[str, float] = {}
    step_intervals: dict[str, list[tuple[float, float]]] = defaultdict(list)
    episodes: list[Episode] = []
    transitions: list[float] = []
    episode_start = None
    episode_temp = None
    for event in events:
        if event["on"] is None or not event["step"]:
            continue
        step = event["step"]
        ts = event["ts"]
        if event["on"]:
            if step not in active:
                active.add(step)
                step_open[step] = ts
                transitions.append(ts)
            if episode_start is None:
                episode_start = ts
                episode_temp = event["temp"]
        elif step in active:
            active.remove(step)
            step_intervals[step].append((step_open.pop(step), ts))
            transitions.append(ts)
            if episode_start is not None and not active:
                episodes.append(Episode(episode_start, ts, episode_temp, day_of(episode_start)))
                episode_start = None
                episode_temp = None
    return episodes, transitions, step_intervals


def _heartbeat_throttle(heartbeats: list[dict], episodes: list[Episode]) -> None:
    """Heartbeats carry thermal_throttled before throttle events exist. A gap longer
    than two heartbeat periods is not evidence the flag stayed on."""
    extra = []
    for earlier, later in zip(heartbeats, heartbeats[1:]):
        gap = later["ts"] - earlier["ts"]
        if earlier["throttled"] is True and 0 < gap <= HEARTBEAT_GAP_MAX_MS:
            extra.append((earlier["ts"], later["ts"]))
    # Fold heartbeat intervals into episodes only when no throttle event already
    # covers them: represented as zero-temp episodes so duty includes them and the
    # 15-minute temperature check (which needs a start temperature) ignores them.
    for left, right in extra:
        if any(ep.start <= left and right <= ep.end for ep in episodes):
            continue
        episodes.append(Episode(left, right, None, day_of(left), from_heartbeat=True))


def _thermal_pauses(cov: Coverage, camera: list[dict], heartbeats: list[dict]) -> list[ThermalPause]:
    pauses: list[ThermalPause] = []
    open_pause: ThermalPause | None = None
    for event in camera:
        kind = event["event"]
        if kind == "recording_cap":
            cap = lower(cov.take("camera", "recording_cap", event, "cap"))
            reason = lower(cov.take("camera", "recording_cap", event, "reason"))
            temp = num(cov.take("camera", "recording_cap", event, "battery_temp_c"))
            if reason == "thermal" and cap not in (None, "none"):
                if open_pause is None:
                    open_pause = ThermalPause(event["ts"], None, temp, day_of(event["ts"]))
                    pauses.append(open_pause)
            elif cap == "none" and open_pause is not None:
                open_pause.end = event["ts"]
                open_pause.resumed = True
                open_pause = None
        elif kind == "health_state":
            axis = lower(cov.take("camera", "health_state", event, "axis"))
            to = lower(cov.take("camera", "health_state", event, "to"))
            cov.take("camera", "health_state", event, "from")
            if axis != "thermal":
                continue
            temp = num(cov.take("camera", "health_state", event, "battery_temp_c"))
            if to in {"pause", "critical"} and open_pause is None:
                open_pause = ThermalPause(event["ts"], None, temp, day_of(event["ts"]))
                pauses.append(open_pause)
            elif to in {"ok", "warn"} and open_pause is not None:
                open_pause.end = event["ts"]
                open_pause.resumed = True
                open_pause = None
    for pause in pauses:
        if pause.temp is None:
            earlier = [item["temp"] for item in heartbeats if item["ts"] <= pause.start and item["temp"] is not None]
            if earlier:
                pause.temp = earlier[-1]
    return pauses


def _bucket(result: Result, day):
    buf = result.days.get(day)
    if buf is None:
        buf = {
            "first": None,
            "last": None,
            "temps": [],
            "heartbeats": 0,
            "throttled_hb": 0,
            "sounds": [],
            "sound_count": 0,
            "power_ts": [],
            "gaps": 0,
            "gap_largest": 0,
            "gap_coincide": 0,
            "reverts": [],
            "drain_pairs": 0,
            "drain_bad": 0,
            "battery_off": 0,
            "floor_false": 0,
            "storage_health": 0,
            "critical": 0,
            "critical_bare": 0,
            "spotlight": 0,
        }
        result.days[day] = buf
    return buf


def _fill_days(result, camera, heartbeats, sounds, pauses):
    for event in camera:
        day = day_of(event["ts"])
        buf = _bucket(result, day)
        buf["first"] = event["ts"] if buf["first"] is None else min(buf["first"], event["ts"])
        buf["last"] = event["ts"] if buf["last"] is None else max(buf["last"], event["ts"])
    for beat in heartbeats:
        buf = _bucket(result, day_of(beat["ts"]))
        buf["heartbeats"] += 1
        if beat["temp"] is not None:
            buf["temps"].append(beat["temp"])
        if beat["throttled"] is True:
            buf["throttled_hb"] += 1
    for sound in sounds:
        buf = _bucket(result, day_of(sound["ts"]))
        buf["sound_count"] += 1
        if sound["level"] is not None:
            buf["sounds"].append(sound["level"])
    for pause in pauses:
        buf = _bucket(result, pause.day)
        buf.setdefault("pauses", []).append(pause)
    # Ensure days touched only by derived pauses exist.
    for day in list(result.days):
        result.days[day].setdefault("pauses", [])


def _duty_and_cycles(result, episodes, throttle_events, step_intervals) -> None:
    by_day_status: dict = defaultdict(list)
    for event in throttle_events:
        if event["on"] is None or not event["step"]:
            continue
        by_day_status[event["day"]].append(event["status"])

    cycles = Counter()
    for episode in episodes:
        # Heartbeat slices carry thermal_throttled but are not engage/release cycles.
        # A throttle episode with no temperature is still a cycle.
        if not episode.from_heartbeat and episode.end > episode.start:
            cycles[episode.day] += 1
        day = day_of(episode.start)
        last = day_of(max(episode.start, episode.end - 1))
        cursor = day
        while cursor <= last:
            _bucket(result, cursor)
            cursor += timedelta(days=1)
    for day in result.days:
        result.days[day]["cycles"] = cycles[day]

    # Recompute duty from all episodes clipped per day, including heartbeat slices.
    for day in result.days:
        start, end = day_bounds(day)
        intervals = [(ep.start, ep.end) for ep in episodes]
        result.days[day]["throttle_ms"] = union_ms(intervals, start, end)
        step_ms = {}
        for step in THROTTLE_STEPS:
            step_ms[step] = union_ms(step_intervals.get(step, []), start, end)
        for step, spans in step_intervals.items():
            if step not in step_ms:
                step_ms[step] = union_ms(spans, start, end)
        result.days[day]["step_ms"] = step_ms
        fraction = result.days[day]["throttle_ms"] / WALL_CLOCK_DAY_MS
        result.days[day]["duty"] = fraction

    _cycle_flags(result, by_day_status)
    _duty_flags(result)


def _cycle_flags(result, by_day_status) -> None:
    for day, buf in result.days.items():
        count = buf.get("cycles", 0)
        statuses = by_day_status.get(day, [])
        if count < THROTTLE_CYCLES:
            continue
        if not result.coverage.usable("camera", "throttle", "thermal_status"):
            continue
        if not statuses or any(status is None for status in statuses):
            continue
        verdicts = [thermal_is_light_or_below(status) for status in statuses]
        if any(verdict is not True for verdict in verdicts):
            continue
        mark(result, PHRASE_CYCLES, f"{day.isoformat()} {count}", day, "cycles")


def _duty_flags(result) -> None:
    run: list = []
    runs = []
    for day in sorted(result.days):
        high = result.days[day]["duty"] > THROTTLE_DUTY_FRACTION
        if high and (not run or day - run[-1] == timedelta(days=1)):
            run.append(day)
        else:
            if len(run) >= THROTTLE_DUTY_DAYS:
                runs.append(run)
            run = [day] if high else []
    if len(run) >= THROTTLE_DUTY_DAYS:
        runs.append(run)
    for streak in runs:
        detail = ", ".join(
            f"{day.isoformat()} {fmt_pct(result.days[day]['duty'])}" for day in streak
        )
        mark(result, PHRASE_DUTY, detail)
        for day in streak:
            result.day_marks[day].add("duty")


def _temp_not_fallen(result, episodes, heartbeats, throttle_events, cov: Coverage) -> None:
    if not cov.usable("camera", "heartbeat", "battery_temp_c") and not cov.usable(
        "camera", "throttle", "battery_temp_c"
    ):
        return
    samples = [(item["ts"], item["temp"]) for item in heartbeats if item["temp"] is not None]
    samples += [(event["ts"], event["temp"]) for event in throttle_events if event["temp"] is not None]
    flagged = []
    for episode in episodes:
        if episode.temp is None:
            continue
        if episode.end - episode.start < THROTTLE_TEMP_WINDOW_MS:
            continue
        if result.log_end < episode.start + THROTTLE_TEMP_WINDOW_MS:
            continue
        window = [
            temp
            for ts, temp in samples
            if episode.start < ts <= episode.start + THROTTLE_TEMP_WINDOW_MS and temp is not None
        ]
        if not window:
            continue
        if all(temp >= episode.temp for temp in window):
            flagged.append(episode)
            result.day_marks[episode.day].add("temp")
    if flagged:
        detail = ", ".join(f"{ep.day.isoformat()} {clock(ep.start)} {ep.temp:.1f}°C" for ep in flagged)
        mark(result, PHRASE_TEMP, detail)


def _gaps_and_misses(result, segments, transitions, alerts, cov: Coverage) -> None:
    gap_usable = cov.usable("camera", "segment", "gap_ms") or cov.total("camera", "segment", "gap_ms") == 0
    for segment in segments:
        if segment["gap"] is None or segment["gap"] <= GAP_OVER_MS:
            continue
        day = day_of(segment["ts"])
        buf = _bucket(result, day)
        buf["gaps"] += 1
        buf["gap_largest"] = max(buf["gap_largest"], segment["gap"])
        duration = segment["duration"] or 0
        gap_end = segment["ts"] - duration
        gap_start = gap_end - segment["gap"]
        if any(gap_start <= ts <= gap_end for ts in transitions):
            buf["gap_coincide"] += 1
    if gap_usable and cov.usable("camera", "segment", "gap_ms"):
        total = sum(buf["gap_coincide"] for buf in result.days.values())
        if total:
            days = [
                f"{day.isoformat()} {buf['gap_coincide']}"
                for day, buf in sorted(result.days.items())
                if buf["gap_coincide"]
            ]
            mark(result, PHRASE_GAP, ", ".join(days))
            for day, buf in result.days.items():
                if buf["gap_coincide"]:
                    result.day_marks[day].add("gap")

    if not cov.usable("camera", "alert", "kind"):
        return
    detections = [alert for alert in alerts if alert["kind"] == "detection"]
    missed = []
    for ts in transitions:
        for alert in detections:
            if abs(alert["ts"] - ts) > MISS_WINDOW_MS:
                continue
            if _clip_after(segments, alert["ts"]):
                continue
            missed.append(alert)
            result.day_marks[day_of(alert["ts"])].add("miss_throttle")
            break
    if missed:
        mark(
            result,
            PHRASE_MISS_THROTTLE,
            ", ".join(f"{day_of(alert['ts']).isoformat()} {clock(alert['ts'])}" for alert in missed),
        )


def _clip_after(segments, ts: float) -> bool:
    return any(ts < segment["ts"] <= ts + CLIP_AFTER_DETECTION_MS for segment in segments)


def _power_flags(result, power_events, sounds, alerts, segments, heartbeats, cov: Coverage) -> None:
    for event in power_events:
        _bucket(result, day_of(event["ts"]))["power_ts"].append(event["ts"])
    if not power_events:
        return
    by_day = defaultdict(list)
    for event in power_events:
        by_day[day_of(event["ts"])].append(event["ts"])
    flap_days = []
    for day, stamps in by_day.items():
        peak = peak_in_window(stamps, HOUR_MS)
        result.days[day]["power_peak"] = peak
        if peak > POWER_SAVING_PER_HOUR and cov.usable("camera", "power_saving", "state"):
            flap_days.append(f"{day.isoformat()} {peak}/h")
            result.day_marks[day].add("flap")
    if flap_days:
        mark(result, PHRASE_FLAP, ", ".join(flap_days))

    if cov.usable("camera", "power_saving", "reason") and cov.usable("camera", "power_saving", "state"):
        late = []
        for event in power_events:
            if event["on"] is not False or not event["reason"]:
                continue
            trigger = _release_trigger(event, sounds, alerts, segments)
            if trigger is None:
                continue
            latency = event["ts"] - trigger
            if latency > TWO_FRAMES_MS:
                late.append((event, latency))
                result.day_marks[day_of(event["ts"])].add("frames")
        if late:
            detail = ", ".join(
                f"{day_of(event['ts']).isoformat()} {latency:.0f}ms" for event, latency in late
            )
            mark(result, PHRASE_FRAMES, detail)

    if not cov.usable("camera", "alert", "kind"):
        return
    detections = [alert for alert in alerts if alert["kind"] == "detection"]
    missed = []
    for event in power_events:
        for alert in detections:
            if abs(alert["ts"] - event["ts"]) > MISS_WINDOW_MS:
                continue
            if _clip_after(segments, alert["ts"]):
                continue
            missed.append(alert)
            result.day_marks[day_of(alert["ts"])].add("miss_power")
            break
    if missed and cov.usable("camera", "power_saving", "state"):
        mark(
            result,
            PHRASE_MISS_POWER,
            ", ".join(f"{day_of(alert['ts']).isoformat()} {clock(alert['ts'])}" for alert in missed),
        )


def _release_trigger(event, sounds, alerts, segments) -> float | None:
    reason = event["reason"]
    if reason in SOUND_REASONS:
        pool = [item["ts"] for item in sounds if item["ts"] <= event["ts"]]
    elif reason in DETECTION_REASONS:
        pool = [item["ts"] for item in alerts if item["kind"] == "detection" and item["ts"] <= event["ts"]]
    elif reason in MANUAL_REASONS:
        pool = [item["ts"] for item in segments if item["ts"] <= event["ts"]]
    else:
        return None
    return max(pool) if pool else None


def _drain(result, heartbeats, cov: Coverage) -> None:
    needed = ("drain_pct_per_h", "battery_pct", "charging")
    if any(not cov.usable("camera", "heartbeat", name) for name in needed):
        result.drain_note = "not evaluated"
        return
    result.drain_note = None
    unplugged = [
        beat
        for beat in heartbeats
        if beat["charging"] is False and beat["pct"] is not None and beat["drain"] is not None
    ]
    bad_days = []
    for earlier in unplugged:
        later = _hour_later(earlier, unplugged)
        if later is None:
            continue
        hours = (later["ts"] - earlier["ts"]) / HOUR_MS
        if hours <= 0:
            continue
        observed = (earlier["pct"] - later["pct"]) / hours
        reported = later["drain"]
        day = day_of(later["ts"])
        buf = _bucket(result, day)
        buf["drain_pairs"] += 1
        if _drain_disagrees(reported, observed):
            buf["drain_bad"] += 1
            bad_days.append(
                f"{day.isoformat()} reported {reported:.1f}/h vs {observed:.1f}/h from battery %"
            )
            result.day_marks[day].add("drain")
    if bad_days:
        mark(result, PHRASE_DRAIN, "; ".join(bad_days))


def _hour_later(earlier, unplugged):
    best = None
    best_distance = None
    target = earlier["ts"] + HOUR_MS
    for later in unplugged:
        delta = later["ts"] - earlier["ts"]
        if not (DRAIN_WINDOW_MIN_MS <= delta <= DRAIN_WINDOW_MAX_MS):
            continue
        distance = abs(later["ts"] - target)
        if best_distance is None or distance < best_distance:
            best = later
            best_distance = distance
    return best


def _drain_disagrees(reported: float, observed: float) -> bool:
    # "More than 30%" is relative to the battery percentage's own change.
    # A gauge that did not move cannot resolve under 1%/h (battery % is an integer).
    if observed == 0:
        return abs(reported) >= 1
    return abs(reported - observed) / abs(observed) > DRAIN_DISAGREE_FRACTION


def _battery_threshold(result, camera, cov: Coverage) -> None:
    judged = 0
    missing_threshold = 0
    bad = []
    for event in camera:
        if event["event"] != "health_state":
            continue
        axis = lower(event.get("axis"))
        to = lower(event.get("to"))
        if axis != "battery" or to != "pause":
            continue
        judged += 1
        pct = num(cov.take("camera", "health_state", event, "battery_pct"))
        threshold = num(cov.take("camera", "health_state", event, "battery_pause_pct"))
        if pct is None:
            missing_threshold += 1
            continue
        if threshold is None:
            missing_threshold += 1
            continue
        if pct != threshold:
            day = day_of(event["ts"])
            _bucket(result, day)["battery_off"] += 1
            bad.append(f"{day.isoformat()} paused at {pct:.0f}% threshold {threshold:.0f}%")
            result.day_marks[day].add("battery")
    if judged and missing_threshold / judged >= NULL_FINDING_FRACTION:
        return
    if bad:
        mark(result, PHRASE_BATTERY, "; ".join(bad))


def _floor_and_critical(result, camera, alerts, cov: Coverage) -> None:
    prunes_by_day = defaultdict(list)
    storage_days = set()
    criticals = []
    for event in camera:
        if event["event"] == "prune":
            held = as_bool(cov.take("camera", "prune", event, "floor_held"))
            prunes_by_day[day_of(event["ts"])].append(held)
        elif event["event"] == "health_state":
            axis = lower(event.get("axis"))
            to = lower(event.get("to"))
            if axis == "storage":
                storage_days.add(day_of(event["ts"]))
                _bucket(result, day_of(event["ts"]))["storage_health"] += 1
            if to == "critical":
                criticals.append(event)
    floor_days = []
    for day, helds in prunes_by_day.items():
        false_count = sum(1 for held in helds if held is False)
        _bucket(result, day)["floor_false"] = false_count
        if (
            cov.usable("camera", "prune", "floor_held")
            and false_count
            and day not in storage_days
        ):
            floor_days.append(f"{day.isoformat()} {false_count}")
            result.day_marks[day].add("floor")
    if floor_days:
        mark(result, PHRASE_FLOOR, ", ".join(floor_days))

    bare = []
    for event in criticals:
        if _health_alert_near(alerts, event["ts"]):
            continue
        day = day_of(event["ts"])
        buf = _bucket(result, day)
        buf["critical"] += 1
        buf["critical_bare"] += 1
        bare.append(f"{day.isoformat()} {lower(event.get('axis')) or 'unknown'}")
        result.day_marks[day].add("critical")
    # critical count includes those with alerts, for the daily line.
    for event in criticals:
        _bucket(result, day_of(event["ts"]))["critical"] += 0
    seen = Counter(day_of(event["ts"]) for event in criticals)
    for day, count in seen.items():
        result.days[day]["critical"] = count
    if bare and cov.usable("camera", "health_state", "to"):
        # Alerts may be absent entirely; that is the finding, not a null field.
        # Suppress only when the health_state level itself is mostly missing.
        mark(result, PHRASE_CRITICAL, ", ".join(bare))


def _health_alert_near(alerts, ts: float) -> bool:
    return any(
        alert["kind"] == "health" and 0 <= alert["ts"] - ts <= CRITICAL_ALERT_MS for alert in alerts
    )


def _timers(result, camera, cov: Coverage) -> None:
    timers = []
    reverts = []
    starts = []
    indexed = list(enumerate(camera))
    for index, event in indexed:
        kind = event["event"]
        if kind == "recording_timer_set":
            due = num(cov.take("camera", "recording_timer_set", event, "due_at_ms"))
            cov.take("camera", "recording_timer_set", event, "mode")
            cov.take("camera", "recording_timer_set", event, "revert_to")
            cov.take("camera", "recording_timer_set", event, "via")
            timers.append({"ts": event["ts"], "due": due, "index": index})
        elif kind == "recording_revert":
            due = num(cov.take("camera", "recording_revert", event, "due_at_ms"))
            late = num(cov.take("camera", "recording_revert", event, "late_ms"))
            if late is None and due is not None:
                late = event["ts"] - due
            reverts.append({"ts": event["ts"], "due": due, "late": late})
            if late is not None:
                _bucket(result, day_of(event["ts"]))["reverts"].append(late)
        elif kind == "service_start":
            cov.take("camera", "service_start", event, "reason")
            cov.take("camera", "service_start", event, "prev_run_unclean")
            prev_ts = camera[index - 1]["ts"] if index else None
            starts.append({"ts": event["ts"], "prev": prev_ts, "event": event, "index": index})

    late_ones = [item for item in reverts if item["late"] is not None and item["late"] > LATE_REVERT_MS]
    if late_ones and (
        cov.usable("camera", "recording_revert", "late_ms")
        or cov.usable("camera", "recording_revert", "due_at_ms")
    ):
        for item in late_ones:
            result.day_marks[day_of(item["ts"])].add("late")
        detail = ", ".join(
            f"{day_of(item['ts']).isoformat()} late_ms {item['late']:.0f}" for item in late_ones
        )
        mark(result, PHRASE_LATE, detail)

    missing = []
    for timer in timers:
        due = timer["due"]
        if due is None:
            continue
        for start in starts:
            if start["prev"] is None:
                continue
            if not _spans(start, due):
                continue
            if timer["ts"] >= start["ts"]:
                continue
            if any(_same_due(item["due"], due) and item["ts"] >= start["ts"] for item in reverts):
                continue
            # Already reverted before the outage.
            if any(_same_due(item["due"], due) and item["ts"] < start["ts"] for item in reverts):
                continue
            missing.append((timer, start))
            result.day_marks[day_of(start["ts"])].add("restart")
    if missing and cov.usable("camera", "recording_timer_set", "due_at_ms"):
        detail = ", ".join(
            f"due {day_of(timer['due']).isoformat()} {clock(timer['due'])} "
            f"restart {clock(start['ts'])}"
            for timer, start in missing
        )
        mark(result, PHRASE_RESTART, detail)


def _spans(start, due: float) -> bool:
    return start["prev"] < due <= start["ts"]


def _same_due(left, right) -> bool:
    if left is None or right is None:
        return False
    return abs(left - right) < 1


def _spotlight(result, camera, cov: Coverage) -> None:
    pending = None
    daylight = []
    dark = []
    late = []
    for event in camera:
        if event["event"] != "spotlight":
            continue
        day = day_of(event["ts"])
        _bucket(result, day)["spotlight"] += 1
        on = as_bool(cov.take("camera", "spotlight", event, "on"))
        auto = as_bool(cov.take("camera", "spotlight", event, "auto"))
        luma = num(cov.take("camera", "spotlight", event, "luma"))
        cov.take("camera", "spotlight", event, "duration_ms")
        if auto is not True:
            if on is False and pending is not None:
                pending = None
            continue
        if on is True:
            if luma is not None and luma >= DARK_LUMA:
                daylight.append(event)
                result.day_marks[day].add("daylight")
            pending = event
        elif on is False:
            if pending is None:
                if luma is not None and luma < DARK_LUMA:
                    dark.append(event)
                    result.day_marks[day].add("dark")
            else:
                delta = event["ts"] - pending["ts"]
                if delta > AUTO_SPOTLIGHT_MS and result.log_end >= pending["ts"] + AUTO_SPOTLIGHT_MS:
                    late.append((pending, delta))
                    result.day_marks[day_of(pending["ts"])].add("spot_off")
                pending = None
    if pending is not None and result.log_end >= pending["ts"] + AUTO_SPOTLIGHT_MS:
        late.append((pending, None))
        result.day_marks[day_of(pending["ts"])].add("spot_off")
    if daylight and cov.usable("camera", "spotlight", "luma") and cov.usable("camera", "spotlight", "auto"):
        mark(
            result,
            PHRASE_DAYLIGHT,
            ", ".join(f"{day_of(event['ts']).isoformat()} luma {num(event.get('luma')):.0f}" for event in daylight),
        )
    if dark and cov.usable("camera", "spotlight", "luma"):
        mark(
            result,
            PHRASE_DARK,
            ", ".join(f"{day_of(event['ts']).isoformat()} luma {num(event.get('luma')):.0f}" for event in dark),
        )
    if late and cov.usable("camera", "spotlight", "on"):
        parts = []
        for event, delta in late:
            if delta is None:
                parts.append(f"{day_of(event['ts']).isoformat()} {clock(event['ts'])} no off")
            else:
                parts.append(f"{day_of(event['ts']).isoformat()} off after {fmt_duration(delta)}")
        mark(result, PHRASE_SPOT_OFF, ", ".join(parts))


def _viewer(result, alerts, viewer, cov: Coverage) -> None:
    view_alerts = []
    for event in viewer:
        if event["event"] != "alert":
            continue
        kind = lower(cov.take("viewer", "alert", event, "kind"))
        if kind is None:
            kind = lower(cov.take("viewer", "alert", event, "label"))
        view_alerts.append(
            {
                "ts": event["ts"],
                "kind": kind,
                "delay": num(cov.take("viewer", "alert", event, "delay_ms")),
                "idle": as_bool(cov.take("viewer", "alert", event, "idle")),
            }
        )
    result.viewer_alerts = view_alerts
    if not result.viewer_supplied:
        return
    slow = [
        item
        for item in view_alerts
        if item["delay"] is not None and item["delay"] > ALERT_DELAY_MS and item["idle"] is True
    ]
    if slow and cov.usable("viewer", "alert", "delay_ms") and cov.usable("viewer", "alert", "idle"):
        for item in slow:
            result.day_marks[day_of(item["ts"])].add("delay")
        detail = ", ".join(
            f"{day_of(item['ts']).isoformat()} {item['delay']:.0f}ms" for item in slow
        )
        mark(result, PHRASE_DELAY, detail)

    if not cov.usable("camera", "alert", "delivered"):
        return
    unmatched = _unmatched(alerts, view_alerts)
    if unmatched:
        for alert in unmatched:
            day = day_of(alert["ts"])
            result.day_marks[day].add("viewer")
            buf = _bucket(result, day)
            buf["no_viewer"] = buf.get("no_viewer", 0) + 1
        detail = ", ".join(
            f"{day_of(alert['ts']).isoformat()} {alert['kind'] or 'unknown'} {clock(alert['ts'])}"
            for alert in unmatched
        )
        mark(result, PHRASE_NO_VIEWER, detail)


def _unmatched(alerts, view_alerts) -> list[dict]:
    used = set()
    missing = []
    delivered = [alert for alert in alerts if alert["delivered"] == 1]
    for alert in delivered:
        best = None
        best_dt = None
        for index, view in enumerate(view_alerts):
            if index in used:
                continue
            if alert["kind"] and view["kind"] and alert["kind"] != view["kind"]:
                continue
            delta = abs(view["ts"] - alert["ts"])
            if delta > ALERT_MATCH_MS:
                continue
            if best_dt is None or delta < best_dt:
                best = index
                best_dt = delta
        if best is None:
            missing.append(alert)
        else:
            used.add(best)
    return missing


def _heat_pause_flags(result, pauses, alerts, segments, heartbeats, cov: Coverage) -> None:
    if not pauses:
        return
    for pause in pauses:
        result.day_marks[pause.day].add("pause")
    detail = ", ".join(f"{pause.day.isoformat()} {clock(pause.start)}" for pause in pauses)
    mark(result, PHRASE_PAUSE, detail)

    if cov.usable("camera", "alert", "kind"):
        bare = []
        for pause in pauses:
            end = pause.end if pause.end is not None else result.log_end
            for alert in alerts:
                if alert["kind"] != "detection":
                    continue
                if not (pause.start <= alert["ts"] <= end):
                    continue
                clipped_end = end
                if any(alert["ts"] < segment["ts"] <= clipped_end for segment in segments):
                    continue
                bare.append(alert)
                result.day_marks[day_of(alert["ts"])].add("noclip")
        if bare:
            mark(
                result,
                PHRASE_NO_CLIP,
                ", ".join(f"{day_of(alert['ts']).isoformat()} {clock(alert['ts'])}" for alert in bare),
            )

    stuck = []
    for pause in pauses:
        if pause.resumed or pause.temp is None:
            continue
        if not cov.usable("camera", "heartbeat", "battery_temp_c") and not cov.usable(
            "camera", "recording_cap", "battery_temp_c"
        ):
            continue
        fallen_at = None
        for beat in heartbeats:
            if beat["ts"] <= pause.start or beat["temp"] is None:
                continue
            if beat["temp"] <= pause.temp - TEMP_FALL_C:
                fallen_at = beat["ts"]
                break
        if fallen_at is None:
            continue
        if result.log_end < fallen_at + RELEASE_DWELL_MS:
            continue
        stuck.append((pause, fallen_at))
        result.day_marks[pause.day].add("noresume")
    if stuck:
        detail = ", ".join(
            f"{pause.day.isoformat()} fell at {clock(fallen)} to ≤ {pause.temp - TEMP_FALL_C:.1f}°C"
            for pause, fallen in stuck
        )
        mark(result, PHRASE_NO_RESUME, detail)


def _mic_flags(result) -> None:
    sound = result.mic_sound
    listen = result.mic_listen
    if not sound and not listen:
        return
    if result.coverage.total("camera", "audio_listen", "reason") and not result.coverage.usable(
        "camera", "audio_listen", "reason"
    ):
        return
    days = sorted(set(sound) | set(listen))
    parts = []
    for day in days:
        if sound[day] or listen[day]:
            parts.append(f"{day.isoformat()} sound {sound[day]} listening {listen[day]}")
            result.day_marks[day].add("mic")
            _bucket(result, day)
    if parts:
        mark(result, PHRASE_MIC, ", ".join(parts))


def _sound_count_flags(result) -> None:
    if not result.coverage.usable("camera", "sound_event", "level_db") and result.coverage.total(
        "camera", "sound_event", "level_db"
    ):
        # The count itself does not depend on level_db. Only skip when the event
        # rows are present but we somehow failed to see them; the count is the
        # number of sound_event lines, which is not a nullable field.
        pass
    parts = []
    for day, buf in sorted(result.days.items()):
        if buf["sound_count"] > SOUND_EVENTS_PER_DAY:
            parts.append(f"{day.isoformat()} {buf['sound_count']}")
            result.day_marks[day].add("sounds")
    if parts:
        mark(
            result,
            PHRASE_SOUNDS,
            ", ".join(parts) + " (confirm the user reported none were real)",
        )


def format_result(camera_path: Path, viewer_path: Path | None, camera_unparsed: int, camera_parsed: int, viewer_unparsed: int, viewer_parsed: int, result: Result) -> str:
    _mic_flags(result)
    _sound_count_flags(result)
    lines = [
        f"camera: {camera_path}  parsed: {camera_parsed}  unparsed: {camera_unparsed}",
    ]
    if viewer_path is None:
        lines.append("viewer: not provided")
    else:
        lines.append(f"viewer: {viewer_path}  parsed: {viewer_parsed}  unparsed: {viewer_unparsed}")
    if result.event_counts:
        summary = ", ".join(f"{name} {count}" for name, count in result.event_counts.most_common())
        lines.append(f"events: {summary}")
    lines.append("coverage:")
    lines.extend(result.coverage.lines() or ["  (no events)"])
    if not result.days:
        lines.append("(no dated events)")
    for day in sorted(result.days):
        lines.append("")
        lines.extend(_format_day(result, day))
    lines.append("")
    lines.append("review triggers:")
    if not result.flags:
        lines.append("  none")
    else:
        order = {phrase: index for index, phrase in enumerate(PHRASE_ORDER)}
        # One line per phrase; keep the first detail if a phrase was recorded twice.
        seen = {}
        for phrase, detail in result.flags:
            seen.setdefault(phrase, detail)
        for phrase in sorted(seen, key=lambda item: order.get(item, len(order))):
            lines.append(f"{FLAG} {phrase}: {seen[phrase]}")
    lines.append("")
    return "\n".join(lines)


def _format_day(result: Result, day) -> list[str]:
    buf = result.days[day]
    pauses = buf.get("pauses", [])
    resumes = sum(1 for pause in pauses if pause.resumed)
    first, last = buf["first"], buf["last"]
    observed = (last - first) if first is not None and last is not None and last > first else 0
    start, end = day_bounds(day)
    capped = union_ms(
        [(pause.start, pause.end if pause.end is not None else result.log_end) for pause in pauses],
        start,
        end,
    )
    share = (capped / observed) if observed else 0
    lines = [day.isoformat()]
    lines.append(
        "  "
        + starred(result, day, "pause", f"heat pauses: {len(pauses)}")
        + "   "
        + starred(result, day, "noresume", f"resumes: {resumes}")
    )
    lines.append(f"  time capped: {fmt_duration(capped)} of {fmt_duration(observed)} observed ({fmt_pct(share)})")
    if pauses:
        for pause in pauses:
            before, after = _temps_around(result, pause)
            at = f"{pause.temp:.1f}°C" if pause.temp is not None else "unknown"
            lines.append(
                f"  pause temperature: {clock(pause.start)} before {before} at {at} after {after}"
                + (f" {FLAG}" if "noresume" in result.day_marks[day] or "noclip" in result.day_marks[day] else "")
            )
    else:
        lines.append("  pause temperature: none")
    if buf["temps"]:
        lines.append(
            f"  heartbeat temperature: {min(buf['temps']):.1f}–{max(buf['temps']):.1f} °C (n={len(buf['temps'])})"
        )
    else:
        lines.append("  heartbeat temperature: no readings")
    lines.append(f"  thermal_throttled heartbeats: {buf['throttled_hb']}/{buf['heartbeats']}")
    duty = buf.get("duty", 0)
    throttle_ms = buf.get("throttle_ms", 0)
    lines.append(
        "  " + starred(result, day, "duty", f"throttled: {fmt_duration(throttle_ms)} ({fmt_pct(duty)} of 24h)")
        + "   "
        + starred(result, day, "cycles", f"cycles: {buf.get('cycles', 0)}")
        + (" " + FLAG if "temp" in result.day_marks[day] else "")
    )
    step_ms = buf.get("step_ms") or {step: 0 for step in THROTTLE_STEPS}
    duty_bits = [f"{step} {fmt_pct(step_ms.get(step, 0) / WALL_CLOCK_DAY_MS)}" for step in THROTTLE_STEPS]
    for step, ms in step_ms.items():
        if step not in THROTTLE_STEPS:
            duty_bits.append(f"{step} {fmt_pct(ms / WALL_CLOCK_DAY_MS)}")
    lines.append("  throttle step duty of 24h: " + ", ".join(duty_bits))
    peak = buf.get("power_peak", peak_in_window(buf["power_ts"], HOUR_MS))
    per_hour = (len(buf["power_ts"]) / (observed / HOUR_MS)) if observed else float(len(buf["power_ts"]))
    lines.append(
        "  "
        + starred(
            result,
            day,
            "flap",
            f"power_saving: {len(buf['power_ts'])} transitions, {per_hour:.1f}/h over the observed span, peak {peak}/h",
        )
        + (" " + FLAG if "frames" in result.day_marks[day] or "miss_power" in result.day_marks[day] else "")
    )
    if buf["sounds"]:
        level = f"{min(buf['sounds']):.1f}–{max(buf['sounds']):.1f}"
    else:
        level = "none"
    lines.append(
        "  "
        + starred(result, day, "sounds", f"sound_events: {buf['sound_count']}")
        + f"   level_db {level}"
        + (" " + FLAG if "mic" in result.day_marks[day] else "")
    )
    sound_hits = result.mic_sound[day]
    listen_hits = result.mic_listen[day]
    lines.append(
        "  "
        + starred(
            result,
            day,
            "mic",
            f"microphone off overlaps: {sound_hits} sound_events, {listen_hits} listening intervals",
        )
    )
    lines.append("  " + _delay_line(result, day))
    lines.append("  " + _viewer_line(result, day))
    coincide = buf["gap_coincide"]
    largest = f", largest {fmt_duration(buf['gap_largest'])}" if buf["gaps"] else ""
    lines.append(
        "  "
        + starred(
            result,
            day,
            "gap",
            f"recording gaps >1s: {buf['gaps']}{largest}, {coincide} coincide with a throttle transition",
        )
        + (" " + FLAG if "miss_throttle" in result.day_marks[day] else "")
    )
    if buf["reverts"]:
        shown = ", ".join(f"late_ms {late:.0f}" for late in buf["reverts"])
    else:
        shown = "0"
    lines.append(
        "  "
        + starred(result, day, "late", f"timed-mode reverts: {shown}")
        + (" " + FLAG if "restart" in result.day_marks[day] else "")
    )
    lines.append("  " + _drain_line(result, day))
    lines.append(
        "  " + starred(result, day, "battery", f"battery pause off threshold: {buf['battery_off']}")
    )
    floor_shown = buf["floor_false"] if not buf["storage_health"] else 0
    lines.append(
        "  "
        + starred(
            result,
            day,
            "floor",
            f"floor_held false without storage health_state: {floor_shown}",
        )
    )
    lines.append(
        "  " + starred(result, day, "critical", f"critical without alert: {buf['critical_bare']}")
    )
    spot = []
    if "daylight" in result.day_marks[day]:
        spot.append("daylight")
    if "dark" in result.day_marks[day]:
        spot.append("dark")
    if "spot_off" in result.day_marks[day]:
        spot.append("off outside auto window")
    spot_note = f" ({', '.join(spot)} {FLAG})" if spot else ""
    lines.append(f"  spotlight events: {buf['spotlight']}{spot_note}")
    return lines


def _temps_around(result, pause: ThermalPause) -> tuple[str, str]:
    # Temperatures live on the day buffers only as a flat list; recover from marks
    # is unnecessary — format walks heartbeats stored on the result via days' temps
    # is not timestamped. Keep the strings from the pause object when we stashed them.
    before = getattr(pause, "before", None)
    after = getattr(pause, "after", None)
    return (
        f"{before:.1f}°C" if isinstance(before, float) else "unknown",
        f"{after:.1f}°C" if isinstance(after, float) else "unknown",
    )


def _delay_line(result: Result, day) -> str:
    if not result.viewer_supplied:
        return "alert delay p50/p95: viewer log not provided"
    delays = [
        item["delay"]
        for item in result.viewer_alerts
        if item["delay"] is not None and day_of(item["ts"]) == day
    ]
    if not delays:
        text_value = "alert delay p50/p95: no viewer alert lines"
    else:
        text_value = (
            f"alert delay p50/p95: {percentile(delays, 50):.0f}ms / {percentile(delays, 95):.0f}ms"
            f" (n={len(delays)})"
        )
    return starred(result, day, "delay", text_value)


def _viewer_line(result: Result, day) -> str:
    if not result.viewer_supplied:
        return "camera alerts delivered=1 with no viewer line: not evaluated (viewer log not provided)"
    count = result.days[day].get("no_viewer", 0)
    return starred(result, day, "viewer", f"camera alerts delivered=1 with no viewer line: {count}")


def _drain_line(result: Result, day) -> str:
    note = result.drain_note
    if note:
        missing = result.coverage.missing[("camera", "heartbeat", "drain_pct_per_h")]
        total = result.coverage.total("camera", "heartbeat", "drain_pct_per_h")
        if total:
            return f"drain vs battery %: not evaluated (heartbeat.drain_pct_per_h missing {missing}/{total})"
        return "drain vs battery %: not evaluated (no heartbeats)"
    buf = result.days[day]
    return starred(
        result,
        day,
        "drain",
        f"drain vs battery %: {buf['drain_pairs']} pair(s), {buf['drain_bad']} disagree by more than 30%",
    )


def _stash_pause_temps(result: Result, heartbeats: list[dict]) -> None:
    for buf in result.days.values():
        for pause in buf.get("pauses", []):
            earlier = [item["temp"] for item in heartbeats if item["ts"] < pause.start and item["temp"] is not None]
            later = [item["temp"] for item in heartbeats if item["ts"] > pause.start and item["temp"] is not None]
            pause.before = earlier[-1] if earlier else None
            pause.after = later[0] if later else None


def render(camera_path: Path, viewer_path: Path | None = None) -> str:
    camera, camera_unparsed = load_jsonl(camera_path)
    viewer: list[dict] = []
    viewer_unparsed = 0
    if viewer_path is not None:
        viewer, viewer_unparsed = load_jsonl(viewer_path)
    result = analyze(camera, viewer, viewer_supplied=viewer_path is not None)
    # Pause before/after temperatures need the heartbeat list. Re-read is avoided
    # by stashing during analyze; fill any gap from a second lightweight pass.
    heartbeats = []
    cov = result.coverage
    # Temps were counted already. Walk the parsed events again only for timestamps.
    for event in camera:
        if event["event"] != "heartbeat":
            continue
        temp = num(event.get("battery_temp_c"))
        heartbeats.append({"ts": event["ts"], "temp": temp})
    _stash_pause_temps(result, heartbeats)
    # cov is already complete; the second walk must not double-count. It doesn't:
    # it reads the dict directly.
    del cov
    return format_result(
        camera_path,
        viewer_path,
        camera_unparsed,
        len(camera),
        viewer_unparsed,
        len(viewer),
        result,
    )


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) not in (1, 2):
        print("usage: python3 tools/observe.py camera.jsonl [viewer.jsonl]")
        return 2
    camera = Path(args[0])
    if not camera.is_file():
        print(f"observe: no such file: {camera}")
        return 1
    viewer = None
    if len(args) == 2:
        viewer = Path(args[1])
        if not viewer.is_file():
            print(f"observe: no such file: {viewer}")
            return 1
    reconfigure = getattr(sys.stdout, "reconfigure", None)
    if reconfigure is not None:
        try:
            reconfigure(encoding="utf-8")
        except (OSError, ValueError):
            pass
    print(render(camera, viewer), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
