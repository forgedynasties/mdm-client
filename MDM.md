# AOSP MDM Server — Documentation

## Overview

A lightweight MDM (Mobile Device Management) backend for ~1000 custom AOSP devices.
Devices poll in every 60 seconds, sending a JSON payload to the API. A web dashboard
built with HTMX + Go templates provides real-time visibility into all devices.

---

## Architecture

```
AOSP Devices (x1000)
      |
      | POST /api/v1/checkin  (X-API-Key header)
      v
+-------------------+
|   Go HTTP Server  |  :8080
|   - REST API      |
|   - HTMX Dashboard|
+-------------------+
      |
      v
+-------------------+
|   PostgreSQL      |  :5432
+-------------------+
```

---

## Project Structure

```
mdm/
├── cmd/
│   └── server/
│       └── main.go
├── internal/
│   ├── api/          # API handlers
│   ├── dashboard/    # HTMX + template handlers
│   ├── db/           # Database layer
│   └── middleware/   # Auth, logging
├── migrations/       # SQL migration files
├── templates/        # Go HTML templates
├── static/           # CSS / JS assets
├── docker-compose.yml
├── Dockerfile
├── .env.example
└── DOCS.md
```

---

## Database Schema

### `devices`
Stores the canonical record for each device (identified by serial number).

| Column        | Type        | Notes                        |
|---------------|-------------|------------------------------|
| id            | UUID        | Primary key                  |
| serial_number | TEXT        | Unique, set on first checkin |
| build_id      | TEXT        | Latest known build           |
| created_at    | TIMESTAMPTZ | First seen                   |
| last_seen_at  | TIMESTAMPTZ | Updated on every checkin     |

### `checkins`
One row per poll. Historical record of every checkin.

| Column            | Type        | Notes                                      |
|-------------------|-------------|--------------------------------------------|
| id                | UUID        | Primary key                                |
| device_id         | UUID        | FK → devices.id                            |
| battery_pct       | SMALLINT    | 0–100                                      |
| build_id          | TEXT        | Build at time of checkin                   |
| extra             | JSONB       | Extensibility — any future fields go here  |
| created_at        | TIMESTAMPTZ | Checkin timestamp                          |

> **Extensibility:** Any new data fields from devices are stored in the `extra` JSONB
> column without requiring a schema migration. When a field becomes common/important
> it can be promoted to a dedicated column via a migration.

---

## Configuration

Copy `.env.example` to `.env` and fill in values:

```env
# Server
PORT=8080

# Postgres
DB_HOST=postgres
DB_PORT=5432
DB_USER=mdm
DB_PASSWORD=changeme
DB_NAME=mdm

# Shared API key — all devices use this
DEVICE_API_KEY=your-secret-key-here

# Dashboard login
DASHBOARD_USER=admin
DASHBOARD_PASSWORD=changeme
```

---

## Running with Docker

### Prerequisites
- Docker
- Docker Compose v2

### Start

```bash
# Clone / enter project directory
cd mdm

# Copy and edit environment
cp .env.example .env
nano .env        # set DEVICE_API_KEY, DASHBOARD_PASSWORD

# Build and start all services
docker compose up -d --build

# View logs
docker compose logs -f server

# Stop
docker compose down

# Stop and wipe database volume
docker compose down -v
```

The server will be available at `http://localhost:8080`.

---

## API Reference

All device API endpoints require the header:

```
X-API-Key: <DEVICE_API_KEY>
```

---

### POST /api/v1/checkin

Devices call this every 60 seconds.

**Request headers:**
```
Content-Type: application/json
X-API-Key: your-secret-key-here
```

**Request body — minimum payload:**
```json
{
  "serial_number": "ABC123XYZ",
  "build_id": "aosp-eng 13 TP1A.220624.014",
  "battery_pct": 87
}
```

**Request body — with extra fields (future extensibility):**
```json
{
  "serial_number": "ABC123XYZ",
  "build_id": "aosp-eng 13 TP1A.220624.014",
  "battery_pct": 87,
  "extra": {
    "ip_address": "192.168.1.42",
    "wifi_ssid": "Corp-WiFi",
    "storage_free_gb": 12.4
  }
}
```

