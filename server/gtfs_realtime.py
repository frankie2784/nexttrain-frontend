"""
GTFS Real-time feed poller.

Fetches the Metro trip-updates protobuf feed every ~30 seconds and stores
the latest delay information in memory, keyed by (trip_id, stop_id).
"""

import logging
import threading
from datetime import datetime
from typing import Optional

import requests
from google.transit.gtfs_realtime_pb2 import FeedMessage

logger = logging.getLogger(__name__)

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
            for entity in feed.entity:
                if entity.HasField("trip_update"):
                    tu = entity.trip_update
                    trip_id = tu.trip.trip_id
                    for stu in tu.stop_time_update:
                        stop_id = stu.stop_id
                        delay = stu.arrival.delay if stu.HasField("arrival") else None
                        new_delays[(trip_id, stop_id)] = delay

            with self._lock:
                self.delays = new_delays
                self.last_fetched = datetime.now()

            logger.debug("GTFS-RT updated: %d stop-time delays", len(new_delays))
        except Exception:
            logger.exception("Failed to fetch GTFS-RT feed")

    def get_delay(self, trip_id: str, stop_id: str) -> int | None:
        """Return delay in seconds for a (trip_id, stop_id) pair, or None."""
        with self._lock:
            return self.delays.get((trip_id, stop_id))


# Singleton
gtfs_rt = GtfsRealtime()
