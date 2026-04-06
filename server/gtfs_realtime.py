"""
GTFS Real-time feed poller.

Fetches the Metro trip-updates protobuf feed every ~30 seconds and stores
the latest delay information in memory, keyed by (trip_id, stop_id).
"""

import logging
import threading
from datetime import datetime
from typing import Optional
from zoneinfo import ZoneInfo

import requests
from google.transit.gtfs_realtime_pb2 import FeedMessage

logger = logging.getLogger(__name__)
LOCAL_TZ = ZoneInfo("Australia/Melbourne")

GTFS_RT_URL = (
    "https://api.opendata.transport.vic.gov.au/"
    "opendata/public-transport/gtfs/realtime/v1/metro/trip-updates"
)


class GtfsRealtime:
    """In-memory store for the latest GTFS-RT trip updates."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # (trip_id, stop_id) -> delay_seconds (int or None)
        self.delays: dict[tuple[str, str], int | None] = {}
        # (trip_id, stop_id) -> absolute departure unix timestamp (or None)
        self.dep_times: dict[tuple[str, str], int | None] = {}
        self.cancelled_trips: set[str] = set()
        self.last_fetched: Optional[datetime] = None

    def fetch(self, api_key: str) -> None:
        """Download and parse the protobuf feed."""
        try:
            resp = requests.get(
                GTFS_RT_URL,
                headers={"KeyID": api_key},
                timeout=15,
            )
            resp.raise_for_status()
            feed = FeedMessage()
            feed.ParseFromString(resp.content)

            new_delays: dict[tuple[str, str], int | None] = {}
            new_dep_times: dict[tuple[str, str], int | None] = {}
            new_cancelled_trips: set[str] = set()
            for entity in feed.entity:
                if entity.HasField("trip_update"):
                    tu = entity.trip_update
                    trip_id = tu.trip.trip_id
                    if tu.trip.schedule_relationship == tu.trip.ScheduleRelationship.CANCELED:
                        new_cancelled_trips.add(trip_id)
                    for stu in tu.stop_time_update:
                        stop_id = stu.stop_id
                        key = (trip_id, stop_id)
                        # For departures board, departure delay is usually the best signal.
                        if stu.HasField("departure"):
                            delay = stu.departure.delay
                            dep_time = stu.departure.time if stu.departure.time else None
                        elif stu.HasField("arrival"):
                            delay = stu.arrival.delay
                            dep_time = stu.arrival.time if stu.arrival.time else None
                        else:
                            delay = None
                            dep_time = None
                        new_delays[key] = delay
                        new_dep_times[key] = dep_time

            with self._lock:
                self.delays = new_delays
                self.dep_times = new_dep_times
                self.cancelled_trips = new_cancelled_trips
                self.last_fetched = datetime.now(LOCAL_TZ)

            logger.debug("GTFS-RT updated: %d stop-time delays", len(new_delays))
        except Exception:
            logger.exception("Failed to fetch GTFS-RT feed")

    def get_delay(self, trip_id: str, stop_id: str) -> int | None:
        """Return delay in seconds for a (trip_id, stop_id) pair, or None."""
        with self._lock:
            return self.delays.get((trip_id, stop_id))

    def get_dep_time(self, trip_id: str, stop_id: str) -> int | None:
        """Return the absolute departure unix timestamp, or None."""
        with self._lock:
            return self.dep_times.get((trip_id, stop_id))

    def is_trip_cancelled(self, trip_id: str) -> bool:
        """Return True if the trip is marked cancelled in GTFS-RT."""
        with self._lock:
            return trip_id in self.cancelled_trips


# Singleton
gtfs_rt = GtfsRealtime()
