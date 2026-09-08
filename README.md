# XinBotTelemetry

Telemetry server for the [Xinbot](https://github.com/huangdihd/xinbot) Minecraft bot client.
It receives **encrypted heartbeat and crash-report packets** from Xinbot clients (over UDP or
HTTP), shows **which bots are online** on a built-in web dashboard, and **persists crash reports**
to SQLite or MySQL.

简体中文版本见 [README_CN.md](README_CN.md)。

## Features

- **Listens on all network interfaces** (`0.0.0.0`) for both UDP and HTTP traffic
- **Online / offline / crashed status per bot** — keyed by `bot@server`; a bot is considered
  offline when no heartbeat arrives within `online.timeout` (default 10 min, twice the
  client's 5-min heartbeat), or immediately when a crash report arrives (it comes back online
  on the next heartbeat)
- **Web dashboard** showing online counts and recent crash reports, auto-refresh every 10 s
- **Crash logs persisted** to SQLite (default) or MySQL; tables are created automatically
- **Multi-language** console logs, help text and dashboard page: `zh_cn` (default, Simplified
  Chinese), `zh_tw` (Traditional Chinese), `en_us` (English). Language files use the same
  `.lang` format as the Xinbot core (`en_us` acts as the fallback base)
- **Fully configurable via `config.yml`** (UTF-8 YAML with comments), auto-generated on the
  first run; command line is only `--help` and `--config=PATH`
- **Wire protocol identical to the Xinbot `TelemetryManager`**: one fixed AES-128-GCM key,
  tamper-evident encrypted envelope

## Requirements

- Java 17 or newer

## Build & Run

```bash
mvn package                      # produces target/XinBotTelemetry.jar
java -jar target/XinBotTelemetry.jar
java -jar target/XinBotTelemetry.jar --config=/path/to/config.yml
```

On the first start the server writes a default **`config.yml`** next to the jar if it does not
exist yet. Edit it and restart to apply changes.

Run `mvn test` to execute the packet-decoder unit tests.

## Configuration (`config.yml`)

| Key | Default | Description |
|---|---|---|
| `lang` | `zh_cn` | Interface language: `zh_cn` / `zh_tw` / `en_us` |
| `udp.port` | `9000` | UDP telemetry listen port (`0` disables) |
| `http.port` | `8080` | HTTP port for the dashboard and `POST /telemetry` (`0` disables) |
| `online.timeout` | `600000` | Milliseconds without a heartbeat before a bot is marked offline |
| `db.type` | `sqlite` | Crash-log storage: `sqlite` or `mysql` |
| `db.file` | `telemetry.db` | SQLite database file |
| `db.url` | `jdbc:mysql://…` | MySQL JDBC URL (only when `db.type=mysql`) |
| `db.user` / `db.password` | `root` / empty | MySQL credentials |

Both listeners bind `0.0.0.0` (all network interfaces); setting a port to `0` disables that
entry point.

For MySQL, it is recommended to keep `createDatabaseIfNotExist=true` in the JDBC URL so the
database and table are created on first start:

```yaml
db:
  type: mysql
  url: "jdbc:mysql://127.0.0.1:3306/xinbot_telemetry?createDatabaseIfNotExist=true"
  user: root
  password: ""
```

## Connecting Xinbot clients

Enable telemetry in the client's `config.conf`:

```hocon
"telemetry" : {
    "enable" : true,
    "mode" : "udp",        // "udp" (default) or "http"
    "ip" : "<server-ip>",  // IP of this telemetry server
    "port" : 9000
}
```

- `mode="udp"`: clients send encrypted envelopes to **`udp.port`** (default 9000).
- `mode="http"`: clients POST the same envelope to **`http.port`** at the path
  `/telemetry` (default `http://<server-ip>:8080/telemetry`).

## Web Dashboard & HTTP API

Open `http://<server-ip>:8080/` in a browser to see online counts and the latest crash reports.

| Route | Method | Description |
|---|---|---|
| `/` | GET | Dashboard HTML (rendered in the configured `lang`) |
| `/api/stats` | GET | `{online, offline, crashed, total_crashes, online_timeout_ms, now}` |
| `/api/bots` | GET | Status of every known bot: `name`, `server`, `online`, `crashed`, `state`, `players`, `version`, `source_ip`, `uptime_ms`, `last_seen_ms`, … |
| `/api/crashes?limit=N` | GET | Most recent crash reports (default 15, max 200) |
| `/telemetry` | POST | Receives HTTP-mode telemetry envelopes (body is the same binary envelope as UDP) |

## Wire Protocol (encrypted envelope)

Identical to the Xinbot client `TelemetryManager`. Each packet is one envelope:

| Offset | Bytes | Field |
|---|---|---|
| `0..3` | 4 | magic `XBTL` |
| `4` | 1 | protocol version (`1`) |
| `5` | 1 | message type: `1` = heartbeat, `2` = crash report |
| `6..17` | 12 | random AES-GCM IV |
| `18..` | rest | AES-128-GCM ciphertext (JSON payload + 16-byte auth tag) |

The fixed 16-byte key is the string `xinbot-telemetry` (ASCII); the server rejects packets that
fail authentication (wrong key or tampered data). The plaintext JSON payload contains
`type`, `timestamp_ms`, `version`, `bot`, `online`, `state`, `server`, `players`, `uptime_ms`;
heartbeats additionally carry JVM heap and OS info (`heap_used_bytes`, `os_name`, …), and crash
reports carry `thread_name`, `exception`, `stack_trace`.

If you write your own receiver, see `PacketDecoder` for the reference implementation
(`PacketDecoderTest` covers round-trip, bad magic and tamper detection).

## Language

Translations live in `src/main/resources/lang/` as Xinbot-core-style `.lang` files:

```
lang/
├── en_us.lang   # fallback base (full)
├── zh_cn.lang   # Simplified Chinese (default)
└── zh_tw.lang   # Traditional Chinese
```

Loading mirrors the Xinbot core `LangManager`: `en_us` is loaded first as the base, then the
selected language overrides the same keys; a missing key finally falls back to the key itself.
All keys are `key=value` lines, `#` starts a comment, format placeholders use `%s` / `%d`
(`String.format` style). To add another language, drop in a new `<code>.lang` file and register
the code in `I18n.supports(...)` (plus the config validation in `Config`).

## Project Layout

```
src/main/java/top/mcocet/
├── Main.java                  # bootstrap & graceful shutdown
├── Config.java                # config.yml loading / validation
├── i18n/I18n.java             # .lang loader & lookup
├── telemetry/
│   ├── PacketDecoder.java     # envelope verification + AES-GCM decryption
│   └── TelemetryHandler.java  # shared pipeline: heartbeat -> registry, crash -> store
├── core/
│   ├── BotRegistry.java       # online status registry & timeout logic
│   └── BotStatus.java         # per-bot live state
├── model/CrashRecord.java     # crash-log model (JSON <-> DB row)
├── store/                     # CrashStore: SQLite + MySQL implementations
├── net/UdpServer.java         # UDP listener (0.0.0.0)
└── http/WebServer.java        # dashboard, JSON APIs, POST /telemetry
src/main/resources/
├── config.yml                 # built-in default config (auto-copied on first run)
└── lang/*.lang                # translations
src/test/java/.../PacketDecoderTest.java
```

## Notes

- This project is part of the Xinbot ecosystem; the wire format and the `.lang` convention are
  shared with the [Xinbot](https://github.com/huangdihd/xinbot) core project.
- Records that have not reported for more than 7 days are purged from the in-memory registry
  automatically.