**Response 200 OK:**
```json
{
  "status": "ok"
}
```

**Response 401 Unauthorized:**
```json
{
  "error": "invalid api key"
}
```

---

### curl Examples — Device Checkin

**Basic checkin:**
```bash
curl -X POST http://localhost:8080/api/v1/checkin \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-key-here" \
  -d '{
    "serial_number": "ABC123XYZ",
    "build_id": "aosp-eng 13 TP1A.220624.014",
    "battery_pct": 87
  }'
```

**Checkin with extra fields:**
```bash
curl -X POST http://localhost:8080/api/v1/checkin \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-key-here" \
  -d '{
    "serial_number": "ABC123XYZ",
    "build_id": "aosp-eng 13 TP1A.220624.014",
    "battery_pct": 54,
    "extra": {
      "ip_address": "10.0.0.55"
    }
  }'
```

**Simulate 3 different devices checking in:**
```bash
for serial in DEVICE001 DEVICE002 DEVICE003; do
  curl -s -X POST http://localhost:8080/api/v1/checkin \
    -H "Content-Type: application/json" \
    -H "X-API-Key: your-secret-key-here" \
    -d "{
      \"serial_number\": \"$serial\",
      \"build_id\": \"aosp-eng 13 TP1A.220624.014\",
      \"battery_pct\": $((RANDOM % 100))
    }" && echo " -> $serial OK"
done
```

**Test with wrong API key (expect 401):**
```bash
curl -X POST http://localhost:8080/api/v1/checkin \
  -H "Content-Type: application/json" \
  -H "X-API-Key: wrong-key" \
  -d '{"serial_number":"TEST","build_id":"x","battery_pct":50}'
```

---

### GET /api/v1/devices

Returns latest state of all known devices. Intended for dashboard and monitoring integrations.

**Request headers:**
```
X-API-Key: your-secret-key-here
```

**curl:**
```bash
curl http://localhost:8080/api/v1/devices \
  -H "X-API-Key: your-secret-key-here"
```

**Response:**
```json
[
  {
    "id": "a1b2c3d4-...",
    "serial_number": "ABC123XYZ",
    "build_id": "aosp-eng 13 TP1A.220624.014",
    "battery_pct": 87,
    "last_seen_at": "2026-03-06T14:32:00Z",
    "created_at": "2026-01-10T08:00:00Z"
  }
]
```

---

### GET /api/v1/devices/{serial}

Returns the latest state and full checkin history for a single device.

**curl:**
```bash
curl http://localhost:8080/api/v1/devices/ABC123XYZ \
  -H "X-API-Key: your-secret-key-here"
```

**Response:**
```json
{
  "device": {
    "id": "a1b2c3d4-...",
    "serial_number": "ABC123XYZ",
    "build_id": "aosp-eng 13 TP1A.220624.014",
    "last_seen_at": "2026-03-06T14:32:00Z",
    "created_at": "2026-01-10T08:00:00Z"
  },
  "checkins": [
    {
      "battery_pct": 87,
      "build_id": "aosp-eng 13 TP1A.220624.014",
      "extra": {},
      "created_at": "2026-03-06T14:32:00Z"
    },
    {
      "battery_pct": 91,
      "build_id": "aosp-eng 13 TP1A.220624.014",
      "extra": {},
      "created_at": "2026-03-06T14:31:00Z"
    }
  ]
}
```

---

## Dashboard

Access the web dashboard at: `http://localhost:8080/`

### Login
- URL: `http://localhost:8080/login`
- Credentials set via `DASHBOARD_USER` and `DASHBOARD_PASSWORD` in `.env`
- Session cookie is valid for 24 hours

### Pages

| Route                     | Description                              |
|---------------------------|------------------------------------------|
| `/`                       | Device list — all devices, latest state  |
| `/devices/{serial}`       | Device detail — history, battery chart   |

