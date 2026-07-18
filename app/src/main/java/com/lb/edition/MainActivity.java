// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Host activity: a full-screen WebView that renders the offline dashboard
 * (file:///android_asset/dashboard/telemetry.html) and exposes a "LB" JavaScript
 * bridge for BLE, settings, motor control and SRT streaming.
 *
 * The bridge forwards to the native BLE layer (BleManager / FrameParser / CommandBuilder) and to
 * the com.lb.srt streaming module. Live telemetry is pushed back to the WebView as localStorage
 * ['lb_live_data'] (plus window.__onBleData); scan results via window.__onBleScan; connection state
 * via window.__onBleState. Every bridge method is null/exception-safe and never throws across JS.
 */
public class MainActivity extends Activity {

    private static final String TAG = "lbedition";
    private static final int REQ_PERMS = 4711;
    private static final int REQ_OTA_FILE = 4712;

    // Picked firmware file, held natively until the user starts the flash (never round-trips through
    // JS - only its metadata does). Guarded by the main thread (set in onActivityResult, read in the
    // otaStart bridge). max ~200 KB of hex text.
    private volatile String otaHexText = null;
    private volatile String otaFileName = null;

    private WebView webView;
    private BleManager ble;
    private DebugLog debugLog;
    private RideLogger rideLogger;
    private SharedPreferences prefs;
    // Auto-reconnect to the remembered scooter is attempted at most once per process.
    private boolean autoConnectTried = false;
    // Tracks the BLE connection state so ride-logging transitions fire exactly once per change.
    // Volatile: onState() may run on the BLE binder thread or the main thread.
    private volatile boolean rideConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on while riding / dashboard is visible.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        // Full-screen (immersive) is user-toggleable and persisted; default OFF. When off, the
        // Android status bar (battery / clock / notifications) shows and content sits below it.
        // Delegated to UiChrome so every screen (main + native) shares the exact same inset logic.
        prefs = getSharedPreferences("lb", MODE_PRIVATE);
        UiChrome.applyFullscreen(this);

        configureWebView();
        webView.addJavascriptInterface(new LbBridge(), "LB");
        webView.loadUrl("file:///android_asset/dashboard/telemetry.html");

        ble = new BleManager(this, bleListener);

        // Ride logging: native NDJSON ride recorder, driven from the BLE listener callbacks below.
        rideLogger = new RideLogger(getApplicationContext());

        // Debug logging: persistent across restarts (SharedPreferences key lb_debug).
        // Resume capture immediately if the user left it enabled.
        debugLog = new DebugLog(getApplicationContext());
        try {
            if (debugLog.isEnabled()) debugLog.start();
        } catch (Throwable t) {
            Log.e(TAG, "debug resume failed", t);
        }

        requestRuntimePermissions();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Re-apply the shared window chrome on regaining focus (e.g. after a dialog) so the immersive
        // / status-bar state survives - identical to NavActivity and MapDownloadActivity.
        if (hasFocus) UiChrome.applyFullscreen(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // First time we resume with BT permission available, reconnect to the remembered scooter.
        if (!autoConnectTried) {
            boolean btOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (btOk && ble != null) {
                autoConnectTried = true;
                ble.connectLast();
            }
        }
    }

    @SuppressWarnings({"SetJavaScriptEnabled"})
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                // Local GPS only; grant to the dashboard's route recorder.
                callback.invoke(origin, true, false);
            }
        });
    }

    // ── Java -> JS bridge (all on the UI thread) ──

    private void runJs(final String js) {
        if (webView == null || js == null) return;
        webView.post(() -> {
            try {
                if (webView != null) webView.evaluateJavascript(js, null);
            } catch (Throwable t) {
                Log.e(TAG, "evaluateJavascript failed", t);
            }
        });
    }

    private final BleManager.Listener bleListener = new BleManager.Listener() {
        @Override
        public void onScanResults(String jsonArray) {
            if (jsonArray == null) return;
            runJs("(function(){try{if(window.__onBleScan)window.__onBleScan(" + jsonArray + ");}catch(e){}})();");
        }

        @Override
        public void onState(String json) {
            if (json == null) return;
            runJs("(function(){try{if(window.__onBleState)window.__onBleState(" + json + ");}catch(e){}})();");
            // Drive the ride logger on connect/disconnect transitions (fires once per change).
            try {
                boolean nowConnected = new JSONObject(json).optBoolean("connected", false);
                if (nowConnected != rideConnected) {
                    rideConnected = nowConnected;
                    if (rideLogger != null) {
                        if (nowConnected) rideLogger.onConnected();
                        else rideLogger.onDisconnected();
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "ride state wiring failed", t);
            }
        }

        @Override
        public void onLiveData(String json) {
            if (json == null) return;
            // Write the JSON string to localStorage['lb_live_data'] (dashboard's tickBLE reads it)
            // and also call window.__onBleData(json) if present.
            runJs("(function(){try{var d=" + json + ";var s=JSON.stringify(d);"
                    + "try{localStorage.setItem('lb_live_data',s);}catch(e){}"
                    + "if(window.__onBleData){try{window.__onBleData(s);}catch(e){}}}catch(e){}})();");
            // Feed the latest snapshot to the ride logger (arms/samples the ride).
            try {
                if (rideLogger != null) rideLogger.onLiveData(json);
            } catch (Throwable t) {
                Log.e(TAG, "ride live-data wiring failed", t);
            }
        }

        // ── Firmware update (OTA) -> WebView ──
        @Override
        public void onOtaProgress(String json) {
            if (json == null) return;
            runJs("(function(){try{if(window.__onOtaProgress)window.__onOtaProgress(" + json + ");}catch(e){}})();");
        }

        @Override
        public void onOtaLog(String line) {
            String s = line == null ? "" : line;
            // Persist OTA log lines to the debug log too (when enabled), so a flash done away from the
            // PC can be pulled afterwards: adb pull the debug log or export it in-app.
            try { if (debugLog != null && debugLog.isEnabled()) debugLog.append("[ota] " + s); } catch (Throwable ignored) {}
            runJs("(function(){try{if(window.__onOtaLog)window.__onOtaLog(" + org.json.JSONObject.quote(s) + ");}catch(e){}})();");
        }

        @Override
        public void onOtaState(String json) {
            if (json == null) return;
            runJs("(function(){try{if(window.__onOtaState)window.__onOtaState(" + json + ");}catch(e){}})();");
        }
    };

    // ── Runtime permissions ──
    private void requestRuntimePermissions() {
        List<String> want = new ArrayList<>();
        want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        want.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            want.add(Manifest.permission.BLUETOOTH_SCAN);
            want.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            want.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> missing = new ArrayList<>();
        for (String p : want) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            for (int i = 0; i < permissions.length; i++) {
                Log.i(TAG, "perm " + permissions[i] + " -> " + grantResults[i]);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OTA_FILE) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pushOtaFile("{\"ok\":false,\"cancelled\":true}");
            return;
        }
        Uri uri = data.getData();
        try {
            String name = queryDisplayName(uri);
            byte[] bytes = readAllBytes(uri);
            // Intel-HEX is ASCII; the original reads it as Latin-1 (byte -> char). Match that so the
            // trailer/line parsing is byte-identical.
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            otaHexText = text;
            otaFileName = name;
            // Report parsed metadata (no BLE, no side effects) so the UI can show version/CRC/target.
            String meta = OtaEngine.inspect(text, name);
            pushOtaFile(meta);
        } catch (Throwable t) {
            Log.e(TAG, "ota file read failed", t);
            otaHexText = null;
            otaFileName = null;
            pushOtaFile("{\"ok\":false,\"error\":\"read failed\"}");
        }
    }

    /** Push the picked-file metadata (JSON) to the WebView (window.__onOtaFile). */
    private void pushOtaFile(String json) {
        runJs("(function(){try{if(window.__onOtaFile)window.__onOtaFile(" + json + ");}catch(e){}})();");
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "firmware.hex" : last;
    }

    private byte[] readAllBytes(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("no stream");
            ByteArrayOutputStream out = new ByteArrayOutputStream(131072);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }

    /** Read a bundled asset (e.g. a firmware under assets/firmware/) fully into memory. */
    private byte[] readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(262144);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }

    /** Descriptive output name, e.g. AWIVCU_R5.4.19_Unlocked_BlinkerFix_20260718_0142.hex */
    private String buildPatchName(String base, java.util.List<String> tags) {
        StringBuilder sb = new StringBuilder(base);
        for (String t : tags) sb.append('_').append(t);
        sb.append('_').append(new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                .format(new java.util.Date()));
        return sb.append(".hex").toString();
    }

    /** Minimal JSON string escaper for bridge error messages. */
    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    @Override
    protected void onDestroy() {
        try {
            if (ble != null) ble.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            // Safety net: finalize any active ride and stop the foreground service on teardown.
            if (rideLogger != null) rideLogger.onDisconnected();
        } catch (Throwable ignored) {
        }
        try {
            if (debugLog != null) debugLog.stop();
        } catch (Throwable ignored) {
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * The "LB" JavaScript bridge. Forwards to the native BLE layer and the SRT streaming module.
     * Every method is null/exception-safe - nothing throws across the bridge.
     */
    private class LbBridge {

        @JavascriptInterface
        public void scan() {
            try {
                Log.i(TAG, "LB.scan()");
                if (ble != null) ble.scan();
            } catch (Throwable t) {
                Log.e(TAG, "scan bridge failed", t);
            }
        }

        @JavascriptInterface
        public void stopScan() {
            try {
                Log.i(TAG, "LB.stopScan()");
                if (ble != null) ble.stopScan();
            } catch (Throwable t) {
                Log.e(TAG, "stopScan bridge failed", t);
            }
        }

        @JavascriptInterface
        public void connect(String addr) {
            try {
                Log.i(TAG, "LB.connect(" + addr + ")");
                if (ble != null) ble.connect(addr);
            } catch (Throwable t) {
                Log.e(TAG, "connect bridge failed", t);
            }
        }

        /** Connect and seed the already-known BLE name so the FIN / Bluetooth name shows at once. */
        @JavascriptInterface
        public void connect(String addr, String name) {
            try {
                Log.i(TAG, "LB.connect(" + addr + ", " + name + ")");
                if (ble != null) ble.connect(addr, name);
            } catch (Throwable t) {
                Log.e(TAG, "connect bridge failed", t);
            }
        }

        @JavascriptInterface
        public void disconnect() {
            try {
                Log.i(TAG, "LB.disconnect()");
                if (ble != null) ble.disconnect();
            } catch (Throwable t) {
                Log.e(TAG, "disconnect bridge failed", t);
            }
        }

        /** @return JSON {"address","name"} of the last connected scooter, or "" if none stored. */
        @JavascriptInterface
        public String lastDevice() {
            try {
                return ble.lastDeviceJson();
            } catch (Throwable t) {
                return "";
            }
        }

        @JavascriptInterface
        public void sendSetting(String json) {
            try {
                Log.i(TAG, "LB.sendSetting(" + json + ")");
                if (ble != null) ble.sendSetting(json);
            } catch (Throwable t) {
                Log.e(TAG, "sendSetting bridge failed", t);
            }
        }

        @JavascriptInterface
        public void setMotorMode(int mode) {
            try {
                Log.i(TAG, "LB.setMotorMode(" + mode + ")");
                if (ble != null) ble.setMotorMode(mode);
            } catch (Throwable t) {
                Log.e(TAG, "setMotorMode bridge failed", t);
            }
        }

        /** Toggle SMART/TCS traction control. Written as a[2]=5, not the generic normal write. */
        @JavascriptInterface
        public void setSmart(boolean on) {
            try {
                Log.i(TAG, "LB.setSmart(" + on + ")");
                if (ble != null) ble.setSmart(on);
            } catch (Throwable t) {
                Log.e(TAG, "setSmart bridge failed", t);
            }
        }

        /** Legacy alias: which is ignored; on=false -> dual, on=true -> rear-only cycle helper. */
        @JavascriptInterface
        public void setMotor(int which, boolean on) {
            try {
                Log.i(TAG, "LB.setMotor(which=" + which + ", on=" + on + ")");
                if (ble != null) ble.setMotorMode(on ? 0 : 1);
            } catch (Throwable t) {
                Log.e(TAG, "setMotor bridge failed", t);
            }
        }

        @JavascriptInterface
        public void setCustomKey(int value) {
            try {
                Log.i(TAG, "LB.setCustomKey(" + value + ")");
                if (ble != null) ble.setCustomKey(value);
            } catch (Throwable t) {
                Log.e(TAG, "setCustomKey bridge failed", t);
            }
        }

        /** Set the VCU identity / BLE name via cmd 0x1f (Gate-1 toggle; reversible). Null-safe. */
        @JavascriptInterface
        public void setBleName(String name) {
            try {
                Log.i(TAG, "LB.setBleName(" + name + ")");
                if (ble != null) ble.setBleName(name);
            } catch (Throwable t) {
                Log.e(TAG, "setBleName bridge failed", t);
            }
        }

        // ── Firmware update (OTA) ──

        /** Open the system file picker to choose a firmware .hex; result comes back via __onOtaFile. */
        @JavascriptInterface
        public void otaPickFile() {
            Log.i(TAG, "LB.otaPickFile()");
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");   // .hex has no registered MIME; accept any and parse/validate it
                    startActivityForResult(i, REQ_OTA_FILE);
                } catch (Throwable t) {
                    Log.e(TAG, "otaPickFile failed", t);
                    pushOtaFile("{\"ok\":false,\"error\":\"no file picker\"}");
                }
            });
        }

        /**
         * Patch a bundled VCU firmware and hand the result to the flash flow, exactly as if the user
         * had picked it (sets {@code otaHexText} and pushes the metadata to {@code __onOtaFile}).
         * fwId: "r5" (R5.4.19), "d5" (D5.4.14), "ali" (open ALI D3.4.12, convert only).
         * speedMode (r5): "unlock" full / "live" Gate-2 only (FIN stays the switch) / "capped" keep ~22.
         * d5: "unlock" or "off". Returns the same metadata JSON so the patcher page can show a summary.
         */
        @JavascriptInterface
        public String patchFirmware(String fwId, String speedMode, boolean zerostart, boolean cruise,
                                    boolean blinker, boolean wheel) {
            Log.i(TAG, "LB.patchFirmware(" + fwId + "," + speedMode + ",z=" + zerostart + ",c=" + cruise
                    + ",b=" + blinker + ",w=" + wheel + ")");
            try {
                String asset, baseLabel;
                boolean isHex;
                if ("r5".equals(fwId)) { asset = "firmware/vcu_r5_4_19.hex"; baseLabel = "AWIVCU_R5.4.19"; isHex = true; }
                else if ("ali".equals(fwId)) { asset = "firmware/vcu_ali_d3_4_12.bin"; baseLabel = "AWIVCU_ALI_D3_4_12"; isHex = false; }
                else return "{\"ok\":false,\"error\":\"unknown firmware\"}";

                byte[] raw = readAsset(asset);
                FirmwarePatcher fp = isHex
                        ? FirmwarePatcher.fromHex(new String(raw, StandardCharsets.ISO_8859_1))
                        : FirmwarePatcher.fromAliDump(raw);

                java.util.List<String> tags = new java.util.ArrayList<>();
                if ("r5".equals(fwId)) {
                    if ("live".equals(speedMode)) {
                        // Gate 2 out -> the FIN identity is the live 22/open switch. ZeroStart and Cruise
                        // come free with the FIN unlock on modern units, so they are UI markers only (no
                        // patch); Charge-1 units need Full unlock + ZeroStart instead. WheelDiameter does
                        // not persist under Live toggle (the UI greys it out), so it is not applied here.
                        fp.applyR519LiveToggle();
                        tags.add("LiveFIN");
                    } else if ("unlock".equals(speedMode)) {
                        fp.applyR519Unlock(zerostart);   // full speed; bit5=0 for Charge-1 kickstart
                        tags.add("Unlocked");
                        if (zerostart) tags.add("ZeroStart");
                        if (wheel) { fp.applyWheelDiameter(); tags.add("WheelDia"); }
                    } else {
                        // Keep ~22 (stock speed). WheelDiameter is the only clamp-independent option here.
                        if (wheel) { fp.applyWheelDiameter(); tags.add("WheelDia"); }
                    }
                    if (blinker) { fp.applyR519Blinker(); tags.add("BlinkerFix"); }
                }
                // ALI is open (convert only); R5 with nothing selected -> the unmodified original.
                if ("ali".equals(fwId)) tags.add("Converted");
                else if (tags.isEmpty()) tags.add("Original");

                String hex = fp.buildHex();
                String name = buildPatchName(baseLabel, tags);
                otaHexText = hex;
                otaFileName = name;
                // Return the parsed metadata; the patcher page hands it to __onOtaFile after it opens
                // the update page, so the reset inside openFirmwarePage cannot wipe the selection.
                return OtaEngine.inspect(hex, name);
            } catch (Throwable t) {
                Log.e(TAG, "patchFirmware failed", t);
                return "{\"ok\":false,\"error\":" + jsonStr(String.valueOf(t.getMessage())) + "}";
            }
        }

        /** Begin flashing the previously picked firmware file. Progress via __onOtaProgress/State/Log. */
        @JavascriptInterface
        public void otaStart() {
            Log.i(TAG, "LB.otaStart()");
            try {
                final String text = otaHexText;
                final String name = otaFileName;
                if (text == null || text.isEmpty()) {
                    runJs("(function(){try{if(window.__onOtaState)window.__onOtaState({state:'failed',message:'Pick a firmware file first'});}catch(e){}})();");
                    return;
                }
                if (ble != null) ble.startOta(text, name);
            } catch (Throwable t) {
                Log.e(TAG, "otaStart failed", t);
            }
        }

        /** Abort a running flash. The controller stays in bootloader receive-mode (re-flashable). */
        @JavascriptInterface
        public void otaCancel() {
            Log.i(TAG, "LB.otaCancel()");
            try {
                if (ble != null) ble.cancelOta();
            } catch (Throwable t) {
                Log.e(TAG, "otaCancel failed", t);
            }
        }

        /** @return true while a firmware flash is in progress. */
        @JavascriptInterface
        public boolean isOtaActive() {
            try {
                return ble != null && ble.isOtaActive();
            } catch (Throwable t) {
                return false;
            }
        }

        /** @return true when the connected scooter is a ver2 (T2/tetra) platform - it flashes every node
         *  through the display block with a node-select handshake, so the update page shows a ver2 note. */
        @JavascriptInterface
        public boolean isVer2() {
            try { return ble != null && ble.isVer2(); } catch (Throwable t) { return false; }
        }

        /**
         * Write ONE gear/assist profile (BLE_PROTOCOL §3.4). {@code json} carries any of
         * {@code speedLimit, eabsLevel, fStartLevel, rStartLevel, fCurrent, rCurrent}; missing
         * fields fall back to the maintained current-gear state. Null/exception-safe.
         */
        @JavascriptInterface
        public void sendGearSetting(int gear, String json) {
            try {
                Log.i(TAG, "LB.sendGearSetting(gear=" + gear + ", " + json + ")");
                if (ble != null) ble.sendGearSetting(gear, json);
            } catch (Throwable t) {
                Log.e(TAG, "sendGearSetting bridge failed", t);
            }
        }

        /** Open the native offline navigation screen (map + bike routing + camping/charging POIs). */
        @JavascriptInterface
        public void openNavigation() {
            Log.i(TAG, "LB.openNavigation()");
            try {
                Intent i = new Intent(MainActivity.this, NavActivity.class);
                startActivity(i);
            } catch (Throwable t) {
                Log.e(TAG, "openNavigation failed", t);
            }
        }

        /**
         * Show a recorded ride on the native offline map (instead of Google Maps). Opens
         * {@link NavActivity} in display-only mode with the track passed as a JSON array of
         * {@code {lat, lon}} points.
         */
        @JavascriptInterface
        public void showRouteOnMap(final String pointsJson) {
            Log.i(TAG, "LB.showRouteOnMap(" + (pointsJson == null ? 0 : pointsJson.length()) + " chars)");
            try {
                Intent i = new Intent(MainActivity.this, NavActivity.class);
                i.putExtra(NavActivity.EXTRA_TRACK, pointsJson);
                startActivity(i);
            } catch (Throwable t) {
                Log.e(TAG, "showRouteOnMap failed", t);
            }
        }

        @JavascriptInterface
        public void startStream(String url) {
            Log.i(TAG, "LB.startStream(" + url + ")");
            try {
                if (url == null || url.trim().isEmpty()) {
                    Log.w(TAG, "startStream: empty url, ignoring");
                    return;
                }
                Intent i = new Intent(MainActivity.this, com.lb.srt.StreamActivity.class);
                i.putExtra("url", url);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                Log.e(TAG, "startStream failed", t);
            }
        }

        @JavascriptInterface
        public void stopStream() {
            Log.i(TAG, "LB.stopStream()");
            try {
                Intent i = new Intent(MainActivity.this, com.lb.srt.StreamService.class);
                i.setAction("STOP");
                startService(i);
            } catch (Throwable t) {
                Log.e(TAG, "stopStream failed", t);
            }
        }

        @JavascriptInterface
        public String streamStatus() {
            try {
                String s = com.lb.srt.StreamService.getStatus();
                return s == null ? "" : s;
            } catch (Throwable t) {
                Log.e(TAG, "streamStatus failed", t);
                return "";
            }
        }

        @JavascriptInterface
        public String encrypt(String plain) {
            try {
                return com.lb.srt.SrtCrypto.encrypt(plain);
            } catch (Throwable t) {
                Log.e(TAG, "encrypt failed", t);
                return null;
            }
        }

        @JavascriptInterface
        public String decrypt(String stored) {
            try {
                return com.lb.srt.SrtCrypto.decrypt(stored);
            } catch (Throwable t) {
                Log.e(TAG, "decrypt failed", t);
                return null;
            }
        }

        @JavascriptInterface
        public void log(String s) {
            Log.i(TAG, "LB.log: " + s);
            try {
                if (debugLog != null && debugLog.isEnabled()) debugLog.append(s);
            } catch (Throwable ignored) {
            }
        }

        /** @return the app version as "vNAME (build CODE)", read from the package info. */
        @JavascriptInterface
        public String appVersion() {
            try {
                android.content.pm.PackageInfo pi =
                        getPackageManager().getPackageInfo(getPackageName(), 0);
                long code = (android.os.Build.VERSION.SDK_INT >= 28)
                        ? pi.getLongVersionCode() : pi.versionCode;
                return "v" + pi.versionName + " (build " + code + ")";
            } catch (Throwable t) {
                return "";
            }
        }

        /** Toggle immersive full-screen (persisted; survives restarts). Persist the pref, then let
         *  UiChrome re-apply the shared immersive + inset-padding logic on the UI thread. */
        @JavascriptInterface
        public void setFullscreen(final boolean on) {
            try {
                if (prefs != null) prefs.edit().putBoolean("fullscreen", on).apply();
                runOnUiThread(() -> UiChrome.applyFullscreen(MainActivity.this));
            } catch (Throwable t) {
                Log.e(TAG, "setFullscreen bridge failed", t);
            }
        }

        /** @return whether immersive full-screen is currently enabled (default false). */
        @JavascriptInterface
        public boolean isFullscreen() {
            try {
                return prefs != null && prefs.getBoolean("fullscreen", false);
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * Persist the app theme so the native screens (NavActivity / MapDownloadActivity chrome)
         * follow the dashboard's light/dark toggle. Stored in the "lb" prefs, default true (dark).
         */
        @JavascriptInterface
        public void setTheme(boolean dark) {
            try {
                Log.i(TAG, "LB.setTheme(dark=" + dark + ")");
                if (prefs != null) prefs.edit().putBoolean("theme_dark", dark).apply();
            } catch (Throwable t) {
                Log.e(TAG, "setTheme bridge failed", t);
            }
        }

        /** Copy text to the Android clipboard (on the UI thread; null/exception-safe). */
        @JavascriptInterface
        public void copyClipboard(final String text) {
            if (text == null) return;
            runOnUiThread(() -> {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("TR-LB", text));
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "copyClipboard failed", t);
                }
            });
        }

        // ── Debug logging ──

        /** Turn persistent debug logging on/off (persisted; survives restarts). */
        @JavascriptInterface
        public void setDebug(boolean on) {
            try {
                Log.i(TAG, "LB.setDebug(" + on + ")");
                if (debugLog != null) debugLog.setEnabled(on);
            } catch (Throwable t) {
                Log.e(TAG, "setDebug bridge failed", t);
            }
        }

        /** @return whether debug logging is currently enabled. */
        @JavascriptInterface
        public boolean isDebug() {
            try {
                return debugLog != null && debugLog.isEnabled();
            } catch (Throwable t) {
                Log.e(TAG, "isDebug bridge failed", t);
                return false;
            }
        }

        /** Share the debug log file via a system chooser (email/message/etc.). */
        @JavascriptInterface
        public void exportLogs() {
            runOnUiThread(() -> {
                try {
                    File f = debugLog != null ? debugLog.getLogFile() : null;
                    if (f == null || !f.exists() || f.length() == 0) {
                        Toast.makeText(MainActivity.this, "No logs yet", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Uri uri = FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".fileprovider", f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, "TR-LB Edition debug log");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(send, "Send debug log");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                } catch (Throwable t) {
                    Log.e(TAG, "exportLogs failed", t);
                    try {
                        Toast.makeText(MainActivity.this, "Export failed", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        // ── Ride logging ──

        /** @return whether ride logging is currently enabled (persisted; default false). */
        @JavascriptInterface
        public boolean isRideLogging() {
            try {
                return rideLogger != null && rideLogger.isEnabled();
            } catch (Throwable t) {
                Log.e(TAG, "isRideLogging bridge failed", t);
                return false;
            }
        }

        /** Turn ride logging on/off (persisted). Requests notification permission on first enable. */
        @JavascriptInterface
        public void setRideLogging(boolean on) {
            try {
                Log.i(TAG, "LB.setRideLogging(" + on + ")");
                if (rideLogger != null) rideLogger.setEnabled(on);
                if (on) maybeRequestPostNotifications();
            } catch (Throwable t) {
                Log.e(TAG, "setRideLogging bridge failed", t);
            }
        }

        /**
         * @return a JSON array string of the (at most 3) newest rides, newest first. Each entry:
         * {@code {"id","start","end","durationSec","distanceKm","samples"}}. "[]" if none.
         */
        @JavascriptInterface
        public String listRides() {
            try {
                String s = rideLogger != null ? rideLogger.listRides() : "[]";
                return s;
            } catch (Throwable t) {
                Log.e(TAG, "listRides bridge failed", t);
                return "[]";
            }
        }

        /** Delete a recorded ride by id. No-op if the id is unknown / invalid. */
        @JavascriptInterface
        public void deleteRide(String id) {
            try {
                Log.i(TAG, "LB.deleteRide(" + id + ")");
                if (rideLogger != null) rideLogger.deleteRide(id);
            } catch (Throwable t) {
                Log.e(TAG, "deleteRide bridge failed", t);
            }
        }

        /** Open an external http(s) URL in the system browser. Ignores anything that is not http/https. */
        @JavascriptInterface
        public void openUrl(final String url) {
            try {
                Log.i(TAG, "LB.openUrl(" + url + ")");
                if (url == null) return;
                final String u = url.trim();
                if (!u.startsWith("http://") && !u.startsWith("https://")) return;
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        Log.e(TAG, "openUrl start failed", t);
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "openUrl bridge failed", t);
            }
        }

        /** Export a ride ("csv"/"json") and share it via the system chooser. No-op if id is unknown. */
        @JavascriptInterface
        public void exportRide(final String id, final String format) {
            Log.i(TAG, "LB.exportRide(" + id + ", " + format + ")");
            runOnUiThread(() -> {
                try {
                    File f = rideLogger != null ? rideLogger.exportRide(id, format) : null;
                    if (f == null || !f.exists() || f.length() == 0) {
                        Toast.makeText(MainActivity.this, "Ride not found", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean csv = "csv".equalsIgnoreCase(format);
                    Uri uri = FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".fileprovider", f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(csv ? "text/csv" : "application/json");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, "Laufbursche Edition ride log");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(send, "Share ride");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                } catch (Throwable t) {
                    Log.e(TAG, "exportRide failed", t);
                    try {
                        Toast.makeText(MainActivity.this, "Export failed", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    /** Best-effort: ask for POST_NOTIFICATIONS when ride logging is first enabled (Android 13+). */
    private void maybeRequestPostNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> {
                    try {
                        requestPermissions(
                                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }
}
