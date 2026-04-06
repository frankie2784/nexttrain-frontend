"""
Next Train GTFS Server.

Runs on your RPi4 and exposes a REST API that the Android widget calls
once per minute. All GTFS processing (static + real-time) happens here.

Endpoints
---------
GET /departures?stop_id=<int>&direction_id=<int>&max_results=<int>
    Returns upcoming departures with real-time delay merged in.

GET /health
    Simple health-check.

Configuration
-------------
Set environment variables (or create a .env file next to this script):
    PTV_API_KEY   – Your PTV / Open Data API key (used for GTFS-RT header)
    PORT          – Server port (default 5050)
"""

import logging
import os
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler
from flask import Flask, jsonify, request

from gtfs_realtime import gtfs_rt
from gtfs_static import gtfs_static

# ── Logging ────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# ── Config ─────────────────────────────────────────────────────────────────

API_KEY = os.environ.get("PTV_API_KEY", "")
PORT = int(os.environ.get("PORT", "5050"))
LOCAL_TZ = ZoneInfo("Australia/Melbourne")

if not API_KEY:
    logger.warning("PTV_API_KEY not set — GTFS-RT delays will be unavailable")

# ── Flask app ──────────────────────────────────────────────────────────────

app = Flask(__name__)


@app.route("/debug/rt/compare")
def debug_rt_compare():
    """Compare RT absolute departure times vs static scheduled times for HBE trips."""
    import requests as req
    from google.transit.gtfs_realtime_pb2 import FeedMessage as FM
    try:
        resp = req.get(
            "https://api.opendata.transport.vic.gov.au/"
            "opendata/public-transport/gtfs/realtime/v1/metro/trip-updates",
            headers={"KeyID": API_KEY},
            timeout=15,
        )
        resp.raise_for_status()
        feed = FM()
        feed.ParseFromString(resp.content)

        comparisons = []
        for entity in feed.entity:
            if not entity.HasField("trip_update"):
                continue
            tu = entity.trip_update
            tid = tu.trip.trip_id
            if "HBE" not in tid:
                continue
            # Get static stop_times for this trip
            static_times = gtfs_static.stop_times_by_trip.get(tid, [])
            static_by_stop = {str(st["stop_id"]): st for st in static_times}

            for stu in tu.stop_time_update:
                sid = stu.stop_id
                static_st = static_by_stop.get(sid)
                if not static_st:
                    continue
                sched_dep = static_st.get("departure_time", "")
                rt_dep_time = stu.departure.time if stu.HasField("departure") else 0
                rt_dep_delay = stu.departure.delay if stu.HasField("departure") else None

                if rt_dep_time and sched_dep:
                    # Convert scheduled HH:MM:SS to unix timestamp for today
                    rt_dt = datetime.fromtimestamp(rt_dep_time, LOCAL_TZ)
                    try:
                        h, m, s = (int(x) for x in sched_dep.split(":"))
                        sched_dt = rt_dt.replace(hour=h % 24, minute=m, second=s)
                        diff_seconds = int((rt_dt - sched_dt).total_seconds())
                    except Exception:
                        diff_seconds = None

                    comparisons.append({
                        "trip_id": tid,
                        "stop_id": sid,
                        "stop_name": gtfs_static.stops.get(sid, {}).get("stop_name", "?"),
                        "scheduled": sched_dep,
                        "rt_absolute": rt_dt.strftime("%H:%M:%S"),
                        "rt_delay_field": rt_dep_delay,
                        "computed_diff_seconds": diff_seconds,
                    })

        # Sort by abs diff
        comparisons.sort(key=lambda x: abs(x.get("computed_diff_seconds") or 0), reverse=True)

        return jsonify({
            "total_comparisons": len(comparisons),
            "with_nonzero_diff": sum(1 for c in comparisons if c.get("computed_diff_seconds", 0) != 0),
            "comparisons": comparisons[:50],
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/debug/rt/raw")
def debug_rt_raw():
    """Fetch GTFS-RT feed directly and dump raw fields for HBE trips."""
    import requests as req
    from google.transit.gtfs_realtime_pb2 import FeedMessage as FM
    try:
        resp = req.get(
            "https://api.opendata.transport.vic.gov.au/"
            "opendata/public-transport/gtfs/realtime/v1/metro/trip-updates",
            headers={"KeyID": API_KEY},
            timeout=15,
        )
        resp.raise_for_status()
        feed = FM()
        feed.ParseFromString(resp.content)

        hbe_entities = []
        all_has_delay = 0
        all_has_time = 0
        all_stop_updates = 0
        for entity in feed.entity:
            if not entity.HasField("trip_update"):
                continue
            tu = entity.trip_update
            tid = tu.trip.trip_id
            for stu in tu.stop_time_update:
                all_stop_updates += 1
                has_dep_delay = stu.HasField("departure") and stu.departure.delay != 0
                has_dep_time = stu.HasField("departure") and stu.departure.time != 0
                has_arr_delay = stu.HasField("arrival") and stu.arrival.delay != 0
                has_arr_time = stu.HasField("arrival") and stu.arrival.time != 0
                if has_dep_delay or has_arr_delay:
                    all_has_delay += 1
                if has_dep_time or has_arr_time:
                    all_has_time += 1

            if "HBE" not in tid:
                continue
            stops_detail = []
            for stu in tu.stop_time_update:
                detail = {"stop_id": stu.stop_id, "stop_sequence": stu.stop_sequence}
                if stu.HasField("departure"):
                    detail["dep_delay"] = stu.departure.delay
                    detail["dep_time"] = stu.departure.time
                    detail["dep_uncertainty"] = stu.departure.uncertainty
                if stu.HasField("arrival"):
                    detail["arr_delay"] = stu.arrival.delay
                    detail["arr_time"] = stu.arrival.time
                    detail["arr_uncertainty"] = stu.arrival.uncertainty
                stops_detail.append(detail)
            hbe_entities.append({
                "trip_id": tid,
                "schedule_relationship": tu.trip.schedule_relationship,
                "start_date": tu.trip.start_date,
                "route_id": tu.trip.route_id,
                "stop_time_updates": stops_detail,
            })

        return jsonify({
            "feed_entities": len(feed.entity),
            "total_stop_time_updates": all_stop_updates,
            "updates_with_nonzero_delay_field": all_has_delay,
            "updates_with_nonzero_time_field": all_has_time,
            "hbe_trip_count": len(hbe_entities),
            "hbe_trips": hbe_entities[:10],
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/debug/rt/match")
def debug_rt_match():
    """Trace RT matching for a given origin + trip."""
    origin_id = request.args.get("origin_id", "vic:rail:MCD")
    trip_id = request.args.get("trip_id")

    matching_sids = gtfs_static._matching_stop_ids(origin_id)
    stops_info = {
        sid: {
            "name": gtfs_static.stops.get(sid, {}).get("stop_name"),
            "parent": gtfs_static.stops.get(sid, {}).get("parent_station"),
        }
        for sid in matching_sids
    }

    # Check if origin_id is itself in stops dict
    origin_in_stops = origin_id in gtfs_static.stops
    origin_stop_data = gtfs_static.stops.get(origin_id)

    # If trip_id given, find all RT entries for it
    rt_entries_for_trip = {}
    if trip_id:
        with gtfs_rt._lock:
            for (tid, sid), delay in gtfs_rt.delays.items():
                if tid == trip_id:
                    rt_entries_for_trip[sid] = delay

    # Sample of all stop IDs in stops dict
    total_stops = len(gtfs_static.stops)
    sample_stop_ids = list(gtfs_static.stops.keys())[:20]

    return jsonify({
        "origin_id": origin_id,
        "origin_in_stops_dict": origin_in_stops,
        "origin_stop_data": origin_stop_data,
        "matching_stop_ids": list(matching_sids),
        "matching_stops_info": stops_info,
        "total_stops_loaded": total_stops,
        "sample_stop_ids": sample_stop_ids,
        "trip_id": trip_id,
        "rt_entries_for_trip": rt_entries_for_trip,
    })


@app.route("/health")
def health():
    return jsonify({
        "status": "ok",
        "gtfs_static_loaded": gtfs_static.last_updated is not None,
        "gtfs_static_last_updated": (
            gtfs_static.last_updated.isoformat() if gtfs_static.last_updated else None
        ),
        "gtfs_rt_last_fetched": (
            gtfs_rt.last_fetched.isoformat() if gtfs_rt.last_fetched else None
        ),
        "gtfs_rt_delay_count": len(gtfs_rt.delays),
    })


@app.route("/debug/rt")
def debug_rt():
    """Diagnostic: show RT delay stats, trip ID matching, and sample non-zero delay entries."""
    import re

    def timetable_period(trip_id):
        m = re.search(r"--(\d+)-", trip_id)
        return m.group(1) if m else "?"

    delays = dict(gtfs_rt.delays)
    non_zero = {k: v for k, v in delays.items() if v and v != 0}
    top_delayed = sorted(non_zero.items(), key=lambda x: abs(x[1]), reverse=True)[:20]

    rt_trip_ids = {k[0] for k in delays}
    static_trip_ids = set(gtfs_static.trips.keys())
    matched = rt_trip_ids & static_trip_ids

    # Period breakdown
    static_periods: dict[str, int] = {}
    for t in static_trip_ids:
        p = timetable_period(t)
        static_periods[p] = static_periods.get(p, 0) + 1
    rt_periods: dict[str, int] = {}
    for t in rt_trip_ids:
        p = timetable_period(t)
        rt_periods[p] = rt_periods.get(p, 0) + 1

    return jsonify({
        "total_rt_entries": len(delays),
        "non_zero_delay_entries": len(non_zero),
        "cancelled_trips": len(gtfs_rt.cancelled_trips),
        "static_trips": len(static_trip_ids),
        "rt_unique_trips": len(rt_trip_ids),
        "matched_trips": len(matched),
        "static_timetable_periods": static_periods,
        "rt_timetable_periods": rt_periods,
        "sample_matched_trip_ids": list(matched)[:5],
        "sample_unmatched_rt_trip_ids": list(rt_trip_ids - static_trip_ids)[:5],
        "sample_unmatched_static_trip_ids": list(static_trip_ids - rt_trip_ids)[:5],
        "top_delays": [{"trip_id": k[0], "stop_id": k[1], "delay_s": v} for k, v in top_delayed],
        "last_fetched": gtfs_rt.last_fetched.isoformat() if gtfs_rt.last_fetched else None,
    })


@app.route("/stations")
def stations():
    if gtfs_static.last_updated is None:
        return jsonify({"error": "GTFS static data not loaded yet"}), 503

    items = gtfs_static.station_catalog()
    return jsonify({
        "count": len(items),
        "stations": items,
        "timestamp": datetime.now(LOCAL_TZ).isoformat(),
    })


@app.route("/departures")
def departures():
    origin_gtfs_id = request.args.get("origin_gtfs_id")
    stop_id = request.args.get("stop_id")
    origin_id = origin_gtfs_id or stop_id
    if origin_id is None:
        return jsonify({"error": "origin_gtfs_id or stop_id is required"}), 400

    destination_gtfs_id = request.args.get("destination_gtfs_id")
    destination_stop_id = request.args.get("destination_stop_id")
    destination_id = destination_gtfs_id or destination_stop_id
    direction_id_raw = request.args.get("direction_id")
    direction_id = int(direction_id_raw) if direction_id_raw is not None else None
    max_results = int(request.args.get("max_results", "5"))

    if gtfs_static.last_updated is None:
        return jsonify({"error": "GTFS static data not loaded yet"}), 503

    now = datetime.now(LOCAL_TZ)
    # Over-fetch candidates so cancelled services can be dropped while still
    # returning up to max_results running trains.
    candidate_limit = min(max(max_results * 4, max_results + 5), 100)
    scheduled = gtfs_static.departures_from_stop(
        stop_id=origin_id,
        destination_stop_id=destination_id,
        direction_id=direction_id,
        max_results=candidate_limit,
        reference_time=now,
    )

    results = []
    for dep in scheduled:
        trip_id = dep["trip_id"]

        # Hide trips that GTFS-RT explicitly marks as cancelled.
        if gtfs_rt.is_trip_cancelled(trip_id):
            continue

        # Prefer the exact GTFS platform stop_id that produced this departure.
        matched_stop_id = dep.get("stop_id", origin_id)
        delay_seconds = gtfs_rt.get_delay(trip_id, matched_stop_id)
        if delay_seconds is None:
            # Fall back to any resolved stop-id variant for the requested stop.
            for sid in gtfs_static._matching_stop_ids(origin_id):
                delay_seconds = gtfs_rt.get_delay(trip_id, sid)
                if delay_seconds is not None:
                    matched_stop_id = sid
                    break

        # PTV often sets delay=0 but provides the real predicted departure
        # as an absolute unix timestamp.  Derive the true delay from that.
        if delay_seconds is not None:
            rt_dep_ts = gtfs_rt.get_dep_time(trip_id, matched_stop_id)
            if rt_dep_ts:
                dep_time_str = dep["departure_time"]
                try:
                    h, m, s = (int(x) for x in dep_time_str.split(":"))
                    rt_dt = datetime.fromtimestamp(rt_dep_ts, LOCAL_TZ)
                    sched_dt = rt_dt.replace(hour=h % 24, minute=m, second=s)
                    computed = int((rt_dt - sched_dt).total_seconds())
                    # Only override when the absolute time actually disagrees
                    # with the delay field (PTV quirk).
                    if computed != delay_seconds:
                        delay_seconds = computed
                except Exception:
                    pass

        delay_minutes = round(delay_seconds / 60) if delay_seconds is not None else 0

        # Convert HH:MM:SS to HH:MM for display
        dep_time = dep["departure_time"]
        display_time = dep_time[:5] if len(dep_time) >= 5 else dep_time

        # Compute expected real-time departure whenever RT data is available.
        # If RT is missing (None), clients should fall back to scheduled_time.
        estimated_time = None
        if delay_seconds is not None:
            try:
                h, m, s = (int(x) for x in dep_time.split(":"))
                total_s = h * 3600 + m * 60 + s + delay_seconds
                eh = (total_s // 3600) % 24
                em = (total_s % 3600) // 60
                estimated_time = f"{eh:02d}:{em:02d}"
            except Exception:
                pass

        # Minutes until departure
        try:
            h, m, s = (int(x) for x in dep_time.split(":"))
            dep_abs_seconds = h * 3600 + m * 60 + s + (delay_seconds or 0)
            now_seconds = now.hour * 3600 + now.minute * 60 + now.second
            minutes_until = max(0, (dep_abs_seconds - now_seconds) // 60)
        except Exception:
            minutes_until = 0

        results.append({
            "trip_id": trip_id,
            "route_id": dep["route_id"],
            "direction_id": dep["direction_id"],
            "scheduled_time": display_time,
            "estimated_time": estimated_time,
            "delay_minutes": delay_minutes,
            "delay_seconds": delay_seconds,
            "minutes_until": minutes_until,
            "trip_headsign": dep["trip_headsign"],
            "platform": dep.get("platform"),
        })

        if len(results) >= max_results:
            break

    return jsonify({
        "origin_id": origin_id,
        "origin_gtfs_id": origin_gtfs_id,
        "stop_id": stop_id,
        "destination_id": destination_id,
        "destination_gtfs_id": destination_gtfs_id,
        "destination_stop_id": destination_stop_id,
        "departures": results,
        "timestamp": now.isoformat(),
    })


# ── Background scheduler ──────────────────────────────────────────────────

def refresh_realtime():
    """Fetch GTFS-RT every 30 seconds."""
    if API_KEY:
        gtfs_rt.fetch(API_KEY)


def refresh_static():
    """Re-download and reload the static GTFS data."""
    try:
        gtfs_static.download()
        gtfs_static.load()
    except Exception:
        logger.exception("Failed to refresh static GTFS")


# ── Main ───────────────────────────────────────────────────────────────────

_scheduler_started = False

def _init_app():
    """Load data and start scheduler. Safe to call multiple times (gunicorn workers)."""
    global _scheduler_started
    if _scheduler_started:
        return
    _scheduler_started = True

    logger.info("Loading static GTFS data on startup …")
    try:
        gtfs_static.load()
    except Exception:
        logger.exception("Initial GTFS load failed — will retry in background")

    if API_KEY:
        try:
            gtfs_rt.fetch(API_KEY)
        except Exception:
            logger.exception("Initial GTFS-RT fetch failed")

    scheduler = BackgroundScheduler()
    scheduler.add_job(refresh_realtime, "interval", seconds=30, id="gtfs_rt")
    scheduler.add_job(refresh_static, "interval", days=7, id="gtfs_static")
    # Retry static load every 5 minutes if it hasn't loaded yet
    scheduler.add_job(
        _retry_static_if_needed, "interval", minutes=5, id="gtfs_static_retry"
    )
    scheduler.start()
    logger.info("Scheduler started: RT every 30s, static every 7d")


def _retry_static_if_needed():
    """Retry loading static GTFS if it hasn't been loaded yet."""
    if gtfs_static.last_updated is not None:
        return
    logger.info("Retrying static GTFS load …")
    try:
        gtfs_static.download()
        gtfs_static.load()
    except Exception:
        logger.exception("GTFS static retry failed — will try again in 5 min")


# Initialise when imported by gunicorn
_init_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=PORT)
