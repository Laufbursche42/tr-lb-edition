# Android Permissions

This app requests only the permissions it needs to function. Each one is listed below with the reason it is used.

## Bluetooth

- **BLUETOOTH** (maxSdkVersion 30) - connect to the scooter over Bluetooth on Android 11 and older.
- **BLUETOOTH_ADMIN** (maxSdkVersion 30) - manage the Bluetooth adapter and start scans on Android 11 and older.
- **BLUETOOTH_SCAN** (neverForLocation) - find your scooter when connecting. It is flagged `neverForLocation`, so scanning is not used to derive your location.
- **BLUETOOTH_CONNECT** - talk to the scooter over Bluetooth LE on Android 12 and newer.

## Location

- **ACCESS_FINE_LOCATION** - required by Android for BLE scanning on older versions and used for GPS speed, route recording and offline navigation.
- **ACCESS_COARSE_LOCATION** - the coarse counterpart to the above, for the same BLE-scan and GPS needs. (Because BLUETOOTH_SCAN is flagged `neverForLocation`, scanning itself does not derive location.)

## Network and services

- **INTERNET** - download offline maps and routing data and send the SRT screen stream to your own server.
- **FOREGROUND_SERVICE** - run the map download as a foreground service so a large download keeps running with the screen off.
- **FOREGROUND_SERVICE_DATA_SYNC** - the foreground-service type for that map download, so it keeps running with the screen off.
- **FOREGROUND_SERVICE_MEDIA_PROJECTION** - the foreground-service type that lets screen streaming run as a foreground service.
- **POST_NOTIFICATIONS** - show the download or streaming progress notification.
- **WAKE_LOCK** - keep the CPU awake during a background download.
- **ACCESS_WIFI_STATE** - keep Wi-Fi awake during a background download.

## Note for Google Play

On Google Play these permissions are additionally declared and justified in the Play Console (the Data Safety form and the permission declarations).
