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
from datetime import datetime, timedelta
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


@app.route("/reachable_destinations")
def reachable_destinations():
    """Return stations reachable from the given origin on the same line(s)."""
    stop_id = request.args.get("stop_id")
    if not stop_id:
        return jsonify({"error": "stop_id is required"}), 400

    if gtfs_static.last_updated is None:
        return jsonify({"error": "GTFS static data not loaded yet"}), 503

    stations = gtfs_static.reachable_destinations(stop_id)
    return jsonify({
        "origin_stop_id": stop_id,
        "stations": stations,
        "count": len(stations),
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

        # Minutes until departure, including cross-midnight services.
        try:
            base_ts = dep.get("departure_timestamp")
            if base_ts is not None:
                dep_dt = datetime.fromtimestamp(base_ts, LOCAL_TZ)
            else:
                h, m, s = (int(x) for x in dep_time.split(":"))
                service_date_raw = dep.get("service_date")
                service_day = (
                    datetime.fromisoformat(service_date_raw).date()
                    if service_date_raw else now.date()
                )
                dep_dt = datetime.combine(service_day, datetime.min.time(), tzinfo=LOCAL_TZ)
                dep_dt = dep_dt + timedelta(hours=h, minutes=m, seconds=s)

            if delay_seconds is not None:
                dep_dt = dep_dt + timedelta(seconds=delay_seconds)

            # Static data is fetched with a generous look-back buffer so that
            # RT-delayed trains aren't discarded too early.  Drop any trip
            # whose effective departure time (schedule + RT delay) has already
            # passed — it has genuinely departed and should not be shown.
            if dep_dt < now:
                continue

            minutes_until = int((dep_dt - now).total_seconds() // 60)
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
    scheduler.add_job(refresh_realtime, "interval", seconds=15, id="gtfs_rt")
    scheduler.add_job(refresh_static, "interval", days=7, id="gtfs_static")
    # Retry static load every 5 minutes if it hasn't loaded yet
    scheduler.add_job(
        _retry_static_if_needed, "interval", minutes=5, id="gtfs_static_retry"
    )
    scheduler.start()
    logger.info("Scheduler started: RT every 15s, static every 7d")


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