### Dashboard Features
- All-devices table: serial number, build ID, battery %, last seen
- Sortable / searchable device list
- Per-device checkin history table
- Battery percentage chart over time
- Auto-refresh every 60 seconds via HTMX polling

---

## Health Check

```bash
curl http://localhost:8080/health
# {"status":"ok","db":"ok"}
```

---

## Adding New Device Fields (Extensibility)

### Short-term — use `extra`
The device simply includes new keys in the `extra` object. No server changes needed:

```json
{
  "serial_number": "ABC123",
  "build_id": "...",
  "battery_pct": 80,
  "extra": {
    "new_field": "value"
  }
}
```

The value is stored as-is in the `checkins.extra` JSONB column and visible in the
device detail API response.

### Long-term — promote to a column
When a field is important enough to query/index directly:

1. Add a migration in `migrations/` to add the column to `checkins`
2. Update the checkin request struct in `internal/api/`
3. Update the DB insert in `internal/db/`
4. Update the dashboard template if needed

---

## Android Client Implementation Guide

This section covers how to implement the MDM polling agent on an AOSP device.

---

### Overview

The device runs a background service that wakes up every 60 seconds, collects telemetry,
and POSTs to `/api/v1/checkin`. On AOSP this is typically a system app installed in
`/system/priv-app/` so it can survive reboots and cannot be uninstalled by the user.

---

### Required Data Fields

| Field           | Android API                                      | Example value                          |
|-----------------|--------------------------------------------------|----------------------------------------|
| `serial_number` | `Build.getSerial()` (requires READ_PRIVILEGED_PHONE_STATE) | `"CE061715U9"`             |
| `build_id`      | `Build.DISPLAY`                                  | `"aosp-eng 13 TP1A.220624.014"`        |
| `battery_pct`   | `BatteryManager.EXTRA_LEVEL` intent              | `87`                                   |

Optional fields to pass in `extra`:

| Key               | Android API                                           |
|-------------------|-------------------------------------------------------|
| `ip_address`      | `WifiManager.getConnectionInfo().getIpAddress()`      |
| `wifi_ssid`       | `WifiManager.getConnectionInfo().getSSID()`           |
| `storage_free_gb` | `StatFs(Environment.getDataDirectory())`              |
| `uptime_seconds`  | `SystemClock.elapsedRealtime() / 1000`                |

---

### Polling Service (conceptual)

```java
// Runs inside a foreground Service or JobScheduler job
public void doCheckin() {
    String serial    = Build.getSerial();
    String buildId   = Build.DISPLAY;
    int    battery   = getBatteryLevel();          // see below
    String ipAddress = getWifiIpAddress();

    JSONObject body = new JSONObject();
    body.put("serial_number", serial);
    body.put("build_id",      buildId);
    body.put("battery_pct",   battery);

    JSONObject extra = new JSONObject();
    extra.put("ip_address", ipAddress);
    body.put("extra", extra);

    URL url = new URL("http://mdm.internal:8080/api/v1/checkin");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("X-API-Key", BuildConfig.MDM_API_KEY);
    conn.setDoOutput(true);
    conn.setConnectTimeout(10_000);
    conn.setReadTimeout(10_000);

    try (OutputStream os = conn.getOutputStream()) {
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
    }

    int responseCode = conn.getResponseCode(); // expect 200
}
```

#### Getting battery level

```java
private int getBatteryLevel() {
    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    Intent batteryStatus = context.registerReceiver(null, ifilter);
    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
    return (int) ((level / (float) scale) * 100);
}
```

---

### Scheduling the Poll

Use `AlarmManager` with `setExactAndAllowWhileIdle` for reliable 60-second intervals on
AOSP system apps (JobScheduler minimum is 15 min for regular apps):

```java
AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
PendingIntent pi = PendingIntent.getBroadcast(this, 0,
    new Intent(this, CheckinReceiver.class),
    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

am.setExactAndAllowWhileIdle(
    AlarmManager.ELAPSED_REALTIME_WAKEUP,
    SystemClock.elapsedRealtime() + 60_000,
    pi
);
```

