# NextTrain — Web Frontend

A real-time Melbourne Metro train dashboard hosted on GitHub Pages. Shows network delay trends and per-station departures with live delay info.

**Architecture:** Web frontend + separate [server repo](https://github.com/frankie2784/NextTrain-Server).

---

## Features

- **Network delay sparkline** — 30-minute historical chart showing total network delay, colour-coded (green→amber→red)
- **Per-station departures** — search stations, view next trains with arrival times and delay badges
- **Auto-refresh** — sparkline every 30 s, departures every 60 s
- **Dark theme** — designed for at-a-glance transit info
- **No build step** — vanilla HTML/JS/CSS, deploys instantly to GitHub Pages

---

## Architecture

```
Browser (GitHub Pages)
  │  fetch() + X-Api-Key header
  ▼
Tailscale Funnel (public HTTPS)
  │
  ▼
RPi4 Flask server (port 5050)
```

The web frontend is static — all logic is in the separate [NextTrain-Server](https://github.com/frankie2784/NextTrain-Server) repo.

---

## One-time Setup

### 1. Fork & Clone This Repo

```bash
gh repo fork frankie2784/NextTrain --clone
cd NextTrain
git checkout claude/cloudflare-tunnel-cors  # or merge to main
```

### 2. Set Up the Server (separate repo)

Follow the [NextTrain-Server](https://github.com/frankie2784/NextTrain-Server) README to:
- Install Docker Compose
- Set `PTV_API_KEY` in `.env`
- Run `docker compose up -d`

### 3. Expose via Tailscale Funnel

On the RPi:

```bash
sudo tailscale funnel 5050
# Prints: https://frankipi.tail-abc123.ts.net
```

Copy that URL — you'll need it next.

### 4. Configure GitHub Pages Deployment

Go to **Settings → Secrets and variables → Actions** and add:

| Secret | Value |
|--------|-------|
| `NEXTTRAIN_API_URL` | The Tailscale Funnel URL from step 3 |
| `NEXTTRAIN_API_KEY` | Any long random string (must match `NEXTTRAIN_API_KEY` in the server `.env`) |

### 5. Enable GitHub Pages

Go to **Settings → Pages** and set:
- **Source:** GitHub Actions

### 6. Deploy

Push or merge to `main` — the **Deploy to GitHub Pages** action runs automatically.

Visit `https://<your-github-username>.github.io/NextTrain` — the page loads and calls the API via the Tailscale Funnel.

---

## How It Works

**On page load:**
1. Fetch `/stations` → populate station dropdown
2. Fetch `/delay_history` → draw sparkline
3. On station select → fetch `/departures?origin_gtfs_id=...`

**Auto-refresh:**
- Sparkline: every 30 s
- Departures: every 60 s (if a station is selected)

**Security:**
- All API calls include `X-Api-Key` header
- The server rejects requests without the key (except `/health`)
- Only the API key + Tailscale Funnel protect the backend

---

## Files

```
web/
  └── index.html          Single-file frontend (no npm, no build)
.github/
  └── workflows/
      └── pages.yml       Injects secrets, deploys to GitHub Pages
```

---

## Local Development

Edit `web/index.html` and test in a browser with hardcoded secrets:

```javascript
const API_URL = 'http://localhost:5050';  // or your Tailscale Funnel URL
const API_KEY = 'your-api-key';
```

Then:
```bash
# On RPi
docker compose up -d

# On your machine (if on the same network)
python3 -m http.server 8000 -d web
# Visit http://localhost:8000
```

---

## Server Deployment

See the separate [NextTrain-Server](https://github.com/frankie2784/NextTrain-Server) repo for:
- Flask app + GTFS processing
- Docker Compose setup
- Cloudflare Tunnel alternative (if you prefer)
- Systemd service setup
