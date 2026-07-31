// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        // Observe navigation and mirror the current state to the dashboard banner immediately.
        NavSession.addListener(navListener);
        navListener.onNavState(NavSession.state());
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
        // The dashboard is a self-contained bundled asset page; it never needs to read other local
        // files or make cross-origin requests FROM the file:// origin. Keeping these OFF means that
        // even if a scripting bug ever landed on the page, it could not fetch("file:///...") to exfil
        // local data or reach the network cross-origin. (Both default to false on modern WebView; set
        // explicitly so the hardening is not silently lost on an older engine.)
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        // The page opens external links via LB.openUrl (system browser), never its own windows.
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Pin the privileged WebView (it holds the LB firmware/BLE bridge) to the bundled dashboard.
        // Any attempt to navigate it elsewhere is cancelled, so no remote or attacker page can ever
        // inherit the JS bridge. External links are handled explicitly by LB.openUrl, not here.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request != null ? request.getUrl() : null;
                String url = u != null ? u.toString() : "";
                return !url.startsWith("file:///android_asset/");   // true = cancel the navigation
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                // Grant location ONLY to the bundled local dashboard (its route recorder), never to
                // any other origin that might somehow request it.
                boolean local = origin != null && origin.startsWith("file://");
                callback.invoke(origin, local, false);
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

    /** Push each navigation guidance snapshot to the WebView (window.__onNav) for the dashboard banner. */
    private final NavSession.Listener navListener = new NavSession.Listener() {
        @Override
        public void onNavState(NavSession.State s) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("active", s.active);
                o.put("arrived", s.arrived);
                o.put("offRoute", s.offRoute);
                o.put("turnText", s.turnText);
                o.put("turnArrow", s.turnArrow);
                o.put("distToTurnM", s.distToTurnM);
                o.put("remainingM", s.remainingM);
                runJs("(function(){try{if(window.__onNav)window.__onNav(" + o + ");}catch(e){}})();");
            } catch (Throwable ignored) {
            }
        }
    };

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
            // Intel-HEX is ASCII. Decode as Latin-1 so every byte maps to exactly one char and the
            // line/trailer parsing sees the file unchanged.
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

    // ── Update helpers (used by the LB.checkUpdates / downloadAndInstallApk bridge) ──

    /** Enqueue the APK download into the public Downloads folder via DownloadManager (visible file +
     *  progress notification) then opens the installer once it completes. Runs on the UI thread. */
    private static final String APK_UPDATE_NAME = "laufbursche-edition-update.apk";

    private void startApkDownload(String url) {
        try {
            final android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) { toast("Download service unavailable"); return; }
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(Uri.parse(url));
            req.setTitle("Laufbursche Edition update");
            req.setDescription("Downloading the app update");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, APK_UPDATE_NAME);
            final long id = dm.enqueue(req);
            toast("Downloading update to your Downloads folder...");
            pollApkDownload(dm, id, 0);
        } catch (Throwable t) {
            Log.e(TAG, "startApkDownload failed", t);
            toast("Download failed: " + t.getMessage());
        }
    }

    /** Poll the download until it finishes, then open the installer. Polling is more reliable than the
     *  ACTION_DOWNLOAD_COMPLETE broadcast, which some ROMs (e.g. MIUI) do not deliver to app receivers. */
    private void pollApkDownload(final android.app.DownloadManager dm, final long id, final int tries) {
        if (tries > 900) return;   // ~7.5 min ceiling
        int status = -1;
        try (android.database.Cursor c =
                     dm.query(new android.app.DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                if (col >= 0) status = c.getInt(col);
            }
        } catch (Throwable ignored) {}
        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) { installDownloadedApk(dm, id); return; }
        if (status == android.app.DownloadManager.STATUS_FAILED) { toast("Download failed"); return; }
        new android.os.Handler(getMainLooper()).postDelayed(
                () -> pollApkDownload(dm, id, tries + 1), 500);
    }

    /** Launch the package installer for the finished download (with a FileProvider fallback URI). */
    private void installDownloadedApk(android.app.DownloadManager dm, long id) {
        try {
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                File f = new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), APK_UPDATE_NAME);
                if (f.exists()) uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            }
            if (uri == null) { toast("Downloaded to your Downloads folder - open it there to install"); return; }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "install after download failed", t);
            toast("Downloaded to your Downloads folder - open it there to install");
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private String installedVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    /** true when dotted version {@code latest} is strictly greater than {@code installed} (e.g. 1.0.60 > 1.0.59). */
    private static boolean isNewerVersion(String latest, String installed) {
        try {
            String[] la = latest.split("\\.");
            String[] ia = installed.split("\\.");
            int n = Math.max(la.length, ia.length);
            for (int i = 0; i < n; i++) {
                int lv = i < la.length ? parseIntSafe(la[i]) : 0;
                int iv = i < ia.length ? parseIntSafe(ia[i]) : 0;
                if (lv != iv) return lv > iv;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Throwable t) {
            return 0;
        }
    }

    private String httpGetText(String url) throws Exception {
        return new String(httpGetBytes(url), StandardCharsets.UTF_8);
    }

    /** Simple HTTP(S) GET into memory (used for the small release JSON and the firmware/APK files). */
    private byte[] httpGetBytes(String urlStr) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        try {
            c.setRequestProperty("User-Agent", "lb-edition");
            c.setRequestProperty("Accept", "application/vnd.github+json, */*");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
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

    // Firmware files are ~200 KB (a VCU .hex) up to a few MB (a raw ALI chip dump). Cap the read so
    // an accidentally-picked huge file (the SAF picker accepts any type) cannot OOM the app.
    private static final int MAX_FIRMWARE_BYTES = 16 * 1024 * 1024;

    private byte[] readAllBytes(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("no stream");
            ByteArrayOutputStream out = new ByteArrayOutputStream(131072);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                if (out.size() > MAX_FIRMWARE_BYTES) {
                    throw new Exception("file too large (over 16 MB) - not a firmware file");
                }
            }
            return out.toByteArray();
        }
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
            NavSession.removeListener(navListener);
        } catch (Throwable ignored) {
        }
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

        /** End the active turn-by-turn navigation session (stops the foreground service). */
        @JavascriptInterface
        public void endNav() {
            try {
                Log.i(TAG, "LB.endNav()");
                NavigationService.stop(MainActivity.this);
            } catch (Throwable t) {
                Log.e(TAG, "endNav failed", t);
            }
        }

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

        /** @return JSON {"address","name"} of the last connected scooter or "" if none stored. */
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

        /** Toggle traction control (TCS). Written as a[2]=5, not the generic normal write. */
        @JavascriptInterface
        public void setSmart(boolean on) {
            try {
                Log.i(TAG, "LB.setSmart(" + on + ")");
                if (ble != null) ble.setSmart(on);
            } catch (Throwable t) {
                Log.e(TAG, "setSmart bridge failed", t);
            }
        }

        /** Legacy alias: which is ignored; on=true -> dual, on=false -> rear-only cycle helper. */
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

        /** Set the VCU speed lock via cmd 0x1B (TESTLOCK firmware). true = unlock, false = lock. */
        @JavascriptInterface
        public void setLock(boolean unlocked) {
            try {
                Log.i(TAG, "LB.setLock(" + unlocked + ")");
                if (ble != null) ble.setLock(unlocked);
            } catch (Throwable t) {
                Log.e(TAG, "setLock bridge failed", t);
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

        /** Drop the in-memory firmware blob after a flash completes or fails. The picked image only ever
         *  lives in these two String fields (never written to disk); clearing them means a new flash
         *  always requires a fresh pick, so no stale image lingers. */
        @JavascriptInterface
        public void otaClear() {
            otaHexText = null;
            otaFileName = null;
        }

        /** Abort a running flash. The controller stays in bootloader receive-mode (re-flashable).
         *  Runs on the UI/main thread so the engine's cancel + finish (which touch the main-looper
         *  timers and push the 'cancelled' state) run on the same thread as the flash itself. */
        @JavascriptInterface
        public void otaCancel() {
            Log.i(TAG, "LB.otaCancel()");
            runOnUiThread(() -> {
                try {
                    if (ble != null) ble.cancelOta();
                } catch (Throwable t) {
                    Log.e(TAG, "otaCancel failed", t);
                }
            });
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
         * {@code speedLimit, eabsRegen, frontStartLevel, rearStartLevel, frontCurrent,
         * rearCurrent}; missing fields fall back to the current-gear state. Null/exception-safe.
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

        // ── App update ──

        /** Fetch the latest app release from GitHub and push it to window.__onAppUpdate. Network runs off
         *  the main thread. Never throws. This checks for an app version only: firmware is not
         *  distributed here. */
        @JavascriptInterface
        public void checkUpdates() {
            new Thread(() -> {
                // Latest app release - from GitHub.
                String result = "{\"available\":false}";
                try {
                    JSONObject rel = new JSONObject(httpGetText(
                            "https://api.github.com/repos/Laufbursche42/tr-lb-edition/releases/latest"));
                    String tag = rel.optString("tag_name", "");
                    String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                    String apkUrl = "";
                    org.json.JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a == null) continue;
                            if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }
                    boolean newer = !apkUrl.isEmpty() && isNewerVersion(latest, installedVersionName());
                    JSONObject o = new JSONObject();
                    o.put("available", newer);
                    o.put("version", latest);
                    o.put("url", apkUrl);
                    result = o.toString();
                } catch (Throwable t) {
                    Log.e(TAG, "checkUpdates failed", t);
                }
                final String r = result;
                runJs("(function(){try{if(window.__onAppUpdate)window.__onAppUpdate(" + r + ");}catch(e){}})();");
            }).start();
        }

        /** Download the app APK into the public Downloads folder via the system DownloadManager (which
         *  shows a download notification), then open the installer when it finishes. Using DownloadManager
         *  guarantees the file is a real, visible file in Downloads and yields an installable content URI. */
        @JavascriptInterface
        public void downloadAndInstallApk(final String url) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        toast("Allow installs for this app, then tap download again");
                    } catch (Throwable t) {
                        Log.e(TAG, "unknown-sources prompt failed", t);
                    }
                });
                return;
            }
            runOnUiThread(() -> startApkDownload(url));
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

        /** Delete every recorded ride. */
        @JavascriptInterface
        public void deleteAllRides() {
            try {
                Log.i(TAG, "LB.deleteAllRides()");
                if (rideLogger != null) rideLogger.deleteAllRides();
            } catch (Throwable t) {
                Log.e(TAG, "deleteAllRides bridge failed", t);
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

        /**
         * Write GPX text into the phone's public Downloads folder. Synchronous so the page can
         * report the real outcome instead of assuming one.
         *
         * @return JSON {@code {"ok":true,"name":"<file actually written>"}} or
         * {@code {"ok":false,"name":"<requested>","error":"<code>"}} with code "downloads"
         * (no access to the folder), "createfile", "writer" or "save".
         */
        @JavascriptInterface
        public String saveGpxToDownloads(final String fileName, final String content) {
            final String name = safeGpxName(fileName);
            try {
                Log.i(TAG, "LB.saveGpxToDownloads(" + name + ")");
                if (content == null) return gpxResult(false, name, "save");
                return saveGpxViaMediaStore(name, content);
            } catch (Throwable t) {
                Log.e(TAG, "saveGpxToDownloads failed", t);
                return gpxResult(false, name, "save");
            }
        }
    }

    /** Downloads collection write through MediaStore. Needs no storage permission. */
    private String saveGpxViaMediaStore(String name, String content) {
        ContentResolver cr = getContentResolver();
        Uri item = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            // Pending keeps a half-written file invisible; MediaStore also de-duplicates the name.
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (item == null) return gpxResult(false, name, "createfile");

            OutputStream out = cr.openOutputStream(item);
            if (out == null) {
                cr.delete(item, null, null);
                return gpxResult(false, name, "writer");
            }
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(item, done, null, null);
            return gpxResult(true, gpxDisplayName(cr, item, name), null);
        } catch (Throwable t) {
            Log.e(TAG, "saveGpx MediaStore write failed", t);
            if (item != null) {
                try {
                    cr.delete(item, null, null);
                } catch (Throwable ignored) {
                }
            }
            return gpxResult(false, name, "save");
        }
    }


    /** The name MediaStore settled on, which differs from the request when it de-duplicated. */
    private String gpxDisplayName(ContentResolver cr, Uri uri, String fallback) {
        try (Cursor c = cr.query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable t) {
            Log.e(TAG, "saveGpx name lookup failed", t);
        }
        return fallback;
    }

    /** Never overwrite an existing export: ride.gpx becomes ride (1).gpx. */
    private static File gpxFreeFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return f;
    }

    /** Keep the page's name from escaping the Downloads folder or losing its extension. */
    private static String safeGpxName(String raw) {
        String s = raw == null ? "" : raw.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        if (s.isEmpty()) s = "teverun";
        if (!s.toLowerCase(Locale.US).endsWith(".gpx")) s = s + ".gpx";
        return s;
    }

    private static String gpxResult(boolean ok, String name, String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("name", name == null ? "" : name);
            if (error != null) o.put("error", error);
            return o.toString();
        } catch (Throwable t) {
            return ok ? "{\"ok\":true}" : "{\"ok\":false}";
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
