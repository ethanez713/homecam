"""Synthetic logs for tools/observe.py.

The crossing log is built so each observation-table trigger fires once. The clean
log sits on the safe side of every numeric threshold (20 sound events, 20 power
transitions in an hour, one throttled day, a gap that does not touch a throttle
edge) and must not be marked.
"""

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.observe import PHRASE_ORDER, main, render  # noqa: E402

FLAG = "\u2691"


def at(day, hour, minute=0, second=0, millis=0):
    tz = datetime.now().astimezone().tzinfo
    base = datetime(2026, 6, day, hour, minute, second, tzinfo=tz)
    return int(base.timestamp() * 1000) + millis


def ev(ts, event, run="cam", **fields):
    body = {"ts": ts, "run": run, "event": event}
    body.update(fields)
    return body


def beat(ts, temp, pct=80, charging=True, drain=0.0, throttled=False):
    return ev(
        ts,
        "heartbeat",
        battery_temp_c=temp,
        battery_pct=pct,
        charging=charging,
        drain_pct_per_h=drain,
        thermal_throttled=throttled,
    )


def throttle(ts, on, temp, status):
    return ev(
        ts,
        "throttle",
        step="fps",
        state="engage" if on else "release",
        battery_temp_c=temp,
        thermal_status=status,
    )


def write_jsonl(path, rows, raw=()):
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row) + "\n")
        for line in raw:
            handle.write(line + "\n")


def missing_flags(text):
    flagged = "\n".join(line for line in text.splitlines() if FLAG in line)
    return [phrase for phrase in PHRASE_ORDER if phrase not in flagged]


def crossing_rows():
    rows = []
    # Day 1: throttled 7h, temperature flat through the 15-minute window, then
    # a drop at 20 minutes that must not cancel the trigger.
    rows.append(throttle(at(1, 12), True, 40, "light"))
    rows.append(beat(at(1, 12, 5), 40, throttled=True))
    rows.append(beat(at(1, 12, 10), 40, throttled=True))
    rows.append(beat(at(1, 12, 15), 40, throttled=True))
    rows.append(beat(at(1, 12, 20), 30, throttled=True))
    rows.append(throttle(at(1, 19), False, 30, "light"))
    # Day 2: second day over 25%. Temperature does fall, and status is above LIGHT
    # so a single cycle is not the "limit is too low" trigger.
    rows.append(throttle(at(2, 12), True, 37, "moderate"))
    rows.append(beat(at(2, 12, 5), 35, throttled=True))
    rows.append(throttle(at(2, 19), False, 34, "moderate"))
    # Day 3: third consecutive day, six engage/release cycles, all at LIGHT.
    base = at(3, 11)
    for i in range(5):
        rows.append(throttle(base + i * 2000, True, 36, "light"))
        rows.append(throttle(base + i * 2000 + 500, False, 36, "light"))
    release = base + 500
    rows.append(ev(release + 3000, "segment", gap_ms=5000, duration_ms=1000, id="gap"))
    rows.append(throttle(at(3, 12), True, 37, "light"))
    rows.append(beat(at(3, 12, 5), 35, throttled=True))
    rows.append(ev(at(3, 12, 0, 1), "alert", kind="detection", delivered=0))
    rows.append(throttle(at(3, 19), False, 34, "light"))
    # Day 4: power saving, microphone, sound count, spotlight, timers, alert delay.
    due = at(4, 9)
    rows.append(
        ev(
            at(4, 8),
            "recording_timer_set",
            run="aaaa",
            mode="CONTINUOUS",
            revert_to="DETECTION",
            due_at_ms=due,
            via="lan",
        )
    )
    rows.append(
        ev(at(4, 9, 30), "service_start", run="bbbb", reason="system", prev_run_unclean=True)
    )
    rows.append(beat(at(4, 9, 35), 30))
    sound_base = at(4, 10)
    for i in range(21):
        rows.append(ev(sound_base + i * 1000, "sound_event", category="sound", level_db=-20.0))
    rows.append(ev(sound_base + 9500, "audio_listen", state="stop", reason="mic_off"))
    rows.append(ev(sound_base + 10500, "audio_listen", state="start", reason="viewer_live"))
    rows.append(ev(sound_base + 20500, "power_saving", state="release", reason="sound"))
    flap = at(4, 10, 30)
    for i in range(20):
        state = "engage" if i % 2 == 0 else "release"
        reason = "idle" if state == "engage" else "movement"
        rows.append(ev(flap + i * 10, "power_saving", state=state, reason=reason))
    rows.append(ev(flap + 1000, "alert", kind="detection", delivered=0))
    rows.append(ev(at(4, 11), "spotlight", on=False, auto=True, luma=10))
    rows.append(ev(at(4, 11, 30), "spotlight", on=True, auto=True, luma=200))
    rows.append(ev(at(4, 11, 30, 30), "spotlight", on=False, auto=True, luma=200, duration_ms=30000))
    late_due = at(4, 13) - 180_000
    revert = ev(at(4, 13), "recording_revert", due_at_ms=late_due, late_ms=180_000)
    revert["from"] = "CONTINUOUS"
    revert["to"] = "DETECTION"
    rows.append(revert)
    # Day 5: drain estimate 20%/h against a 10% gauge drop.
    rows.append(beat(at(5, 12), 30, pct=80, charging=False, drain=20))
    rows.append(beat(at(5, 13), 30, pct=70, charging=False, drain=20))
    # Day 6: battery axis pauses at 22% with the threshold at 30%.
    battery = ev(
        at(6, 12),
        "health_state",
        axis="battery",
        to="pause",
        battery_pct=22,
        battery_pause_pct=30,
        battery_temp_c=30,
    )
    battery["from"] = "ok"
    rows.append(battery)
    # Day 7: floor not held, and the storage axis never speaks.
    rows.append(ev(at(7, 12), "prune", floor_held=False, free_after_bytes=1, floor_bytes=2))
    # Day 8: storage goes critical with no health alert.
    critical = ev(at(8, 8), "health_state", axis="storage", to="critical", battery_temp_c=30)
    critical["from"] = "warn"
    rows.append(critical)
    # Day 9: heat pause, detection with no clip, temperature falls, no resume.
    rows.append(beat(at(9, 11, 55), 41))
    rows.append(
        ev(
            at(9, 12),
            "recording_cap",
            cap="no_continuous",
            reason="thermal",
            battery_temp_c=42,
            requested_mode="CONTINUOUS",
            effective_mode="DETECTION",
        )
    )
    rows.append(ev(at(9, 12, 1), "alert", kind="detection", delivered=1))
    rows.append(beat(at(9, 12, 20), 39))
    rows.append(beat(at(9, 12, 45), 38))
    return rows


