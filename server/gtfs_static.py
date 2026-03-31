"""
GTFS Static Timetable Manager.

Downloads the Victoria GTFS static ZIP weekly and parses the CSV files
needed for departure lookups: trips.txt, stop_times.txt, calendar.txt,
calendar_dates.txt, routes.txt, stops.txt.

Data is held in memory as dicts/lists for fast lookup.
"""

import csv
import io
import logging
import os
import zipfile
from datetime import date, datetime, timedelta
from threading import Lock
from typing import Optional

import requests

logger = logging.getLogger(__name__)

GTFS_ZIP_URL = (
    "https://opendata.transport.vic.gov.au/dataset/"
    "3f4e292e-7f8a-4ffe-831f-1953be0fe448/resource/"
    "fb152201-859f-4882-9206-b768060b50ad/download/gtfs.zip"
)
GTFS_CACHE_PATH = os.path.join(os.path.dirname(__file__), "data", "gtfs.zip")
ROUTE_TYPE_TRAIN = "2"  # GTFS route_type for rail


class GtfsStatic:
    """In-memory GTFS static schedule for Metro trains."""

    def __init__(self) -> None:
        self._lock = Lock()
        # Keyed data
        self.routes: dict[str, dict] = {}          # route_id -> row
        self.trips: dict[str, dict] = {}            # trip_id -> row
        self.stops: dict[str, dict] = {}            # stop_id -> row
        self.calendar: dict[str, dict] = {}         # service_id -> row
        self.calendar_dates: dict[str, list] = {}   # service_id -> [rows]
        # stop_times grouped by trip
        self.stop_times_by_trip: dict[str, list] = {}   # trip_id -> sorted [rows]
        # Reverse index: stop_id -> list of (trip_id, stop_sequence, arrival, departure)
        self.stop_times_by_stop: dict[str, list] = {}
        self.last_updated: Optional[datetime] = None

    # ── Download ───────────────────────────────────────────────────────

    def download(self) -> None:
        """Download the latest GTFS ZIP to disk."""
        logger.info("Downloading GTFS static ZIP from %s …", GTFS_ZIP_URL)
        os.makedirs(os.path.dirname(GTFS_CACHE_PATH), exist_ok=True)
        resp = requests.get(GTFS_ZIP_URL, timeout=300, stream=True)
        resp.raise_for_status()
        with open(GTFS_CACHE_PATH, "wb") as f:
            for chunk in resp.iter_content(chunk_size=1 << 20):
                f.write(chunk)
        logger.info("GTFS static ZIP saved to %s", GTFS_CACHE_PATH)

    # ── Parse ──────────────────────────────────────────────────────────

    def load(self, zip_path: str | None = None) -> None:
        """Parse the GTFS ZIP into memory. Thread-safe."""
        path = zip_path or GTFS_CACHE_PATH
        if not os.path.isfile(path):
            logger.warning("No GTFS ZIP found at %s — downloading", path)
            self.download()

        logger.info("Parsing GTFS static data from %s …", path)
        with self._lock, zipfile.ZipFile(path) as zf:
            self._load_routes(zf)
            train_route_ids = {r for r, v in self.routes.items() if v["route_type"] == ROUTE_TYPE_TRAIN}
            self._load_trips(zf, train_route_ids)
            train_trip_ids = set(self.trips.keys())
            self._load_stops(zf)
            self._load_calendar(zf)
            self._load_calendar_dates(zf)
            self._load_stop_times(zf, train_trip_ids)
            self.last_updated = datetime.now()

        logger.info(
            "GTFS loaded: %d routes, %d trips, %d stops, %d stop_time rows",
            len(self.routes), len(self.trips), len(self.stops),
            sum(len(v) for v in self.stop_times_by_trip.values()),
        )

    def _read_csv(self, zf: zipfile.ZipFile, name: str):
        # Handle nested directories (some GTFS ZIPs nest files inside a folder)
        matching = [n for n in zf.namelist() if n.endswith(name)]
        if not matching:
            logger.warning("File %s not found in ZIP", name)
            return []
        with zf.open(matching[0]) as f:
            text = io.TextIOWrapper(f, encoding="utf-8-sig")
            return list(csv.DictReader(text))

    def _load_routes(self, zf: zipfile.ZipFile) -> None:
        self.routes = {}
        for row in self._read_csv(zf, "routes.txt"):
            self.routes[row["route_id"]] = row

    def _load_trips(self, zf: zipfile.ZipFile, route_ids: set[str]) -> None:
        self.trips = {}
        for row in self._read_csv(zf, "trips.txt"):
            if row["route_id"] in route_ids:
                self.trips[row["trip_id"]] = row

    def _load_stops(self, zf: zipfile.ZipFile) -> None:
        self.stops = {}
        for row in self._read_csv(zf, "stops.txt"):
            self.stops[row["stop_id"]] = row

    def _load_calendar(self, zf: zipfile.ZipFile) -> None:
        self.calendar = {}
        for row in self._read_csv(zf, "calendar.txt"):
            self.calendar[row["service_id"]] = row

    def _load_calendar_dates(self, zf: zipfile.ZipFile) -> None:
        self.calendar_dates = {}
        for row in self._read_csv(zf, "calendar_dates.txt"):
            sid = row["service_id"]
            self.calendar_dates.setdefault(sid, []).append(row)

    def _load_stop_times(self, zf: zipfile.ZipFile, trip_ids: set[str]) -> None:
        self.stop_times_by_trip = {}
        self.stop_times_by_stop = {}
        for row in self._read_csv(zf, "stop_times.txt"):
            tid = row["trip_id"]
            if tid not in trip_ids:
                continue
            self.stop_times_by_trip.setdefault(tid, []).append(row)
            sid = row["stop_id"]
            self.stop_times_by_stop.setdefault(sid, []).append(row)
        # Sort each trip's stop_times by sequence
        for tid in self.stop_times_by_trip:
            self.stop_times_by_trip[tid].sort(key=lambda r: int(r["stop_sequence"]))

    # ── Service day helpers ────────────────────────────────────────────

    _DAY_COLS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

    def service_runs_on(self, service_id: str, d: date) -> bool:
        """Return True if the given service_id is active on date d."""
        # Check calendar_dates exceptions first
        for exc in self.calendar_dates.get(service_id, []):
            exc_date = datetime.strptime(exc["date"], "%Y%m%d").date()
            if exc_date == d:
                return exc["exception_type"] == "1"  # 1=added, 2=removed

        cal = self.calendar.get(service_id)
        if cal is None:
            return False

        start = datetime.strptime(cal["start_date"], "%Y%m%d").date()
        end = datetime.strptime(cal["end_date"], "%Y%m%d").date()
        if not (start <= d <= end):
            return False

        day_col = self._DAY_COLS[d.weekday()]
        return cal.get(day_col) == "1"

    # ── Query ──────────────────────────────────────────────────────────

    def departures_from_stop(
        self,
        stop_id: str,
        direction_id: int | None = None,
        max_results: int = 10,
        reference_time: datetime | None = None,
    ) -> list[dict]:
        """
        Return upcoming scheduled departures from a stop.

        Each result: {trip_id, route_id, direction_id, departure_time,
                      arrival_time, stop_sequence, trip_headsign}
        """
        now = reference_time or datetime.now()
        today = now.date()
        current_time = now.strftime("%H:%M:%S")

        # GTFS stop_ids are strings; the Android app uses int PTV stop IDs.
        # We search for both the raw stop_id and children with matching parent_station.
        matching_stop_ids = set()
        str_stop = str(stop_id)
        if str_stop in self.stops:
            matching_stop_ids.add(str_stop)
        # Also include child platforms whose parent_station matches
        for sid, srow in self.stops.items():
            if srow.get("parent_station") == str_stop:
                matching_stop_ids.add(sid)

        results = []
        for sid in matching_stop_ids:
            for st_row in self.stop_times_by_stop.get(sid, []):
                trip_id = st_row["trip_id"]
                trip = self.trips.get(trip_id)
                if trip is None:
                    continue

                # Direction filter
                if direction_id is not None and direction_id >= 0:
                    if trip.get("direction_id") != str(direction_id):
                        continue

                # Service day check
                if not self.service_runs_on(trip["service_id"], today):
                    continue

                dep_time = st_row.get("departure_time", "")
                if dep_time < current_time:
                    continue

                results.append({
                    "trip_id": trip_id,
                    "route_id": trip["route_id"],
                    "direction_id": trip.get("direction_id", ""),
                    "departure_time": dep_time,
                    "arrival_time": st_row.get("arrival_time", dep_time),
                    "stop_sequence": int(st_row["stop_sequence"]),
                    "trip_headsign": trip.get("trip_headsign", ""),
                })

        results.sort(key=lambda r: r["departure_time"])
        return results[:max_results]


# Singleton
gtfs_static = GtfsStatic()
