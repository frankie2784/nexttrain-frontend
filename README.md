# Next Train – Melbourne Lock Screen Widget

An Android home/lock screen widget for Pixel 6 (Android 13+) that shows real-time
Melbourne Metro train departures using the PTV Timetable API v3.

---

## Features

- **Next 3 departures** from your chosen origin station
- **Multiple OD pairs** — e.g. inbound in the morning, outbound in the afternoon
- **Active time windows** — widget only shows trains during your configured hours
- **Real-time updates** every 60 seconds via AlarmManager (fires even in Doze mode)
- **Delay/early badges** from PTV real-time estimated departure data
- **Lock screen placement** supported on Pixel devices running Android 13+

---

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or later |
| JDK | 17 |
| Android SDK | API 36 (Android 16) |
| Pixel 6 | Android 13 minimum (for lock screen widget support) |

---

## Step 1 — Get PTV API Credentials

1. Go to https://www.ptv.vic.gov.au/footer/data-and-reporting/datasets/ptv-timetable-api/
2. Fill in the form to request a **Developer ID** and **API Key** (free, approved within a few days).
3. Keep both values handy — you'll enter them in the app's settings screen.

> The PTV API uses HMAC-SHA1 request signing. Every API call is automatically
> signed by `PtvApiClient.kt` using your key. You never need to handle this manually.

---

## Step 2 — Build & Install

```bash
# Clone / open in Android Studio, then:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or use **Run ▶** in Android Studio with your Pixel 6 connected.

---

## Step 3 — Add the Widget

### Home screen
1. Long-press on your home screen → **Widgets**
2. Find **Next Train** and drag it onto the screen
3. The configuration screen opens automatically

### Lock screen (Pixel, Android 13+)
1. Long-press the lock screen → **Customize** → **Add Widget**
2. Select **Next Train**
3. If prompted, open the app to complete configuration

---

## Step 4 — Configure

In the configuration screen:

1. **Enter your PTV Developer ID and API Key**, then tap **Save Credentials**
2. Tap **+** to add a route:
   - Choose **origin** and **destination** station from the dropdown (all Melbourne Metro stations listed)
   - Set the **active time window** (e.g. 06:00–10:00 for the morning)
   - Give it a label (e.g. "Morning commute")
3. Add a second route for the return journey (e.g. 16:00–20:00, Jolimont → Fairfield)
4. Tap **Save & Update Widget**

---

## How It Works

```
AlarmScheduler
  └── fires every 60s via AlarmManager.setExactAndAllowWhileIdle()
        └── AlarmReceiver.onReceive()
              └── broadcasts ACTION_REFRESH
                    └── TrainWidgetProvider.onReceive()
                          └── checks which OdPair is active right now
                                └── PtvApiClient.getDepartures(stopId)
                                      └── HMAC-signs the URL
                                      └── calls PTV API v3
                                      └── filters future departures
                                      └── renders RemoteViews with top 3
```

### Key classes

| File | Purpose |
|------|---------|
| `TrainWidgetProvider.kt` | AppWidgetProvider; renders RemoteViews |
| `AlarmScheduler.kt` | Schedules/cancels per-minute exact alarms |
| `AlarmReceiver.kt` | Receives alarm, triggers refresh + reschedules |
| `PtvApiClient.kt` | HMAC-SHA1 signing + PTV API v3 calls |
| `WidgetPrefs.kt` | SharedPreferences: credentials + OD pairs |
| `ConfigActivity.kt` | Settings UI: credentials + add/edit/delete OD pairs |
| `Models.kt` | Data classes: `OdPair`, `Departure`, `MelbourneStations` |

---

## Finding Stop IDs

All Melbourne Metro stations are pre-loaded in `MelbourneStations.ALL` in `Models.kt`.
If you need to verify or add a stop ID, call the PTV API directly:

```
GET /v3/stops/route_type/0
```

Or search by name:
```
GET /v3/search/{search_term}?route_types=0
```

Both endpoints require the same HMAC-SHA1 signing — you can use the
[PTV API Explorer](https://timetableapi.ptv.vic.gov.au/swagger/ui/index) with your credentials.

---

## Known Limitations & Notes

- **Direction filtering**: The widget fetches all departures from the origin stop and
  sorts by time. For a more precise filter (only services that call at the destination),
  add a `/v3/stops/stop/{stop_id}/route_type/0` check against the run's stop list.
  This is omitted here to keep API calls to 1 per update.

- **Exact alarms**: Android 12+ requires `SCHEDULE_EXACT_ALARM` permission. On some
  devices you may need to manually grant this under **Settings → Apps → Special app access
  → Alarms & reminders**.

- **Battery**: The widget uses `setExactAndAllowWhileIdle` only during configured active
  windows. Outside those windows, no alarms fire.

- **Lock screen on Android <13**: Lock screen widgets aren't supported. The widget will
  still work perfectly on the home screen.

---

## Permissions

| Permission | Why |
|-----------|-----|
| `INTERNET` | PTV API calls |
| `RECEIVE_BOOT_COMPLETED` | Reschedule alarms after reboot |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Per-minute updates |
| `FOREGROUND_SERVICE` | Reserved for future background service use |
| `WAKE_LOCK` | Keep CPU awake during alarm-triggered fetches |
