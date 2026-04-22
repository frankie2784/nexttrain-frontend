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

## Environment variables

| Variable           | Required | Description |
|--------------------|----------|-------------|
| `PTV_API_KEY`      | Yes      | PTV OpenData API key — required for real-time delays |
| `PORT`             | No       | Server port (default `5050`) |
| `NEXTTRAIN_API_KEY`| No       | Shared secret for the web frontend. If unset the API is open (fine for local/widget-only use). |
| `FRONTEND_ORIGIN`  | No       | CORS origin for the web frontend, e.g. `https://frankie2784.github.io` |

Copy `server/.env.example` to `server/.env` and fill in values before running Docker Compose.

---

## Docker Compose (recommended for production)

```bash
cp .env.example .env
# edit .env — set PTV_API_KEY at minimum
docker compose up -d --build
```

The container exposes port `5050` on the host so the Android widget can still reach it on the local network.

---

## Cloudflare Tunnel (public access for the web frontend)

This lets the web frontend at `https://frankie2784.github.io/NextTrain` reach the RPi API without any port-forwarding. The RPi only makes outbound connections.

### 1. Install cloudflared on the RPi

```bash
curl -L --output cloudflared.deb \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64.deb
sudo dpkg -i cloudflared.deb
```

### 2. Authenticate (one-time — opens a browser tab)

```bash
cloudflared tunnel login
# Saves cert.pem to ~/.cloudflared/
```

### 3. Create the tunnel

```bash
cloudflared tunnel create nexttrain
# Prints a tunnel ID — copy it (looks like: a1b2c3d4-...)
# Saves ~/.cloudflared/<tunnel-id>.json
```

### 4. Create a DNS record

Your domain must be managed by Cloudflare.

```bash
cloudflared tunnel route dns nexttrain api.yourdomain.com
```

### 5. Fill in the config file

Edit `server/cloudflared/config.yml` — replace both `<tunnel-id>` placeholders and the `hostname`:

```yaml
tunnel: a1b2c3d4-...
credentials-file: /root/.cloudflared/a1b2c3d4-....json

ingress:
  - hostname: api.yourdomain.com
    service: http://next-train-server:5050
  - service: http_status:404
```

### 6. Set environment variables in `.env`

```bash
NEXTTRAIN_API_KEY=some-long-random-secret   # also add to GitHub repo secrets
FRONTEND_ORIGIN=https://frankie2784.github.io
```

### 7. Start with the tunnel profile

```bash
docker compose --profile tunnel up -d --build
```

Verify the tunnel is connected:

```bash
docker logs nexttrain-tunnel
# Should show: "Connection established" with 4 connections
```

Test from the internet:

```bash
# Health check — no key required
curl https://api.yourdomain.com/health

# Protected endpoint — must include the key
curl -H "X-Api-Key: your-secret" https://api.yourdomain.com/departures?stop_id=1071
```

---

## GitHub Pages web frontend

The frontend lives in `web/index.html` and deploys automatically via GitHub Actions on every push to `main`.

### One-time setup

1. **Enable GitHub Pages:**
   Go to the repo → **Settings → Pages → Source → GitHub Actions** → Save.

2. **Add repository secrets:**
   Go to **Settings → Secrets and variables → Actions** and add:

   | Secret name          | Value |
   |----------------------|-------|
   | `NEXTTRAIN_API_URL`  | `https://api.yourdomain.com` (your tunnel URL) |
   | `NEXTTRAIN_API_KEY`  | Same value as `NEXTTRAIN_API_KEY` in your RPi `.env` |

3. **Merge to `main`** — the `Deploy to GitHub Pages` Actions job runs automatically.

The page will be live at `https://frankie2784.github.io/NextTrain`.

---

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
