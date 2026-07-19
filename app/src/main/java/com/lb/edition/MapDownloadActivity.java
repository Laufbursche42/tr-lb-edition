// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.map.reader.MapFile;

/**
 * In-app offline map download manager (Priority 1).
 *
 * <p>Lists EU countries and downloads the ready-made Mapsforge {@code .map} offline vector map for
 * each - directly over HTTPS with a progress bar - into
 * {@code getExternalFilesDir("nav")/maps/<country>.map}. No adb, no manual data steps. Shows the
 * downloaded size, lets the user pick the ACTIVE map used by {@link NavActivity}, and delete maps.
 *
 * <p>Source mirror: the Mapsforge download mirror at hs-esslingen.de (per-country v5 europe maps).
 * All data © OpenStreetMap contributors (ODbL).
 */
public class MapDownloadActivity extends Activity {

    private static final String TAG = "lbmapdl";

    /** Per-country Mapsforge v5 europe map mirror. */
    static final String MAP_BASE_URL =
            "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/europe/";

    /** Per-country POI databases (camping + EV charging) on this project's GitHub Releases. */
    static final String POI_BASE_URL =
            "https://github.com/Laufbursche42/tr-lb-edition/releases/download/poi/";

    /** {display name, file base (== mirror filename without .map), approx download size}. */
    private static final String[][] COUNTRIES = {
            {"Albania", "albania", "41 MB"},
            {"Andorra", "andorra", "2.4 MB"},
            {"Austria", "austria", "522 MB"},
            {"Belgium", "belgium", "486 MB"},
            {"Bosnia-Herzegovina", "bosnia-herzegovina", "147 MB"},
            {"Bulgaria", "bulgaria", "139 MB"},
            {"Croatia", "croatia", "164 MB"},
            {"Cyprus", "cyprus", "30 MB"},
            {"Czech Republic", "czech-republic", "573 MB"},
            {"Denmark", "denmark", "317 MB"},
            {"Estonia", "estonia", "103 MB"},
            {"Finland", "finland", "651 MB"},
            {"France", "france", "3.2 GB"},
            {"Germany", "germany", "3.0 GB"},
            {"Great Britain", "great-britain", "1.5 GB"},
            {"Greece", "greece", "251 MB"},
            {"Hungary", "hungary", "247 MB"},
            {"Iceland", "iceland", "91 MB"},
            {"Ireland & N. Ireland", "ireland-and-northern-ireland", "312 MB"},
            {"Italy", "italy", "1.5 GB"},
            {"Latvia", "latvia", "103 MB"},
            {"Liechtenstein", "liechtenstein", "2.1 MB"},
            {"Lithuania", "lithuania", "180 MB"},
            {"Luxembourg", "luxembourg", "32 MB"},
            {"Malta", "malta", "6.2 MB"},
            {"Moldova", "moldova", "81 MB"},
            {"Monaco", "monaco", "342 KB"},
            {"Montenegro", "montenegro", "26 MB"},
            {"Netherlands", "netherlands", "890 MB"},
            {"North Macedonia", "macedonia", "23 MB"},
            {"Norway", "norway", "1.7 GB"},
            {"Poland", "poland", "1.5 GB"},
            {"Portugal", "portugal", "343 MB"},
            {"Romania", "romania", "276 MB"},
            {"Serbia", "serbia", "187 MB"},
            {"Slovakia", "slovakia", "218 MB"},
            {"Slovenia", "slovenia", "225 MB"},
            {"Spain", "spain", "1.1 GB"},
            {"Sweden", "sweden", "776 MB"},
            {"Switzerland", "switzerland", "312 MB"},
            {"Ukraine", "ukraine", "815 MB"},
    };

    private File navDir;
    private File mapsDir;
    private File segmentsDir;

    private final List<Row> rows = new ArrayList<>();
    private TextView storageLine;

    // ── Routing-data (BRouter .rd5 segment) management card, above the country list ──
    private TextView routingState;      // "N routing tiles • total size" / "No routing data yet"
    private TextView routingStatus;     // live progress / hint line
    private ProgressBar routingProgress;
    private Button routingDownloadBtn;
    private Button routingDeleteBtn;
    // True while the routing-data download is being prepared or is running in MapDownloadService.
    private volatile boolean routingBusy = false;
    // Computes the tile list off the UI thread before handing it to the foreground service.
    private final ExecutorService routingWorker = Executors.newSingleThreadExecutor();

