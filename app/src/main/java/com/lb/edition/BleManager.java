// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Native BLE layer for the Teverun VCU (UART-over-BLE). Replicates the ORIGINAL uni-app connection
 * flow exactly (BLE_PROTOCOL §1): scan by name prefix, connect GATT, discover the primary
 * 0000FF.. / 495353.. service, pick notify/write characteristics, enable notifications (local +
 * CCCD), then send sendConnectCode(0) once and every 6.5 s to start/sustain the telemetry stream.
 *
 * All public entry points are null/exception-safe so nothing ever throws across the JS bridge.
 */
@SuppressLint("MissingPermission")
final class BleManager {

    private static final String TAG = "lbble";

    // CCCD descriptor
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Hard-coded ISSC Transparent-UART characteristics (used when the service is 495353..)
    private static final String ISSC_NOTIFY = "49535343-1e4d-4bd9-ba61-23c647249616";
    private static final String ISSC_WRITE  = "49535343-aca3-481c-91ec-d85e28a60318";
    // Generic Access service + Device Name characteristic: the reliable post-connect source of the
    // BLE name / FIN (the advertised name and BluetoothDevice.getName() are often null on LE).
    private static final UUID GAP_SERVICE     = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    private static final UUID GAP_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb");

    private static final String[] NAME_PREFIXES = {"XY", "T", "BT04"};

    private static final long CONNECT_CODE_INTERVAL_MS = 6500;
    private static final long DISCOVER_DELAY_MS = 1500;   // original waits 1500 ms after connect
    private static final long WRITE_GAP_MS = 200;         // app spaces multi-frame writes ~200 ms
    private static final long RECONNECT_BASE_MS = 3000;    // exponential-backoff base delay
    private static final long RECONNECT_MAX_MS = 30000;    // exponential-backoff cap
    private static final long PUSH_INTERVAL_MS = 500;     // live-data push ~2x/s

    interface Listener {
        void onScanResults(String jsonArray);
        void onState(String json);
        void onLiveData(String json);
        // Firmware-update (OTA) callbacks. json fields: progress {percent,packet,count,phase};
        // state {state,message}. Log is a plain line. All are null/exception-safe on the far side.
        void onOtaProgress(String json);
        void onOtaLog(String line);
        void onOtaState(String json);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final SettingsState settings = new SettingsState();
    private final FrameParser parser = new FrameParser(settings);

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private volatile boolean scanning = false;
    private final Map<String, ScanEntry> found = new LinkedHashMap<>();

    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic notifyChar;
    private volatile BluetoothGattCharacteristic writeChar;
    private volatile boolean notifyReady = false;
    private volatile boolean connected = false;
    // Guards the one-shot data-characteristic setup: the GAP-name read runs first and calls
    // setupCharacteristics() on completion, with a timeout fallback - this stops it running twice.
    private volatile boolean charsSetupDone = false;

    private String desiredAddress;
    private String deviceName = "";

    // Auto-reconnect backoff: current delay, doubles per failed attempt (base..cap), reset on connect.
    private volatile long reconnectDelay = RECONNECT_BASE_MS;

    // write serialisation
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writing = false;

    // Firmware-update engine. Non-null and running while a flash is in progress; during that time
    // notifications and write-completions are routed to it and the normal keep-alive / push are paused.
    private volatile OtaEngine ota;

    BleManager(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
        try {
            BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) adapter = bm.getAdapter();
        } catch (Throwable t) {
            Log.e(TAG, "adapter init failed", t);
        }
    }

    private static final class ScanEntry {
        String name;
        String address;
        int rssi;
    }

    // ── Scan ──

