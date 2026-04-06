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
import re
import zipfile
from datetime import date, datetime, timedelta
from threading import Lock
from typing import Optional
from zoneinfo import ZoneInfo

import requests

logger = logging.getLogger(__name__)

GTFS_ZIP_URL = (
    "https://opendata.transport.vic.gov.au/dataset/"
    "3f4e292e-7f8a-4ffe-831f-1953be0fe448/resource/"
    "fb152201-859f-4882-9206-b768060b50ad/download/gtfs.zip"
)
GTFS_CACHE_PATH = os.path.join(os.path.dirname(__file__), "data", "gtfs.zip")
ROUTE_TYPE_TRAIN = "2"  # GTFS route_type for rail
LOCAL_TZ = ZoneInfo("Australia/Melbourne")


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
        """Parse the GTFS ZIP into memory. Thread-safe. Handles nested google_transit.zip files."""
        path = zip_path or GTFS_CACHE_PATH
        need_download = False
        if os.path.isfile(path):
            mtime = os.path.getmtime(path)
            age_days = (datetime.now() - datetime.fromtimestamp(mtime)).days
            if age_days >= 7:
                logger.info(f"GTFS ZIP is {age_days} days old, will download a fresh copy.")
                need_download = True
        else:
            logger.warning("No GTFS ZIP found at %s — downloading", path)
            need_download = True

        if need_download:
            self.download()

        logger.info("Parsing GTFS static data from %s …", path)

        with self._lock, zipfile.ZipFile(path) as outer_zip:
            # Always use the google_transit.zip inside the '2/' folder
            target = '2/google_transit.zip'
            if target not in outer_zip.namelist():
                logger.error(f"Expected {target} not found in GTFS bundle!")
                return
            logger.info(f"Extracting nested GTFS: {target}")
            with outer_zip.open(target) as nested_zip_bytes:
                with zipfile.ZipFile(io.BytesIO(nested_zip_bytes.read())) as zf:
                    self._load_routes(zf)
                    # Accept both '2' (GTFS standard) and '400' (PTV) as train route_type
                    train_route_ids = {r for r, v in self.routes.items() if v["route_type"] in (ROUTE_TYPE_TRAIN, "400")}
                    self._load_trips(zf, train_route_ids)
                    train_trip_ids = set(self.trips.keys())
                    self._load_stops(zf)
                    self._load_calendar(zf)
                    self._load_calendar_dates(zf)
                    self._load_stop_times(zf, train_trip_ids)
                    self.last_updated = datetime.now(LOCAL_TZ)

        logger.info(
            "GTFS loaded: %d routes, %d trips, %d stops, %d stop_time rows",
            len(self.routes), len(self.trips), len(self.stops),
            sum(len(v) for v in self.stop_times_by_trip.values()),
        )

    def _read_csv(self, zf: zipfile.ZipFile, name: str):
        # Match any file whose path ends with the required filename (case-insensitive, any subfolder)
        name = name.lower()
        matching = [n for n in zf.namelist() if n.lower().replace('\\', '/').endswith('/' + name) or n.lower().replace('\\', '/').endswith(name)]
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

    @staticmethod
    def _stop_url_public_id(stop_url: str) -> str | None:
        """Extract the public numeric stop ID from a stop_url, if present."""
        m = re.search(r"/stop/(\d+)/", stop_url or "")
        return m.group(1) if m else None

    def _matching_stop_ids(self, stop_id: str) -> set[str]:
        """Resolve an app/public stop ID or GTFS stop ID to all matching platform stop_ids."""
        matching_stop_ids = set()
        str_stop = str(stop_id)
        if str_stop in self.stops:
            matching_stop_ids.add(str_stop)

        for sid, srow in self.stops.items():
            if self._stop_url_public_id(srow.get("stop_url", "")) == str_stop:
                matching_stop_ids.add(sid)

        for sid, srow in self.stops.items():
            if srow.get("parent_station") == str_stop:
                matching_stop_ids.add(sid)

        parent_stations = {
            self.stops[sid].get("parent_station")
            for sid in matching_stop_ids
            if self.stops.get(sid, {}).get("parent_station")
        }
        for sid, srow in self.stops.items():
            if srow.get("parent_station") in parent_stations:
                matching_stop_ids.add(sid)

        return matching_stop_ids

    def _public_stop_id_for_station(self, station_id: str) -> str | None:
        """Return a best-effort public numeric stop id for a GTFS station id."""
        station_row = self.stops.get(station_id, {})
        direct = self._stop_url_public_id(station_row.get("stop_url", ""))
        if direct:
            return direct

        for sid, srow in self.stops.items():
            if srow.get("parent_station") == station_id:
                public_id = self._stop_url_public_id(srow.get("stop_url", ""))
                if public_id:
                    return public_id
        return None

    def station_catalog(self) -> list[dict]:
        """Return train stations from loaded GTFS using parent station ids as stable keys."""
        stations: list[dict] = []
        for sid, srow in self.stops.items():
            if srow.get("location_type") != "1":
                continue

            platform_ids = sorted(
                child_id
                for child_id, child_row in self.stops.items()
                if child_row.get("parent_station") == sid
            )

            if not platform_ids:
                continue

            stations.append({
                "station_gtfs_id": sid,
                "display_name": (srow.get("stop_name") or "").replace(" Railway Station", "").strip(),
                "public_stop_id": self._public_stop_id_for_station(sid),
                "platform_stop_ids": platform_ids,
            })

        stations.sort(key=lambda s: s["display_name"])
        return stations

    def reachable_destinations(self, stop_id: str) -> list[dict]:
        """Return all stations reachable from *stop_id* on the same line(s).

        1. Resolve stop_id to all matching platform IDs.
        2. Find every route_id that has a trip visiting any of those IDs.
        3. Collect every stop on those routes, resolve to parent stations.
        4. Return a de-duplicated, sorted list of
           {station_gtfs_id, display_name, public_stop_id}.
        """
        matching = self._matching_stop_ids(stop_id)
        if not matching:
            return []

        # Collect route_ids serving this stop
        route_ids: set[str] = set()
        for sid in matching:
            for st_row in self.stop_times_by_stop.get(sid, []):
                trip = self.trips.get(st_row["trip_id"])
                if trip:
                    route_ids.add(trip["route_id"])

        if not route_ids:
            return []

        # Collect all stop_ids on those routes (via trips → stop_times)
        all_stop_ids: set[str] = set()
        for trip_id, trip in self.trips.items():
            if trip["route_id"] in route_ids:
                for st_row in self.stop_times_by_trip.get(trip_id, []):
                    all_stop_ids.add(st_row["stop_id"])

        # Resolve to parent stations
        parent_ids: set[str] = set()
        for sid in all_stop_ids:
            parent = self.stops.get(sid, {}).get("parent_station")
            parent_ids.add(parent if parent else sid)

        # Build output list (only real parent stations with platforms)
        results: list[dict] = []
        for pid in parent_ids:
            srow = self.stops.get(pid)
            if srow is None:
                continue
            display = (srow.get("stop_name") or "").replace(" Railway Station", "").strip()
            if not display:
                continue
            results.append({
                "station_gtfs_id": pid,
                "display_name": display,
                "public_stop_id": self._public_stop_id_for_station(pid),
            })

        results.sort(key=lambda s: s["display_name"])
        return results

    def departures_from_stop(
        self,
        stop_id: str,
        destination_stop_id: str | None = None,
        direction_id: int | None = None,
        max_results: int = 10,
        reference_time: datetime | None = None,
    ) -> list[dict]:
        """
        Return upcoming scheduled departures from a stop.

        Each result: {trip_id, route_id, direction_id, departure_time,
                      arrival_time, stop_sequence, trip_headsign}
        """
        now = reference_time or datetime.now(LOCAL_TZ)
        today = now.date()

        def _time_to_seconds(value: str) -> int | None:
            try:
                h, m, s = (int(x) for x in value.split(":"))
                return h * 3600 + m * 60 + s
            except Exception:
                return None

        now_seconds = now.hour * 3600 + now.minute * 60 + now.second

        matching_stop_ids = self._matching_stop_ids(stop_id)
        destination_stop_ids = (
            self._matching_stop_ids(destination_stop_id)
            if destination_stop_id is not None else set()
        )

        results = []
        for sid in matching_stop_ids:
            for st_row in self.stop_times_by_stop.get(sid, []):
                trip_id = st_row["trip_id"]
                trip = self.trips.get(trip_id)
                if trip is None:
                    continue

                origin_sequence = int(st_row["stop_sequence"])

                if destination_stop_ids:
                    trip_stop_times = self.stop_times_by_trip.get(trip_id, [])
                    reaches_destination = any(
                        row["stop_id"] in destination_stop_ids
                        and int(row["stop_sequence"]) > origin_sequence
                        for row in trip_stop_times
                    )
                    if not reaches_destination:
                        continue

                # Direction filter
                if direction_id is not None and direction_id >= 0:
                    if trip.get("direction_id") != str(direction_id):
                        continue

                dep_time = st_row.get("departure_time", "")
                dep_seconds = _time_to_seconds(dep_time)
                if dep_seconds is None:
                    continue

                # Include departures from today and tomorrow so late-night widgets
                # can still show first morning services when only a few trains remain.
                for day_offset in (0, 1):
                    service_day = today + timedelta(days=day_offset)

                    if not self.service_runs_on(trip["service_id"], service_day):
                        continue

                    if day_offset == 0 and dep_seconds < now_seconds:
                        continue

                    dep_dt = datetime.combine(service_day, datetime.min.time(), tzinfo=LOCAL_TZ) + timedelta(seconds=dep_seconds)

                    results.append({
                        "trip_id": trip_id,
                        "route_id": trip["route_id"],
                        "direction_id": trip.get("direction_id", ""),
                        "stop_id": sid,
                        "platform": self.stops.get(sid, {}).get("platform_code"),
                        "departure_time": dep_time,
                        "arrival_time": st_row.get("arrival_time", dep_time),
                        "stop_sequence": origin_sequence,
                        "trip_headsign": trip.get("trip_headsign", ""),
                        "service_date": service_day.isoformat(),
                        "departure_timestamp": int(dep_dt.timestamp()),
                    })

        results.sort(key=lambda r: r.get("departure_timestamp", 0))
        return results[:max_results]


# Singleton
gtfs_static = GtfsStatic()