    // Single active download at a time (keeps bandwidth + UI simple). The download itself runs in
    // MapDownloadService (a foreground service) so it survives screen-off / backgrounding; this
    // local mirror of the active country is kept in sync by the service listener.
    private volatile String activeBase = null;

    // ── App-theme (chrome) palette ──
    // Chosen once in onCreate from the "lb" pref "theme_dark" so this screen's chrome follows the
    // dashboard's light/dark toggle. No map is rendered here, so nothing map-related is affected.
    private int cBg;      // screen background
    private int cCard;    // country / routing card background
    private int cText;    // primary text
    private int cMuted;   // secondary text
    private int cAccent;  // accent (green)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on while a map download runs (consistent with the main dashboard).
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Resolve the chrome palette before the UI is built (buildUi + listeners read the c* fields).
        initTheme();

        navDir = getExternalFilesDir("nav");
        mapsDir = new File(navDir, "maps");
        segmentsDir = new File(navDir, "segments");
        if (!mapsDir.mkdirs() && !mapsDir.exists()) Log.w(TAG, "mkdirs failed: " + mapsDir);
        if (!segmentsDir.mkdirs() && !segmentsDir.exists()) Log.w(TAG, "mkdirs failed: " + segmentsDir);

        setContentView(buildUi());
        refreshAll();
        UiChrome.applyFullscreen(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) UiChrome.applyFullscreen(this);
    }

    // ─────────────────────────────────────────────── background download listener ──

    private final MapDownloadService.Listener dlListener = new MapDownloadService.Listener() {
        @Override
        public void onUpdate(String base, long done, long total, String status) {
            runOnUiThread(() -> {
                activeBase = base;
                for (Row r : rows) {
                    if (r.base.equals(base)) {
                        applyProgress(r, done, total, status);
                        break;
                    }
                }
            });
        }

        @Override
        public void onFinished(String base, boolean ok, String message) {
            runOnUiThread(() -> {
                toast(message);
                activeBase = null;
                refreshAll();
            });
        }

        @Override
        public void onRoutingUpdate(long permille, long scale, String status) {
            runOnUiThread(() -> applyRoutingProgress(permille, scale, status));
        }

        @Override
        public void onRoutingFinished(boolean ok, String message) {
            runOnUiThread(() -> {
                routingBusy = false;
                toast(message);
                refreshRouting();
            });
        }
    };

    /** Mirror routing-download progress from the foreground service into the routing card. */
    private void applyRoutingProgress(long permille, long scale, String status) {
        routingBusy = true;
        routingProgress.setVisibility(View.VISIBLE);
        if (scale > 0) {
            routingProgress.setIndeterminate(false);
            routingProgress.setProgress((int) Math.max(0, Math.min(1000, permille * 1000 / scale)));
        } else {
            routingProgress.setIndeterminate(true);
        }
        routingStatus.setVisibility(View.VISIBLE);
        routingStatus.setTextColor(cMuted);
        if (status != null) routingStatus.setText(status);
        routingDownloadBtn.setEnabled(true);
        routingDownloadBtn.setText("Cancel");
        routingDeleteBtn.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MapDownloadService.setListener(dlListener);
        // Reflect any download running in the background (e.g. started, then screen reopened).
        String bg = MapDownloadService.activeBase;
        activeBase = bg;
        refreshAll();
        if (bg != null) {
            for (Row r : rows) {
                if (r.base.equals(bg)) {
                    applyProgress(r, MapDownloadService.done, MapDownloadService.total,
                            MapDownloadService.status);
                    break;
                }
            }
        }
        // Reflect a routing-data download running in the background (service survives view changes).
        if (MapDownloadService.routingActive) {
            routingBusy = true;
            applyRoutingProgress(MapDownloadService.routingPermille, 1000,
                    MapDownloadService.routingStatus);
        }
    }

    @Override
    protected void onPause() {
        MapDownloadService.setListener(null);
        super.onPause();
    }

    /** Show live progress for the active row (called from the service listener + on resume). */
    private void applyProgress(Row r, long done, long total, String status) {
        r.progress.setVisibility(View.VISIBLE);
        if (total > 0) {
            r.progress.setIndeterminate(false);
            r.progress.setProgress((int) (done * 1000 / total));
        } else {
            r.progress.setIndeterminate(true);
        }
        if (status != null) r.status.setText(status);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("nav", MODE_PRIVATE);
    }

    /**
     * Choose the chrome colour palette from the persisted app theme ("lb" pref "theme_dark",
     * default dark). Dark keeps the existing look (the NavUi dashboard tokens); light swaps in a
     * readable light palette. Called once at the start of onCreate, before any view is built.
     */
    private void initTheme() {
        boolean dark = getSharedPreferences("lb", MODE_PRIVATE).getBoolean("theme_dark", true);
        if (dark) {
            cBg = NavUi.BG;          // 0xFF0E1116
            cCard = NavUi.BAR_BG;    // 0xFF111420
            cText = NavUi.TEXT;      // 0xFFE4EDF6
            cMuted = NavUi.MUTED;    // 0xFF9AA0A6
            cAccent = NavUi.ACCENT;  // 0xFFC6F135
        } else {
            cBg = 0xFFF2F5FA;
            cCard = 0xFFFFFFFF;
            cText = 0xFF0A0D18;
            cMuted = 0xFF5A6A88;
            cAccent = 0xFF2FEBA2;
        }
    }

    // ─────────────────────────────────────────────── UI ──

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);

        root.addView(NavUi.header(this, "Download maps", null));
        root.addView(NavUi.divider(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        col.setPadding(pad, pad, pad, dp(24));

        TextView intro = new TextView(this);
        intro.setText("Download an offline map for each country you ride in. Once downloaded, "
                + "maps render completely offline.");
        intro.setTextColor(cMuted);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intro.setPadding(0, 0, 0, dp(8));
        col.addView(intro);

        storageLine = new TextView(this);
        storageLine.setTextColor(cMuted);
        storageLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        storageLine.setPadding(0, 0, 0, dp(12));
        col.addView(storageLine);

        // Routing-data (BRouter) management card - ABOVE the country list.
        col.addView(buildRoutingCard());

        for (String[] c : COUNTRIES) {
            Row r = new Row(c[0], c[1], c[2]);
            col.addView(r.view);
            rows.add(r);
        }

        TextView attribution = new TextView(this);
        attribution.setText("© OpenStreetMap contributors (ODbL) - data via the Mapsforge download mirror.");
        attribution.setTextColor(cMuted);
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        attribution.setPadding(0, dp(14), 0, 0);
        col.addView(attribution);

        scroll.addView(col);
        root.addView(scroll);
        return root;
    }

    /** One country card: name, status, progress bar, and action buttons. */
    private final class Row {
        final String display;
        final String base;
        final String approxSize;
        final File file;
        final File poiFile;

        final LinearLayout view;
        final TextView status;
        final ProgressBar progress;
        final Button action;   // Download / Resume / Cancel / Re-download
        final Button use;      // Use (set active) / Active
        final Button delete;   // Delete
        final Button poi;      // Get POI / Delete POI (camping + charging)

        Row(String display, String base, String approxSize) {
            this.display = display;
            this.base = base;
            this.approxSize = approxSize;
            this.file = new File(mapsDir, base + ".map");
            this.poiFile = new File(mapsDir, base + ".poi");

            view = new LinearLayout(MapDownloadActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setBackgroundColor(cCard);
            int p = dp(12);
            view.setPadding(p, dp(10), p, dp(10));
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.bottomMargin = dp(8);
            view.setLayoutParams(vlp);

            TextView name = new TextView(MapDownloadActivity.this);
            name.setText(display);
            name.setTextColor(cText);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            view.addView(name);

            status = new TextView(MapDownloadActivity.this);
            status.setTextColor(cMuted);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            status.setPadding(0, dp(2), 0, dp(6));
            view.addView(status);

            progress = new ProgressBar(MapDownloadActivity.this, null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.bottomMargin = dp(6);
            progress.setLayoutParams(plp);
            progress.setVisibility(View.GONE);
            view.addView(progress);

            LinearLayout btnRow = new LinearLayout(MapDownloadActivity.this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);

            action = new Button(MapDownloadActivity.this);
            action.setOnClickListener(v -> {
                if (base.equals(activeBase)) cancelDownload();
                else startDownload(this);
            });
            btnRow.addView(action);

            use = new Button(MapDownloadActivity.this);
            use.setOnClickListener(v -> {
                prefs().edit().putString("selected_map", file.getName()).apply();
                toast("Using " + display);
                refreshAll();
            });
            btnRow.addView(use);

            delete = new Button(MapDownloadActivity.this);
            delete.setText("Delete");
            delete.setOnClickListener(v -> confirmDelete(this));
            btnRow.addView(delete);

            poi = new Button(MapDownloadActivity.this);
            poi.setOnClickListener(v -> {
                boolean have = poiFile != null && poiFile.isFile() && poiFile.length() > 0;
                if (have) {
                    //noinspection ResultOfMethodCallIgnored
                    poiFile.delete();
                    toast(display + " POI deleted");
                    refreshAll();
                } else {
                    startPoiDownload(this);
                }
            });
            btnRow.addView(poi);

            view.addView(btnRow);
        }
    }

    // ─────────────────────────────────────────────── state / refresh ──

    private void refreshAll() {
        for (Row r : rows) refresh(r);
        refreshStorage();
        refreshRouting();
    }

    /** The ".part" companion of a row's map file, guarded to stay under mapsDir (null if it escapes). */
    private File partFileFor(Row r) {
        try {
            return PathGuard.childOf(mapsDir, r.file.getName() + ".part");
        } catch (IOException e) {
            Log.w(TAG, "rejected .part path for " + r.file.getName(), e);
            return null;
        }
    }

    private void refresh(Row r) {
        boolean exists = r.file.isFile() && r.file.length() > 0;
        boolean active = r.base.equals(activeBase);
        File partFile = partFileFor(r);
        boolean partial = partFile != null && partFile.exists();
        String sel = prefs().getString("selected_map", null);
        boolean isActiveMap = exists && r.file.getName().equals(sel);

        if (active) {
            r.progress.setVisibility(View.VISIBLE);
            r.action.setText("Cancel");
            r.action.setVisibility(View.VISIBLE);
            r.use.setVisibility(View.GONE);
            r.delete.setVisibility(View.GONE);
            // status text is driven by the progress callback
        } else if (exists) {
            r.progress.setVisibility(View.GONE);
            r.status.setText("Installed • " + NavDownloader.humanBytes(r.file.length())
                    + (isActiveMap ? "  •  active map" : ""));
            r.action.setVisibility(View.GONE);
            r.use.setVisibility(View.VISIBLE);
            r.use.setText(isActiveMap ? "Active ✓" : "Use");
            r.use.setEnabled(!isActiveMap);
            r.delete.setVisibility(View.VISIBLE);
        } else {
            r.progress.setVisibility(partial ? View.VISIBLE : View.GONE);
            if (partial) {
                long have = partFile.length();
                r.status.setText("Paused • " + NavDownloader.humanBytes(have) + " downloaded");
                r.action.setText("Resume");
            } else {
                r.status.setText("Not downloaded • ~" + r.approxSize);
                r.action.setText("Download");
            }
            r.action.setVisibility(View.VISIBLE);
            r.use.setVisibility(View.GONE);
            r.delete.setVisibility(partial ? View.VISIBLE : View.GONE);
        }

        // POI (camping + charging): offered once the country map is installed; hidden while a download
        // runs on this row. The tiny .poi sits next to the .map so NavActivity picks it up automatically.
        boolean poiExists = r.poiFile != null && r.poiFile.isFile() && r.poiFile.length() > 0;
        if (exists && !active) {
            r.poi.setVisibility(View.VISIBLE);
            r.poi.setText(poiExists ? "Delete POI" : "Get POI");
        } else {
            r.poi.setVisibility(View.GONE);
        }
    }

    private void refreshStorage() {
        long free = 0L;
        try { free = mapsDir.getFreeSpace(); } catch (Throwable ignored) { }
        storageLine.setText("Saved to: " + mapsDir.getAbsolutePath()
                + "\nFree space: " + NavDownloader.humanBytes(free));
    }

    // ─────────────────────────────────────────────── routing-data card ──

    /**
     * The "Routing data" card shown ABOVE the country list. Bike routing uses BRouter {@code .rd5}
     * segment tiles; here they can be pre-downloaded (for every offline map already installed) and
     * deleted, instead of only trickling in on-route inside {@link NavActivity}.
     */
    private View buildRoutingCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(cCard);
        int p = dp(12);
        card.setPadding(p, dp(10), p, dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(16);
        card.setLayoutParams(clp);

        TextView title = new TextView(this);
        title.setText("Routing data");
        title.setTextColor(cText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView blurb = new TextView(this);
        blurb.setText("Bike routing tiles (BRouter) for the areas your downloaded maps cover. "
                + "Get them here so routing works instantly offline.");
        blurb.setTextColor(cMuted);
        blurb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        blurb.setPadding(0, dp(2), 0, dp(6));
        card.addView(blurb);

        routingState = new TextView(this);
        routingState.setTextColor(cMuted);
        routingState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(routingState);

        routingProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        routingProgress.setMax(1000);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(6);
        plp.bottomMargin = dp(2);
        routingProgress.setLayoutParams(plp);
        routingProgress.setVisibility(View.GONE);
        card.addView(routingProgress);

        routingStatus = new TextView(this);
        routingStatus.setTextColor(cMuted);
        routingStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        routingStatus.setPadding(0, dp(2), 0, dp(4));
        routingStatus.setVisibility(View.GONE);
        card.addView(routingStatus);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(4), 0, 0);

        routingDownloadBtn = new Button(this);
        routingDownloadBtn.setAllCaps(false);
        routingDownloadBtn.setText("Download routing data");
        routingDownloadBtn.setOnClickListener(v -> {
            // While the foreground service is running this button cancels it; otherwise it starts.
            if (MapDownloadService.routingActive) cancelRoutingDownload();
            else startRoutingDownload();
        });
        btnRow.addView(routingDownloadBtn);

        routingDeleteBtn = new Button(this);
        routingDeleteBtn.setAllCaps(false);
        routingDeleteBtn.setText("Delete");
        routingDeleteBtn.setOnClickListener(v -> confirmDeleteRouting());
        btnRow.addView(routingDeleteBtn);

        card.addView(btnRow);
        return card;
    }

    /** Update the routing card's state line + button enablement (respects an in-flight download). */
    private void refreshRouting() {
        if (routingState == null) return; // card not built yet
        File[] rd5 = segmentsDir.listFiles((FilenameFilter) (dir, name) -> name.endsWith(".rd5"));
        int count = rd5 == null ? 0 : rd5.length;
        long total = 0L;
        if (rd5 != null) {
            for (File f : rd5) total += f.length();
        }
        boolean anyMap = !downloadedMaps().isEmpty();

        if (count > 0) {
            routingState.setText(count + (count == 1 ? " routing tile • " : " routing tiles • ")
                    + NavDownloader.humanBytes(total));
        } else {
            routingState.setText("No routing data downloaded yet.");
        }

        if (MapDownloadService.routingActive) {
            // Running in the foreground service - offer Cancel; progress is driven by the listener.
            routingProgress.setVisibility(View.VISIBLE);
            routingDownloadBtn.setEnabled(true);
            routingDownloadBtn.setText("Cancel");
            routingDeleteBtn.setVisibility(View.GONE);
            return;
        }
        if (routingBusy) {
            // Preparing the tile list before the service starts.
            routingDownloadBtn.setEnabled(false);
            routingDownloadBtn.setText("Preparing…");
            routingDeleteBtn.setVisibility(View.GONE);
            return;
        }

        routingProgress.setVisibility(View.GONE);
        routingDownloadBtn.setText("Download routing data");
        // Enabled as soon as at least one country map exists - routing tiles are derived from a
        // downloaded map's bounding box, so a map must come first.
        routingDownloadBtn.setEnabled(anyMap);
        routingDeleteBtn.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        if (anyMap) {
            routingStatus.setVisibility(View.GONE);
        } else {
            // Make it obvious WHY the button is disabled - a clear, visible reason, not just a
            // greyed-out button. Highlighted (accent) so the user sees the next step to take.
            routingStatus.setVisibility(View.VISIBLE);
            routingStatus.setTextColor(cAccent);
            routingStatus.setText("Download a country map first, then routing data for it.");
        }
    }

    /** Every fully-downloaded {@code .map} offline map (the country maps in {@code mapsDir}). */
    private List<File> downloadedMaps() {
        List<File> out = new ArrayList<>();
        File[] fs = mapsDir.listFiles((FilenameFilter) (dir, name) -> name.endsWith(".map"));
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && f.length() > 0) out.add(f);
            }
        }
        return out;
    }

    /** Read a {@code .map} file's own bounding box from its Mapsforge header (null on failure). */
    private static BoundingBox readMapBoundingBox(File map) {
        MapFile mf = null;
        try {
            mf = new MapFile(map);
            return mf.getMapFileInfo().boundingBox;
        } catch (Throwable t) {
            Log.w(TAG, "could not read bbox from " + map.getName(), t);
            return null;
        } finally {
            if (mf != null) {
                try { mf.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Start the routing-data download. The tile list is computed off the UI thread from the
     * downloaded maps' bounding boxes; the actual {@code .rd5} download then runs in
     * {@link MapDownloadService} (a FOREGROUND SERVICE) so it survives the app being backgrounded
     * or this view being left - exactly like the map download.
     */
    private void startRoutingDownload() {
        if (MapDownloadService.activeBase != null || activeBase != null) {
            toast("A download is already running");
            return;
        }
        if (routingBusy || MapDownloadService.routingActive) return;
        final List<File> maps = downloadedMaps();
        if (maps.isEmpty()) {
            toast("Download a map first");
            return;
        }
        routingBusy = true;
        routingProgress.setVisibility(View.VISIBLE);
        routingProgress.setIndeterminate(true);
        routingStatus.setVisibility(View.VISIBLE);
        routingStatus.setTextColor(cMuted);
        routingStatus.setText("Preparing…");
        refreshRouting();
        routingWorker.execute(() -> prepareAndStartRoutingService(maps));
    }

    /**
     * Worker thread: read each downloaded map's bbox from its {@code .map} header, compute the union
     * of covering BRouter tiles, keep only the ones still missing locally, then hand that tile list
     * to {@link MapDownloadService} for a foreground download. (Reading the small map headers is
     * quick; the long-running transfer is what the service keeps alive across backgrounding.)
     */
    private void prepareAndStartRoutingService(List<File> maps) {
        final ArrayList<String> tiles = new ArrayList<>();
        boolean readAny = false;
        try {
            Set<String> want = new LinkedHashSet<>();
            for (File map : maps) {
                BoundingBox bb = readMapBoundingBox(map);
                if (bb != null) {
                    readAny = true;
                    want.addAll(BikeRouter.tilesFor(bb, 0.0));
                }
            }
            for (String t : want) {
                File seg = PathGuard.childOf(segmentsDir, t + ".rd5");
                if (!seg.isFile() || seg.length() == 0) tiles.add(t);
            }
        } catch (Throwable t) {
            Log.e(TAG, "compute routing tiles failed", t);
        }
        final boolean any = readAny;
        runOnUiThread(() -> {
            if (tiles.isEmpty()) {
                routingBusy = false;
                toast(any ? "Routing data already complete for your maps."
                          : "Could not read the map bounds");
                refreshRouting();
                return;
            }
            Intent i = new Intent(this, MapDownloadService.class);
            i.setAction(MapDownloadService.ACTION_START_ROUTING);
            i.putStringArrayListExtra(MapDownloadService.EXTRA_TILES, tiles);
            try {
                ContextCompat.startForegroundService(this, i);
            } catch (Throwable e) {
                Log.e(TAG, "start routing service failed", e);
                routingBusy = false;
                toast("Could not start routing download");
            }
            refreshRouting();
        });
    }

    /** Ask the foreground service to cancel the in-flight routing-data download. */
    private void cancelRoutingDownload() {
        try {
            Intent i = new Intent(this, MapDownloadService.class);
            i.setAction(MapDownloadService.ACTION_CANCEL);
            startService(i);
        } catch (Throwable t) {
            Log.e(TAG, "cancel routing download failed", t);
        }
    }

    private void confirmDeleteRouting() {
        if (routingBusy || MapDownloadService.routingActive) {
            toast("Download in progress");
            return;
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("Delete routing data?")
                    .setMessage("Remove all downloaded BRouter routing tiles?")
                    .setPositiveButton("Delete", (d, w) -> {
                        deleteRoutingData();
                        refreshRouting();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "delete routing dialog failed", t);
        }
    }

    /** Delete every {@code .rd5} routing tile (and any {@code .part}) in the segments dir. */
    private void deleteRoutingData() {
        File[] fs = segmentsDir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".rd5") || n.endsWith(".part")) {
                if (!f.delete()) Log.w(TAG, "delete failed: " + f);
            }
        }
    }

    // ─────────────────────────────────────────────── download ──

    private void startDownload(final Row r) {
        if (MapDownloadService.activeBase != null || activeBase != null) {
            toast("A download is already running");
            return;
        }
        activeBase = r.base;

        Intent i = new Intent(this, MapDownloadService.class);
        i.setAction(MapDownloadService.ACTION_START);
        i.putExtra(MapDownloadService.EXTRA_URL, MAP_BASE_URL + r.base + ".map");
        i.putExtra(MapDownloadService.EXTRA_DEST, r.file.getAbsolutePath());
        i.putExtra(MapDownloadService.EXTRA_BASE, r.base);
        i.putExtra(MapDownloadService.EXTRA_DISPLAY, r.display);
        try {
            ContextCompat.startForegroundService(this, i);
        } catch (Throwable t) {
            Log.e(TAG, "start download service failed", t);
            activeBase = null;
            toast("Could not start download");
            return;
        }

        // Show progress right away; the service listener drives it from here.
        r.progress.setIndeterminate(true);
        refresh(r);
        r.status.setText("Connecting…");
    }

    /** Download this country's POI database (camping + charging) next to its map, via the shared
     *  foreground service. Progress shows on the row + in the notification; the .poi files are small. */
    private void startPoiDownload(final Row r) {
        if (MapDownloadService.activeBase != null || activeBase != null) {
            toast("A download is already running");
            return;
        }
        activeBase = r.base;
        Intent i = new Intent(this, MapDownloadService.class);
        i.setAction(MapDownloadService.ACTION_START);
        i.putExtra(MapDownloadService.EXTRA_URL, POI_BASE_URL + r.base + ".poi");
        i.putExtra(MapDownloadService.EXTRA_DEST, r.poiFile.getAbsolutePath());
        i.putExtra(MapDownloadService.EXTRA_BASE, r.base);
        i.putExtra(MapDownloadService.EXTRA_DISPLAY, r.display + " POI");
        i.putExtra(MapDownloadService.EXTRA_SUFFIX, ".poi");
        try {
            ContextCompat.startForegroundService(this, i);
        } catch (Throwable t) {
            Log.e(TAG, "start POI download failed", t);
            activeBase = null;
            toast("Could not start POI download");
            return;
        }
        r.progress.setIndeterminate(true);
        refresh(r);
        r.status.setText("Connecting POI…");
    }

    /** Ask the foreground service to cancel the in-flight download. */
    private void cancelDownload() {
        try {
            Intent i = new Intent(this, MapDownloadService.class);
            i.setAction(MapDownloadService.ACTION_CANCEL);
            startService(i);
        } catch (Throwable t) {
            Log.e(TAG, "cancel download failed", t);
        }
    }

    private void confirmDelete(final Row r) {
        if (r.base.equals(activeBase)) {
            toast("Cancel the download first");
            return;
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("Delete " + r.display + "?")
                    .setMessage("Remove the downloaded offline map and any paused part?")
                    .setPositiveButton("Delete", (d, w) -> {
                        if (!r.file.delete()) Log.w(TAG, "delete failed: " + r.file);
                        File partFile = partFileFor(r);
                        if (partFile != null) {
                            if (!partFile.delete()) Log.w(TAG, "delete failed: " + partFile);
                        }
                        String sel = prefs().getString("selected_map", null);
                        if (r.file.getName().equals(sel)) {
                            prefs().edit().remove("selected_map").apply();
                        }
                        refreshAll();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "delete dialog failed", t);
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    // NOTE: BOTH the map download AND the routing-data download now run in MapDownloadService and
    // must survive this Activity being destroyed (screen lock / backgrounding / view change) -
    // cancel is explicit via each Cancel button. Only the short-lived tile-list compute executor is
    // shut down here; it must NOT touch the running foreground download.
    @Override
    protected void onDestroy() {
        try {
            routingWorker.shutdownNow();
        } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