def crossing_viewer():
    return [
        ev(
            at(4, 12),
            "alert",
            run="view",
            kind="health",
            delay_ms=90_000,
            idle=True,
            repeat=False,
        )
    ]


def crossing_raw():
    ts = at(4, 9, 40)
    return [
        "",
        "{",
        "[]",
        '{"event": "heartbeat", "battery_temp_c": 1}',
        '{"ts": "nope", "event": "heartbeat"}',
        f'  {{"seconds": 3, "on": false, "event": "siren", "run": "zz", "ts": {ts}.0}}  ',
    ]


def clean_rows():
    rows = []
    rows.append(ev(at(15, 7), "service_start", reason="manual", prev_run_unclean=False))
    rows.append(ev(at(15, 7, 10), "audio_listen", state="start", reason="viewer_live"))
    rows.append(ev(at(15, 7, 20), "audio_listen", state="stop", reason="service_stop"))
    warn = ev(at(15, 7, 30), "health_state", axis="thermal", to="warn", battery_temp_c=34)
    warn["from"] = "ok"
    rows.append(warn)
    # One throttled day, status above LIGHT, temperature falls inside 15 minutes.
    rows.append(throttle(at(15, 8), True, 36, "moderate"))
    rows.append(beat(at(15, 8, 5), 34, throttled=True))
    rows.append(beat(at(15, 8, 10), 33, throttled=True))
    rows.append(ev(at(15, 9), "segment", gap_ms=5000, duration_ms=1000, id="during"))
    due = at(15, 11)
    rows.append(
        ev(
            at(15, 10),
            "recording_timer_set",
            mode="CONTINUOUS",
            revert_to="DETECTION",
            due_at_ms=due,
            via="lan",
        )
    )
    revert = ev(due + 5000, "recording_revert", due_at_ms=due, late_ms=5000)
    revert["from"] = "CONTINUOUS"
    revert["to"] = "DETECTION"
    rows.append(revert)
    rows.append(ev(due + 10_000, "service_start", run="next", reason="manual", prev_run_unclean=False))
    # Exactly 20 sound events, and a release 40 ms later (inside two idle frames).
    sound_base = at(15, 12)
    for i in range(20):
        rows.append(ev(sound_base + i * 1000, "sound_event", category="sound", level_db=-18.0))
    rows.append(ev(sound_base + 19_000 + 40, "power_saving", state="release", reason="sound"))
    # 19 more transitions: peak in any hour is 20, which is not more than 20.
    flap = at(15, 12, 30)
    for i in range(19):
        state = "engage" if i % 2 == 0 else "release"
        reason = "idle" if state == "engage" else "movement"
        rows.append(ev(flap + i * 10, "power_saving", state=state, reason=reason))
    rows.append(throttle(at(15, 15), False, 33, "moderate"))
    rows.append(beat(at(15, 15, 5), 33, throttled=False))
    rows.append(beat(at(15, 16), 32, pct=80, charging=False, drain=1.0))
    rows.append(beat(at(15, 17), 32, pct=79, charging=False, drain=1.0))
    rows.append(ev(at(15, 18), "alert", kind="detection", delivered=1))
    critical = ev(at(15, 19), "health_state", axis="storage", to="critical", battery_temp_c=32)
    critical["from"] = "ok"
    rows.append(critical)
    rows.append(ev(at(15, 19, 0, 1), "alert", kind="health", delivered=1))
    battery = ev(
        at(15, 19, 30),
        "health_state",
        axis="battery",
        to="pause",
        battery_pct=30,
        battery_pause_pct=30,
        battery_temp_c=32,
    )
    battery["from"] = "ok"
    rows.append(battery)
    rows.append(ev(at(15, 20), "spotlight", on=True, auto=True, luma=20))
    rows.append(ev(at(15, 20, 0, 5), "spotlight", on=False, auto=True, luma=20, duration_ms=5000))
    rows.append(ev(at(15, 20, 30), "prune", floor_held=True, free_after_bytes=100, floor_bytes=50))
    return rows