    void scan() {
        try {
            if (adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "scan: adapter unavailable/disabled");
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            synchronized (found) { found.clear(); }
            if (scanning) return;
            ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, s, scanCallback);
            scanning = true;
            Log.i(TAG, "scan started");
        } catch (Throwable t) {
            Log.e(TAG, "scan failed", t);
        }
    }

    void stopScan() {
        try {
            if (scanner != null && scanning) scanner.stopScan(scanCallback);
        } catch (Throwable t) {
            Log.e(TAG, "stopScan failed", t);
        } finally {
            scanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScan(result);
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            if (results != null) for (ScanResult r : results) handleScan(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed code=" + errorCode);
            scanning = false;
        }
    };

    private void handleScan(ScanResult result) {
        try {
            if (result == null || result.getDevice() == null) return;
            String addr = result.getDevice().getAddress();
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null || name.isEmpty()) {
                try { name = result.getDevice().getName(); } catch (Throwable ignored) {}
            }
            if (!nameAccepted(name)) return;
            ScanEntry e = new ScanEntry();
            e.name = (name == null) ? "" : name;
            e.address = addr;
            e.rssi = result.getRssi();
            boolean changed;
            synchronized (found) {
                ScanEntry prev = found.get(addr);
                changed = prev == null;
                found.put(addr, e);
            }
            if (changed) { Log.i(TAG, "found: " + e.name + " [" + addr + "] rssi=" + e.rssi); pushScanResults(); }
        } catch (Throwable t) {
            Log.e(TAG, "handleScan failed", t);
        }
    }

    private static boolean nameAccepted(String name) {
        if (name == null) return false;
        for (String p : NAME_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    private void pushScanResults() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (found) {
                for (ScanEntry e : found.values()) {
                    JSONObject o = new JSONObject();
                    o.put("name", e.name);
                    o.put("address", e.address);
                    o.put("rssi", e.rssi);
                    arr.put(o);
                }
            }
            if (listener != null) listener.onScanResults(arr.toString());
        } catch (Throwable t) {
            Log.e(TAG, "pushScanResults failed", t);
        }
    }

    // ── Connect / disconnect ──

    /**
     * Connect and seed the BLE name the caller already knows (e.g. from the scan list or the
     * remembered scooter), so the FIN / Bluetooth name shows immediately even when the LE stack
     * returns a null {@code getName()} on reconnect. The GAP-name read still runs and refreshes it.
     */
    void connect(String address, String name) {
        if (name != null && !name.trim().isEmpty()) {
            deviceName = name.trim();
            parser.isVer2 = deviceName.startsWith("T2");
            parser.btName = deviceName;
        }
        connect(address);
    }

    void connect(String address) {
        try {
            if (address == null || address.trim().isEmpty() || adapter == null) return;
            desiredAddress = address.trim();
            stopScan();
            // record any name we already discovered for this address
            synchronized (found) {
                ScanEntry e = found.get(desiredAddress);
                if (e != null && e.name != null) deviceName = e.name;
            }
            closeGatt();
            BluetoothDevice dev = adapter.getRemoteDevice(desiredAddress);
            if (deviceName == null || deviceName.isEmpty()) {
                try { String n = dev.getName(); if (n != null) deviceName = n; } catch (Throwable ignored) {}
            }
            parser.isVer2 = deviceName != null && deviceName.startsWith("T2");
            // Expose the BLE name to the dashboard as soon as it is known (forms the FIN prefix).
            parser.btName = deviceName == null ? "" : deviceName;
            Log.i(TAG, "connect() -> " + desiredAddress + " name=" + deviceName);
            pushState("connecting");
            gatt = dev.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
        }
    }

    void disconnect() {
        desiredAddress = null;   // user-initiated: no auto-reconnect
        stopKeepAlive();
        stopPush();
        try {
            if (gatt != null) gatt.disconnect();
        } catch (Throwable t) {
            Log.e(TAG, "disconnect failed", t);
        }
        closeGatt();
        connected = false;
        notifyReady = false;
        pushState("disconnected");
    }

    /** @return JSON {"address","name"} of the last successfully connected scooter, or "" if none. */
    String lastDeviceJson() {
        try {
            SharedPreferences sp = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE);
            String addr = sp.getString("last_device_addr", "");
            if (addr == null || addr.isEmpty()) return "";
            String name = sp.getString("last_device_name", "");
            JSONObject o = new JSONObject();
            o.put("address", addr);
            o.put("name", name == null ? "" : name);
            return o.toString();
        } catch (Throwable t) {
            Log.e(TAG, "lastDeviceJson failed", t);
            return "";
        }
    }

    /** Reconnect to the remembered scooter. No-ops if already connected/busy or nothing is stored. */
    void connectLast() {
        try {
            if (connected || desiredAddress != null) return;   // self-guard: already connected/busy
            SharedPreferences sp = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE);
            String addr = sp.getString("last_device_addr", "");
            if (addr != null && !addr.isEmpty()) {
                Log.i(TAG, "connectLast() -> " + addr);
                connect(addr);
            }
        } catch (Throwable t) {
            Log.e(TAG, "connectLast failed", t);
        }
    }

    private void closeGatt() {
        try {
            if (gatt != null) gatt.close();
        } catch (Throwable ignored) {
        } finally {
            gatt = null;
            notifyChar = null;
            writeChar = null;
            notifyReady = false;
            charsSetupDone = false;   // next connection re-runs the GAP-name read + characteristic setup
            synchronized (writeQueue) { writeQueue.clear(); writing = false; }
        }
    }

    // ── GATT callback ──

    private long frameCount = 0;
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected");
                pushState("discovering");
                // Original waits ~1500 ms before discovering services.
                main.postDelayed(() -> {
                    try { if (gatt != null) gatt.discoverServices(); } catch (Throwable ignored) {}
                }, DISCOVER_DELAY_MS);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected status=" + status);
                connected = false;
                notifyReady = false;
                stopKeepAlive();
                stopPush();
                closeGatt();
                pushState("disconnected");
                // reconnect unless the user asked to disconnect, using exponential backoff so a
                // failing link does not hammer the stack with repeated GATT status 133/147 errors.
                if (desiredAddress != null) {
                    long delay = reconnectDelay;
                    Log.i(TAG, "scheduling reconnect in " + delay + " ms (backoff)");
                    // Back off further for the next attempt (capped); a successful connect resets this.
                    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
                    main.postDelayed(() -> {
                        if (desiredAddress != null) connect(desiredAddress);
                    }, delay);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            try {
                Log.i(TAG, "onServicesDiscovered status=" + status + " count=" + (g == null ? 0 : g.getServices().size()));
                // The advertised name often arrives empty on connect - resolve it now that the GATT
                // is up so the info page shows the Bluetooth name / FIN (see ensureDeviceName).
                ensureDeviceName(g);
                // Read the GAP Device Name (0x2A00) for an authoritative BLE name / FIN, THEN set up
                // the data characteristics. Reading first keeps only one GATT op in flight (no clash
                // with the CCCD write). If no read could be started, set up characteristics now.
                if (!readGapDeviceName(g)) {
                    setupCharacteristics(g);
                }
            } catch (Throwable t) {
                Log.e(TAG, "onServicesDiscovered failed", t);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            // CCCD written: notifications live. Start the handshake / keep-alive and pushes.
            Log.i(TAG, "CCCD write status=" + status);
            notifyReady = true;
            connected = true;
            reconnectDelay = RECONNECT_BASE_MS;   // successful connect: reset auto-reconnect backoff
            // Last chance to resolve the BLE name before it is persisted below (feeds btName / FIN).
            ensureDeviceName(g);
            // Remember this scooter so we can auto-reconnect next time the app opens. Only overwrite
            // the remembered name when we actually resolved one, so a transient empty getName() does
            // not wipe a previously-good FIN (which would leave the saved-device row showing no name).
            try {
                SharedPreferences.Editor ed = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE).edit()
                        .putString("last_device_addr", desiredAddress);
                if (deviceName != null && !deviceName.isEmpty()) {
                    ed.putString("last_device_name", deviceName);
                }
                ed.apply();
            } catch (Throwable ignored) {}
            pushState("connected");
            startPush();
            startKeepAlive();   // sends sendConnectCode(0) immediately, then every 6.5 s
            drainWriteQueue();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            // During a flash the OTA engine owns the write pump (its own queue + pacing).
            OtaEngine o = ota;
            if (o != null && o.isRunning()) { o.onWriteComplete(); return; }
            synchronized (writeQueue) { writing = false; }
            main.postDelayed(BleManager.this::drainWriteQueue, WRITE_GAP_MS);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            try {
                if (c != null && GAP_DEVICE_NAME.equals(c.getUuid())) {
                    String n = null;
                    try { n = c.getStringValue(0); } catch (Throwable ignored) {}
                    if (n != null) n = n.trim();
                    if (n != null && !n.isEmpty()) {
                        // Authoritative name from the device: publish and persist (feeds btName / FIN).
                        deviceName = n;
                        parser.isVer2 = deviceName.startsWith("T2");
                        parser.btName = deviceName;
                        try {
                            appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE).edit()
                                    .putString("last_device_name", deviceName).apply();
                        } catch (Throwable ignored) {}
                        Log.i(TAG, "GAP device name = " + deviceName);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "onCharacteristicRead failed", t);
            } finally {
                // The GAP-name read completed (or errored): now bring up the data characteristics.
                setupCharacteristics(g);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            try {
                byte[] v = c.getValue();
                if (v != null) {
                    // During a flash, OTA responses (0xcc frames) go to the engine, not the telemetry
                    // parser. The VCU stops streaming 0x55 telemetry once it enters the bootloader.
                    OtaEngine o = ota;
                    if (o != null && o.isRunning()) { o.onNotify(v); return; }
                    parser.onNotify(v);
                    if (frameCount++ % 50 == 0) Log.i(TAG, "rx frames=" + frameCount + " last=" + v.length + "b");
                }
            } catch (Throwable t) {
                Log.e(TAG, "onCharacteristicChanged failed", t);
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            parser.rssi = rssi;
            parser.btName = deviceName == null ? "" : deviceName;
        }
    };

    /**
     * Make the BLE device name robust. The advertised name frequently arrives EMPTY at connect
     * time (so the info page shows no FIN / Bluetooth name). Once the GATT is up we can usually
     * resolve it: try the live {@code gatt.getDevice().getName()}, then fall back to the remembered
     * {@code last_device_name} pref. Whenever a non-empty name becomes known we publish it to the
     * parser (which feeds the JSON {@code btName} that forms the FIN prefix) and persist it so a
     * later reconnect - where the name may again be empty - still shows the scooter's name.
     */
    private void ensureDeviceName(BluetoothGatt g) {
        try {
            if (deviceName == null || deviceName.isEmpty()) {
                // 1) the live GATT device (the name is often only resolvable after connecting)
                if (g != null && g.getDevice() != null) {
                    try {
                        String n = g.getDevice().getName();
                        if (n != null && !n.isEmpty()) deviceName = n;
                    } catch (Throwable ignored) {}
                }
            }
            if (deviceName == null || deviceName.isEmpty()) {
                // 2) the remembered name from the last successful connect
                try {
                    String n = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE)
                            .getString("last_device_name", "");
                    if (n != null && !n.isEmpty()) deviceName = n;
                } catch (Throwable ignored) {}
            }
            if (deviceName != null && !deviceName.isEmpty()) {
                // Publish to the dashboard (btName forms the FIN prefix) and persist for next time.
                parser.isVer2 = deviceName.startsWith("T2");
                parser.btName = deviceName;
                try {
                    appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE).edit()
                            .putString("last_device_name", deviceName).apply();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensureDeviceName failed", t);
        }
    }

    /**
     * Start an async read of the GAP Device Name characteristic (0x1800 / 0x2A00). Returns true if
     * a read was initiated - in that case {@link #onCharacteristicRead} will pick up the name and
     * then call {@link #setupCharacteristics}; a timeout fallback guarantees setup still runs if the
     * read callback never fires. Returns false if the characteristic is unavailable / the read could
     * not be started, so the caller proceeds with characteristic setup directly.
     */
    private boolean readGapDeviceName(BluetoothGatt g) {
        try {
            if (g == null) return false;
            BluetoothGattService gap = g.getService(GAP_SERVICE);
            if (gap == null) return false;
            BluetoothGattCharacteristic c = gap.getCharacteristic(GAP_DEVICE_NAME);
            if (c == null) return false;
            if (!g.readCharacteristic(c)) return false;
            // Fallback: if the read never returns, bring up the data characteristics anyway.
            main.postDelayed(() -> { if (!charsSetupDone) setupCharacteristics(g); }, 1200);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "readGapDeviceName failed", t);
            return false;
        }
    }

    private void setupCharacteristics(BluetoothGatt g) {
        if (g == null) return;
        synchronized (this) {
            if (charsSetupDone) return;   // one-shot: the GAP-name read and its fallback both call in
            charsSetupDone = true;
        }
        BluetoothGattService svc = pickService(g);
        if (svc == null) {
            Log.w(TAG, "no matching service");
            pushState("no-service");
            return;
        }
        String svcUuid = svc.getUuid().toString().toUpperCase(Locale.US);
        notifyChar = null;
        writeChar = null;

        if (svcUuid.startsWith("495353")) {
            // ISSC Transparent UART: hard-coded characteristic UUIDs.
            notifyChar = svc.getCharacteristic(UUID.fromString(ISSC_NOTIFY));
            writeChar = svc.getCharacteristic(UUID.fromString(ISSC_WRITE));
        } else {
            // 0000FFxx family: pick by property, mirroring the ORIGINAL app EXACTLY
            // (mixins/bluetooths.js getBLEDeviceCharacteristics): iterate ALL characteristics and
            // keep the LAST one carrying the notify property as the notify char, and the LAST
            // write-only (write property, no notify) characteristic as the write char. The original
            // ternary assigns a notify+write characteristic to notify only. Using the LAST match
            // (not the first) is essential on units that expose more than one notify characteristic
            // where the real telemetry/settings stream - including the 55 71 settings frame - is on
            // a later characteristic; picking the first delivered only a subset of frames.
            BluetoothGattCharacteristic anyWritable = null;   // fallback if no plain-write char exists
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                int props = c.getProperties();
                boolean notify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean write = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                boolean writeNr = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                if (notify) {
                    notifyChar = c;                 // last notify char wins (as in the original)
                } else if (write) {
                    writeChar = c;                  // last write-only char wins (as in the original)
                }
                if (write || writeNr) anyWritable = c;
            }
            if (writeChar == null) writeChar = anyWritable;   // only if no plain-write char was found
        }

        if (notifyChar == null || writeChar == null) {
            Log.w(TAG, "notify/write characteristic missing (notify=" + notifyChar + " write=" + writeChar + ")");
            pushState("no-char");
            return;
        }
        Log.i(TAG, "service=" + svcUuid + " notify=" + notifyChar.getUuid() + " write=" + writeChar.getUuid());
        enableNotifications(g);
    }

    private BluetoothGattService pickService(BluetoothGatt g) {
        // Mirror the ORIGINAL app (mixins/bluetooths.js getBLEDeviceServices): scan ALL services and
        // keep the LAST primary service whose UUID starts with 0000FF.. or 495353.. Using the last
        // match (not the first) matters on units that expose more than one matching service, where
        // the real telemetry/settings service is the later one; picking the first yielded only a
        // subset of frames (e.g. battery but no 55 71 settings frame). Prefer a primary service like
        // the original (`e.isPrimary`), but fall back to the last match of any type so a device that
        // reports its data service as non-primary still connects.
        BluetoothGattService chosen = null;         // last matching service of any type
        BluetoothGattService chosenPrimary = null;  // last matching PRIMARY service
        for (BluetoothGattService svc : g.getServices()) {
            String u = svc.getUuid().toString().toUpperCase(Locale.US);
            // UUID string form: 0000FFxx-.... or 49535343-.... - match on the leading hex.
            String compact = u.replace("-", "");
            boolean matches = compact.startsWith("0000FF") || compact.startsWith("495353");
            boolean primary = svc.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY;
            Log.i(TAG, "discovered service: " + u + " matches=" + matches + " primary=" + primary);
            if (matches) {
                chosen = svc;
                if (primary) chosenPrimary = svc;
            }
        }
        return chosenPrimary != null ? chosenPrimary : chosen; // only the documented primary services
    }

    private void enableNotifications(BluetoothGatt g) {
        try {
            g.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor cccd = notifyChar.getDescriptor(CCCD);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean ok = g.writeDescriptor(cccd);
                Log.i(TAG, "writeDescriptor(CCCD) initiated=" + ok);
                if (!ok) {
                    // Could not write CCCD; proceed anyway so the stream may still start.
                    main.post(this::forceReady);
                }
            } else {
                Log.w(TAG, "CCCD descriptor missing; proceeding");
                main.post(this::forceReady);
            }
        } catch (Throwable t) {
            Log.e(TAG, "enableNotifications failed", t);
            main.post(this::forceReady);
        }
    }

    private void forceReady() {
        notifyReady = true;
        connected = true;
        reconnectDelay = RECONNECT_BASE_MS;   // successful connect: reset auto-reconnect backoff
        ensureDeviceName(gatt);               // resolve the BLE name (btName / FIN) if still empty
        pushState("connected");
        startPush();
        startKeepAlive();
        drainWriteQueue();
    }

    // ── Keep-alive (connect code every 6.5 s) ──

    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (!notifyReady) return;
            enqueueWrite(CommandBuilder.sendConnectCode(0));
            main.postDelayed(this, CONNECT_CODE_INTERVAL_MS);
        }
    };

    private void startKeepAlive() {
        main.removeCallbacks(keepAlive);
        main.post(keepAlive);   // send once now, then repeat
    }

    private void stopKeepAlive() {
        main.removeCallbacks(keepAlive);
    }

    // ── Live-data push (~2x/s) ──

    private final Runnable pushTask = new Runnable() {
        @Override
        public void run() {
            if (!connected) return;
            try {
                if (listener != null) listener.onLiveData(parser.toJson());
            } catch (Throwable t) {
                Log.e(TAG, "push failed", t);
            }
            // opportunistic RSSI refresh
            try { if (gatt != null) gatt.readRemoteRssi(); } catch (Throwable ignored) {}
            main.postDelayed(this, PUSH_INTERVAL_MS);
        }
    };

    private void startPush() {
        main.removeCallbacks(pushTask);
        main.postDelayed(pushTask, PUSH_INTERVAL_MS);
    }

    private void stopPush() {
        main.removeCallbacks(pushTask);
    }

    // ── Write queue (serialised GATT writes) ──

    private void enqueueWrite(byte[] frame) {
        if (frame == null) return;
        synchronized (writeQueue) { writeQueue.add(frame); }
        drainWriteQueue();
    }

    private void drainWriteQueue() {
        if (!notifyReady) return;
        byte[] frame;
        synchronized (writeQueue) {
            if (writing) return;
            frame = writeQueue.poll();
            if (frame == null) return;
            writing = true;
        }
        boolean started = doWrite(frame);
        if (!started) {
            synchronized (writeQueue) { writing = false; }
            main.postDelayed(this::drainWriteQueue, WRITE_GAP_MS);
        }
    }

    private boolean doWrite(byte[] frame) {
        try {
            BluetoothGatt g = gatt;
            BluetoothGattCharacteristic wc = writeChar;
            if (g == null || wc == null) return false;
            int props = wc.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }
            wc.setValue(frame);
            return g.writeCharacteristic(wc);
        } catch (Throwable t) {
            Log.e(TAG, "doWrite failed", t);
            return false;
        }
    }

    /**
     * OTA characteristic write - forces WRITE_TYPE_NO_RESPONSE (falling back to default only if the
     * characteristic cannot do no-response). The VCU bootloader flashes with no-response writes just
     * like the original app; write-with-response is NOT sustained by the bootloader's minimal ATT
     * stack across a whole flash (after a few hundred writes it stops ACKing and a with-response
     * pump stalls waiting for the completion callback). No-response is fire-and-forget at the ATT
     * layer, which is what the bootloader expects.
     */
    private boolean doWriteOta(byte[] frame) {
        try {
            BluetoothGatt g = gatt;
            BluetoothGattCharacteristic wc = writeChar;
            if (g == null || wc == null || frame == null) return false;
            int props = wc.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            wc.setValue(frame);
            return g.writeCharacteristic(wc);
        } catch (Throwable t) {
            Log.e(TAG, "doWriteOta failed", t);
            return false;
        }
    }

    // ── High-level commands (from the JS bridge) ──

    void sendSetting(String json) {
        try {
            JSONObject o = (json == null || json.trim().isEmpty()) ? null : new JSONObject(json);
            settings.merge(o);
            // Write mode a[2], EXACTLY as the original app: anti-theft (atMode) = 8, smart = 5,
            // every other setting = 2 (the app's sendSetting() default). n=0 was wrong and the VCU
            // silently ignored the change (same class of bug as the Smart toggle).
            int mode = 2;
            if (o != null) {
                if (o.has("atMode")) mode = 8;
                else if (o.has("isSmart")) mode = 5;
            }
            enqueueWrite(CommandBuilder.sendSettingCode(settings, mode, 1));
        } catch (Throwable t) {
            Log.e(TAG, "sendSetting failed", t);
        }
    }

    /** mode: 0 = dual, 1 = rear-only, 2 = front-only (BLE_PROTOCOL §4). */
    void setMotorMode(int mode) {
        try {
            switch (mode) {
                case 1: settings.rmStatus = 1; settings.doubleMotor = 0; break;  // rear-only
                case 2: settings.rmStatus = 0; settings.doubleMotor = 1; break;  // front-only
                case 0:
                default: settings.rmStatus = 1; settings.doubleMotor = 1; break; // dual
            }
            enqueueWrite(CommandBuilder.sendSettingCode(settings, 2, 1));   // n=2 immediate
        } catch (Throwable t) {
            Log.e(TAG, "setMotorMode failed", t);
        }
    }

    /**
     * SMART / TCS traction control toggle. The original app writes Smart with a[2]=5
     * ({@code sendSettingCode(state, 5)}), NOT the generic normal write ({@link #sendSetting}
     * uses a[2]=2), so it goes through its own write mode here. Null/exception-safe.
     */
    void setSmart(boolean on) {
        try {
            settings.isSmart = on;
            enqueueWrite(CommandBuilder.sendSettingCode(settings, 5, 1));   // n=5 (Smart write)
        } catch (Throwable t) {
            Log.e(TAG, "setSmart failed", t);
        }
    }

    void setCustomKey(int value) {
        try {
            enqueueWrite(CommandBuilder.setCustomKey(value));
        } catch (Throwable t) {
            Log.e(TAG, "setCustomKey failed", t);
        }
    }

    /**
     * Set the VCU identity / BLE advertised name via cmd 0x1f. Non-destructive and reversible: the
     * VCU persists the new identity to its EEPROM and renames its BLE module (the link may briefly
     * drop; auto-reconnect by MAC restores it). The identity's first three characters gate the eKFV
     * speed clamp - see CommandBuilder.setDeviceName and the project firmware notes. Null-safe.
     */
    void setBleName(String name) {
        try {
            if (name == null) return;
            enqueueWrite(CommandBuilder.setDeviceName(name));
        } catch (Throwable t) {
            Log.e(TAG, "setBleName failed", t);
        }
    }

    /**
     * Write ONE gear/assist profile (cmd 0x18, BLE_PROTOCOL §3.4). {@code json} may carry any of
     * {@code speedLimit, eabsLevel, fStartLevel, rStartLevel, fCurrent, rCurrent}; missing fields
     * fall back to the maintained current-gear state. All other config bytes stay current.
     */
    void sendGearSetting(int gear, String json) {
        try {
            JSONObject o = (json == null || json.trim().isEmpty()) ? null : new JSONObject(json);
            enqueueWrite(settings.gearFrame(gear, o));
        } catch (Throwable t) {
            Log.e(TAG, "sendGearSetting failed", t);
        }
    }

    // ── Firmware update (OTA) ──

    /** @return true while a firmware flash is in progress (JS/UI guards on this). */
    boolean isOtaActive() {
        OtaEngine o = ota;
        return o != null && o.isRunning();
    }

    /**
     * Begin flashing {@code hexText} (raw Intel-HEX file text; {@code fileName} only selects the
     * VCU/BMS target). Pauses the keep-alive and live-push for the whole flash so nothing injects a
     * 0xAA command into the OTA stream, hands the write/notify path to {@link OtaEngine}, and lets
     * any in-flight normal write drain first. Null/exception-safe. Requires a live connection.
     */
    void startOta(final String hexText, final String fileName) {
        try {
            if (isOtaActive()) return;
            if (!connected || !notifyReady) {
                if (listener != null) {
                    listener.onOtaState("{\"state\":\"failed\",\"message\":\"Connect the scooter first\"}");
                }
                return;
            }
            stopKeepAlive();
            stopPush();
            synchronized (writeQueue) { writeQueue.clear(); writing = false; }
            final OtaEngine engine = new OtaEngine(otaHost);
            ota = engine;
            // A ver2 (T2/tetra) device flashes through config_dis with a 06 e2 node-select handshake;
            // the legacy (T1) path is unchanged. The flag comes from the advertised BLE name.
            final boolean ver2 = parser != null && parser.isVer2;
            // Let any in-flight normal write complete on the normal path before the engine takes over
            // (isRunning() stays false until start()); then begin.
            main.postDelayed(() -> { if (ota == engine) engine.start(hexText, fileName, ver2); }, 300);
        } catch (Throwable t) {
            Log.e(TAG, "startOta failed", t);
        }
    }

    /** @return true when the connected device advertises the ver2 (T2/tetra) platform name. */
    boolean isVer2() { return parser != null && parser.isVer2; }

    /** User-initiated abort of a running flash. The bootloader stays in receive-mode (re-flashable). */
    void cancelOta() {
        try {
            OtaEngine o = ota;
            if (o != null) o.cancel();
        } catch (Throwable t) {
            Log.e(TAG, "cancelOta failed", t);
        }
    }

    private final OtaEngine.Host otaHost = new OtaEngine.Host() {
        @Override
        public boolean writeFrame(byte[] frame) {
            return doWriteOta(frame);   // OTA path: write WITHOUT response (matches the original app)
        }

        @Override
        public void setHighPriority(boolean high) {
            try {
                BluetoothGatt g = gatt;
                if (g != null) {
                    g.requestConnectionPriority(high
                            ? BluetoothGatt.CONNECTION_PRIORITY_HIGH
                            : BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
                }
            } catch (Throwable ignored) {}
        }

        @Override
        public void progress(int percent, int packet, int count, String phase) {
            try {
                JSONObject o = new JSONObject();
                o.put("percent", percent);
                o.put("packet", packet);
                o.put("count", count);
                o.put("phase", phase == null ? "" : phase);
                if (listener != null) listener.onOtaProgress(o.toString());
            } catch (Throwable ignored) {}
        }

        @Override
        public void log(String line) {
            try { if (listener != null) listener.onOtaLog(line == null ? "" : line); } catch (Throwable ignored) {}
        }

        @Override
        public void state(String state, String message) {
            try {
                JSONObject o = new JSONObject();
                o.put("state", state == null ? "" : state);
                o.put("message", message == null ? "" : message);
                if (listener != null) listener.onOtaState(o.toString());
            } catch (Throwable ignored) {}
        }

        @Override
        public void finished(boolean success) {
            ota = null;
            // Resume normal traffic if the link is still up. On success the VCU reboots and the link
            // drops -> auto-reconnect restarts the keep-alive / push by itself.
            if (connected && notifyReady) {
                startKeepAlive();
                startPush();
            }
        }
    };

    // ── State reporting ──

    private void pushState(String status) {
        try {
            JSONObject o = new JSONObject();
            o.put("connected", connected);
            o.put("name", deviceName == null ? "" : deviceName);
            o.put("address", desiredAddress == null ? "" : desiredAddress);
            o.put("status", status == null ? "" : status);
            if (listener != null) listener.onState(o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "pushState failed", t);
        }
    }

    void shutdown() {
        stopScan();
        disconnect();
    }
}
