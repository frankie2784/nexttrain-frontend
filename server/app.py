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


@app.route("/departures")
def departures():
    stop_id = request.args.get("stop_id")
    if stop_id is None:
        return jsonify({"error": "stop_id is required"}), 400

    direction_id_raw = request.args.get("direction_id")
    direction_id = int(direction_id_raw) if direction_id_raw is not None else None
    max_results = int(request.args.get("max_results", "5"))

    if gtfs_static.last_updated is None:
        return jsonify({"error": "GTFS static data not loaded yet"}), 503

    now = datetime.now()
    scheduled = gtfs_static.departures_from_stop(
        stop_id=stop_id,
        direction_id=direction_id,
        max_results=max_results,
        reference_time=now,
    )

    results = []
    for dep in scheduled:
        trip_id = dep["trip_id"]
        # Try matching on any stop_id variant for this trip+stop
        delay_seconds = gtfs_rt.get_delay(trip_id, stop_id)
        if delay_seconds is None:
            # Try child platform stop IDs
            for sid, srow in gtfs_static.stops.items():
                if srow.get("parent_station") == str(stop_id):
                    delay_seconds = gtfs_rt.get_delay(trip_id, sid)
                    if delay_seconds is not None:
                        break

        delay_minutes = round(delay_seconds / 60) if delay_seconds is not None else 0

        # Convert HH:MM:SS to HH:MM for display
        dep_time = dep["departure_time"]
        display_time = dep_time[:5] if len(dep_time) >= 5 else dep_time

        # Compute estimated time if delayed
        estimated_time = None
        if delay_seconds and delay_seconds != 0:
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
            "platform": None,  # GTFS-RT doesn't reliably provide this
        })

    return jsonify({
        "stop_id": stop_id,
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