def clean_viewer():
    return [
        ev(at(15, 18), "alert", run="view", kind="detection", delay_ms=2000, idle=False, repeat=False),
        ev(at(15, 19, 0, 1), "alert", run="view", kind="health", delay_ms=1000, idle=False, repeat=False),
        # A non-alert line must not be read as a slow idle alert.
        ev(at(15, 19, 10), "reconnect", run="view", delay_ms=999_999, idle=True, backoff_ms=500),
    ]


class ObserveReportTest(unittest.TestCase):
    def test_given_log_crossing_every_trigger_when_reported_then_each_flag_appears(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            camera = root / "camera.jsonl"
            viewer = root / "viewer.jsonl"
            write_jsonl(camera, crossing_rows(), crossing_raw())
            write_jsonl(viewer, crossing_viewer())
            text = render(camera, viewer)
        self.assertEqual(missing_flags(text), [])
        self.assertIn("unparsed: 4", text)

    def test_given_clean_log_when_reported_then_no_flag_appears(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            camera = root / "camera.jsonl"
            viewer = root / "viewer.jsonl"
            write_jsonl(camera, clean_rows())
            write_jsonl(viewer, clean_viewer())
            text = render(camera, viewer)
        self.assertNotIn(FLAG, text)
        self.assertIn("heat pauses: 0", text)
        self.assertIn("unparsed: 0", text)
        self.assertIn("of 24h", text)
        self.assertIn("\n  none\n", text)

    def test_given_mostly_null_fields_when_reported_then_counts_shown_and_no_flag(self):
        rows = []
        base = at(20, 12)
        for i in range(6):
            rows.append(ev(base + i * 2000, "throttle", step="fps", state="engage"))
            rows.append(ev(base + i * 2000 + 500, "throttle", step="fps", state="release"))
        for _ in range(9):
            rows.append(ev(base + 60_000, "prune", free_after_bytes=1))
        rows.append(ev(base + 61_000, "prune", floor_held=False, free_after_bytes=1))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "camera.jsonl"
            write_jsonl(path, rows, ["{", "[]"])
            text = render(path)
        self.assertNotIn(FLAG, text)
        self.assertIn("cycles: 6", text)
        self.assertIn("throttle.thermal_status: missing 12/12 (100%) incomplete", text)
        self.assertIn("prune.floor_held: missing 9/10 (90%) incomplete", text)
        self.assertIn("unparsed: 2", text)

    def test_given_empty_log_when_reported_then_no_flag(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "camera.jsonl"
            path.write_text("", encoding="utf-8")
            text = render(path)
        self.assertIn("parsed: 0", text)
        self.assertIn("unparsed: 0", text)
        self.assertNotIn(FLAG, text)

    def test_given_missing_file_when_main_then_message_on_stdout(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = main(["/tmp/homecam-observe-missing.jsonl"])
        self.assertEqual(code, 1)
        self.assertIn("no such file", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
