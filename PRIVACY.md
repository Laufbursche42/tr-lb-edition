# Privacy Policy

Laufbursche Edition is built to keep your data on your device. This policy explains exactly what the app does and does not do with your data.

## The short version

The app collects nothing. There are no accounts, no analytics, no telemetry, no tracking, no ads and no third-party SDKs that phone home. Nothing is ever sent to the developer or to any manufacturer backend.

## What data the app handles - and where it stays

All of the following stays on your device and is never uploaded anywhere:

- Live scooter telemetry read over Bluetooth LE.
- Recorded GPS tracks (GPX files).
- Your app settings and preferences.
- Debug logs.
- Downloaded offline maps and routing data.

You can export a GPX track or a debug log yourself through the Android share sheet, but the app never uploads any of this on its own.

## The only network connections the app makes

The app makes network connections in exactly three cases and no others.

### 1. Bluetooth LE to your scooter

A local radio link to your scooter. This is not an internet connection - no data leaves your phone over the network for this.

### 2. Offline map and routing-data downloads (HTTPS, on demand)

When you tap download or route into an area you do not yet have data for, the app downloads:

- offline vector maps from the Hochschule Esslingen mirror of download.mapsforge.org - base URL https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/europe/ with a country map file appended (for example germany.map).
- bicycle-routing segments from the BRouter project server - base URL https://brouter.de/brouter/segments4/ with a 5x5-degree tile file appended (for example E5_N45.rd5).

These are public OpenStreetMap-based mirrors. This happens only on your explicit action. Those servers can see your IP address and the requested file path (which country or tile you fetch). The app keeps this to a minimum: it sends a neutral fixed User-Agent (`TR-LB-Edition`), no cookies, no account and no tracking parameters. All map traffic uses HTTPS; the app uses no cleartext HTTP.

### 3. SRT screen streaming (only to your own server)

Screen streaming goes only to the server URL you configure yourself - typically your own local or LAN server. The stored URL is encrypted on the device using the Android Keystore (AES-256-GCM). SRT is its own transport, not HTTP; it can additionally be AES-encrypted by adding a passphrase to your SRT URL.

## No developer or manufacturer backend

Nothing is ever sent to the developer or to any manufacturer backend. There is no cloud account and no server operated by this project that receives your data.

## Android permissions

Each Android permission the app requests is listed and explained in [PERMISSIONS.md](PERMISSIONS.md).

## Contact

For privacy questions, contact the author (Laufbursche) on GitHub: https://github.com/Laufbursche42