Re-schedule inside `CheckinReceiver.onReceive()` to create a repeating chain.
Trigger the first alarm from a `BOOT_COMPLETED` receiver so polling survives reboots.

---

### curl Commands for Testing and Debugging

Use these on a host machine or directly from an `adb shell` on the device to validate
connectivity to the MDM server before writing Android code.

#### Basic connectivity check

```bash
curl -v http://localhost:8080/health
# Expected: {"status":"ok","db":"ok"}
```

#### Single device checkin (minimal payload)

```bash
curl -X POST http://localhost:8080/api/v1/checkin \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-key-here" \
  -d '{
    "serial_number": "CE061715U9",
    "build_id": "aosp-eng 13 TP1A.220624.014",
    "battery_pct": 87
  }'
```

#### Checkin with full extra payload

```bash
curl -X POST http://localhost:8080/api/v1/checkin \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-key-here" \
  -d '{
    "serial_number": "CE061715U9",
    "build_id": "aosp-eng 13 TP1A.220624.014",
    "battery_pct": 72,
    "extra": {
      "ip_address": "192.168.1.101",
      "wifi_ssid": "Corp-WiFi",
      "storage_free_gb": 14.2,
      "uptime_seconds": 86400
    }
  }'
```

#### Poll loop — simulate the 60-second cycle from adb shell

Run this directly on the device via `adb shell`:

```bash
SERIAL=$(getprop ro.serialno)
BUILD=$(getprop ro.build.display.id)
API_KEY="your-secret-key-here"
SERVER="http://10.0.2.2:8080"   # 10.0.2.2 = host machine from Android emulator

while true; do
  BATTERY=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null || echo 100)
  curl -s -X POST "$SERVER/api/v1/checkin" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $API_KEY" \
    -d "{
      \"serial_number\": \"$SERIAL\",
      \"build_id\": \"$BUILD\",
      \"battery_pct\": $BATTERY
    }" && echo "[$(date)] checkin OK"
  sleep 60
done
```

#### Verify the device appeared on the server

```bash
curl http://localhost:8080/api/v1/devices/CE061715U9 \
  -H "X-API-Key: your-secret-key-here" | python3 -m json.tool
```

#### Confirm auth rejection with a wrong key

```bash
curl -i -X POST http://localhost:8080/api/v1/checkin \
  -H "Content-Type: application/json" \
  -H "X-API-Key: wrong-key" \
  -d '{"serial_number":"CE061715U9","build_id":"test","battery_pct":50}'
# Expected HTTP/1.1 401 Unauthorized
```

#### Watch live checkins on the server side

Pair any of the curl loops above with this on the server host:

```bash
docker compose logs -f server | grep checkin
```

---

### Embedding the API Key

The API key is hardcoded directly in the client source for now:

```java
private static final String MDM_API_KEY = "your-secret-key-here";
```

---

### Retry and Error Handling

| Scenario                   | Recommended behaviour                                        |
|----------------------------|--------------------------------------------------------------|
| Network unavailable        | Skip checkin, reschedule normally (don't drain battery retrying) |
| HTTP 5xx from server       | Retry once after 10 s, then skip until next 60 s cycle       |
| HTTP 401 Unauthorized      | Log error, do not retry (key is wrong — needs OTA fix)       |
| Timeout (>10 s)            | Cancel request, skip cycle                                   |
| Server unreachable >5 min  | Optionally surface a notification if the device is admin-managed |

---

## Load Estimate

| Metric              | Value                         |
|---------------------|-------------------------------|
| Devices             | 1,000                         |
| Poll interval       | 60 seconds                    |
| Requests/second     | ~17 req/s (steady state)      |
| Rows/day (checkins) | ~1,440,000                    |
| Rows/month          | ~43,200,000                   |

At this scale a single Go server + single Postgres instance (with connection pooling
via pgx) is more than sufficient. Postgres can handle millions of rows easily; add
a `created_at` index on `checkins` and partition by month if the table grows large.
