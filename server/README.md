# Next Train GTFS Server – RPi4

Lightweight Python server that handles all GTFS processing and exposes
a simple REST API for the Android widget.

## Setup (Raspberry Pi 4)

```bash
cd server
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

## Configuration

Set your PTV API key as an environment variable:

```bash
export PTV_API_KEY="your-api-key-here"
export PORT=5050   # optional, default 5050
```

## Running

```bash
# Development
python app.py

# Production (recommended)
gunicorn -w 2 -b 0.0.0.0:5050 app:app
```

The first startup downloads the ~230 MB GTFS static ZIP and parses it.
Subsequent startups reuse the cached file at `server/data/gtfs.zip`.

## Background jobs

| Job              | Interval   | Description                        |
|------------------|------------|------------------------------------|
| GTFS real-time   | 30 seconds | Fetches protobuf trip-updates feed |
| GTFS static      | 7 days     | Re-downloads the full timetable    |

## API

### `GET /departures`

| Param          | Required | Default | Description                     |
|----------------|----------|---------|---------------------------------|
| `stop_id`      | yes      |         | PTV stop ID                     |
| `direction_id` | no       | (all)   | Filter by direction             |
| `max_results`  | no       | 5       | Maximum departures to return    |

**Response:**

```json
{
  "stop_id": "1071",
  "departures": [
    {
      "trip_id": "...",
      "route_id": "...",
      "direction_id": "0",
      "scheduled_time": "08:15",
      "estimated_time": "08:17",
      "delay_minutes": 2,
      "delay_seconds": 120,
      "minutes_until": 5,
      "trip_headsign": "Flinders Street",
      "platform": null
    }
  ],
  "timestamp": "2026-03-31T08:10:23.456789"
}
```

### `GET /health`

Returns server status, data freshness, and delay count.

## Running as a systemd service

```bash
sudo nano /etc/systemd/system/next-train.service
```

```ini
[Unit]
Description=Next Train GTFS Server
After=network.target

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi/train_widget/server
Environment=PTV_API_KEY=your-key-here
ExecStart=/home/pi/train_widget/server/venv/bin/gunicorn -w 2 -b 0.0.0.0:5050 app:app
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable next-train
sudo systemctl start next-train
```
