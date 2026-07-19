// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Dimension;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.model.common.Observer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.ExternalRenderTheme;
import org.mapsforge.map.rendertheme.InternalRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.mapsforge.map.rendertheme.rule.RenderThemeHandler;

import org.mapsforge.poi.android.storage.AndroidPoiPersistenceManagerFactory;
import org.mapsforge.poi.storage.PoiCategory;
import org.mapsforge.poi.storage.PoiCategoryFilter;
import org.mapsforge.poi.storage.PoiPersistenceManager;
import org.mapsforge.poi.storage.PointOfInterest;
import org.mapsforge.poi.storage.WhitelistPoiCategoryFilter;

/**
 * Offline turn-by-turn BICYCLE navigation on OpenStreetMap data - with everything downloadable
 * from inside the app (no adb, no manual data steps).
 *
 * <ul>
 *   <li><b>Maps</b>: Mapsforge {@code .map} offline vector maps, downloaded per EU country via
 *       {@link MapDownloadActivity} into {@code getExternalFilesDir("nav")/maps/}. The active map
 *       is chosen there and rendered here.</li>
 *   <li><b>Routing</b>: BRouter (bike/trekking profile, avoids motorways) over small
 *       {@code .rd5} segment tiles downloaded on demand into {@code nav/segments/}.</li>
 *   <li><b>POIs</b> (optional): camping + charging overlays read from the country's {@code .poi}
 *       database - downloaded via "Get POI" on the offline-maps screen (next to the {@code .map}) or
 *       side-loaded - if one is present; otherwise the toggles are hidden.</li>
 * </ul>
 *
 * A consistent fixed-height top app bar is always visible (see {@link NavUi}). When no map is
 * installed the screen is never a dead-end: it shows the header plus a clear message and a
 * "Download maps" button. All OSM data is ODbL - the "© OpenStreetMap contributors" attribution
 * is always visible.
 */
public class NavActivity extends Activity {

    private static final String TAG = "lbnav";
    private static final int REQ_LOC = 5120;

    /**
     * Intent extra carrying a recorded ride as a JSON array of {@code {lat, lon}} points. When set,
     * {@link NavActivity} opens in DISPLAY-ONLY mode: it draws the track and fits the map to it,
     * with no routing or destination required.
     */
    public static final String EXTRA_TRACK = "track";

    // Central Europe - the initial map centre until the first GPS fix arrives.
    private static final LatLong DEFAULT_CENTER = new LatLong(50.0, 9.0);
    private static final byte START_ZOOM = 14;
    // Closer camera used in active turn-by-turn navigation (Start pressed).
    private static final byte NAV_ZOOM = 17;

    // Assumed average cycling speed (km/h) for the ETA when GPS speed is unavailable.
    private static final double CYCLING_KMH = 18.0;
    // Voice announcement distance thresholds to the next maneuver (metres).
    private static final double ANNOUNCE_FAR_M = 200.0;
    private static final double ANNOUNCE_NEAR_M = 30.0;
    private static final double ARRIVE_ANNOUNCE_M = 60.0;

    // Re-route when the rider strays more than this far (metres) from the active route.
    private static final double OFF_ROUTE_METERS = 60.0;
    // Never re-route more often than this (ms).
    private static final long REROUTE_COOLDOWN_MS = 12000L;
    // Minimum bearing change (degrees) between consecutive route segments to log a turn.
    private static final double TURN_THRESHOLD_DEG = 35.0;

    // ── Data locations ──
    private File navDir;
    private File mapsDir;
    private File segmentsDir;
    private File profileDir;
    private File mapFile;          // the ACTIVE offline map (may be null → no-map screen)
    private File poiFile;          // optional POI DB matching the active map
    private String loadedMapPath;  // absolute path of the map this UI was built for (null = none)
    private BoundingBox activeMapBbox; // bbox of the active map (for GPS-based auto map switching)

    // ── Map ──
    private MapView mapView;
    private TileCache tileCache;
    private TileRendererLayer tileRendererLayer;

    // Dark-map render theme toggle (persisted in prefs "nav" key "map_dark", default false).
    // The control is a compact sun/moon icon in the title bar (see NavUi.header + updateMapDarkIcon).
    private boolean mapDark = false;
    private TextView mapDarkIcon;

    // Recorded-ride overlay (display-only mode; see EXTRA_TRACK).
    private Polyline trackLayer;

    // ── Location ──
    private LocationManager locationManager;
    private Location lastLocation;
    private Marker meMarker;
    private boolean followMode = true;

    // ── Destination / routing ──
    private LatLong startPoint;   // explicit route start; null = use the current GPS position
    private LatLong destination;
    private Marker destMarker;
    private Polyline routeLayer;
    private final AtomicBoolean routeCancel = new AtomicBoolean(false);

    // Active route, flattened for live guidance.
    private List<LatLong> routePts;
    private double[] cumDist;                 // cumulative metres along routePts
    private int[] maneuverIdx;                // route-point index of each maneuver
    private String[] maneuverText;            // instruction text for each maneuver
    private long lastRerouteAt = 0L;

    // ── POI ──
    private volatile PoiPersistenceManager poiManager; // lazily opened
    private final List<Marker> poiMarkers = new ArrayList<>();
    private boolean showCamping = false;
    private boolean showCharging = false;

    // ── UI ──
    private TextView banner;
    private long lastPoiReloadAt = 0L;

    // ── App-theme (chrome) palette ──
    // Chosen once in onCreate from the "lb" pref "theme_dark". ONLY the surrounding Activity UI
    // (input bars, profile buttons, banner, next-turn card, backgrounds, text) follows this; the
    // offline map keeps its OWN dark/light toggle (see mapDark / resolveRenderTheme), so map tiles
    // and overlays (route line, markers) are never recolored here.
    private int cBg;        // screen background
    private int cBar;       // translucent input / control bar over the map
    private int cBanner;    // near-opaque overlay bar (bottom banner, next-turn card)
    private int cText;      // primary text
    private int cMuted;     // secondary / hint text
    private int cBorder;    // borders / inactive segment fill
    private int cAccent;    // accent (green)
    private int cOnAccent;  // text drawn ON the accent colour

    // ── Active navigation ("Start" pressed) ──
    // Route-setup controls (destination input, profile selector, POI chips) so they can be hidden
    // while actively navigating and restored on Stop.
    private LinearLayout topContainer;
    private Button startBtn;                    // shown after a route is computed
    private View navCard;                       // prominent NEXT-TURN card at the top (nav mode only)
    private TextView navTurnArrow, navTurnText, navTurnDist, navSecondary;
    private boolean navigating = false;
    private byte preNavZoom = START_ZOOM;       // zoom to restore when navigation stops

    // ── Voice guidance ──
    private TtsHelper tts;
    private NavVoice navVoice;
    private boolean voiceOn = true;             // persisted in prefs "nav" key "voice_on"
    private TextView voiceIcon;
    // Guards so each maneuver is announced at most once per threshold (index into maneuverIdx[]).
    private int lastFarIdx = -1;
    private int lastNearIdx = -1;
    private boolean announcedArrive = false;

    // ── Route preference ──
    // Selected BRouter profile base: "trekking" (Balanced), "shortest", "quiet" (Bike paths).
    private String routeProfile = "trekking";
    private Button btnBalanced, btnShortest, btnQuiet;
    // One-line explanation of the currently-selected route profile (NavUi.MUTED style).
    private TextView profileDesc;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // Marker bitmaps (created once the graphic factory is up).
    private Bitmap dotMe, dotDest, dotCamp, dotCharge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on while navigating (consistent with the main dashboard).
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Resolve the chrome palette before ANY view is built (both screens read the c* fields).
        initTheme();

        navDir = getExternalFilesDir("nav");
        if (navDir != null) {
            mapsDir = new File(navDir, "maps");
            segmentsDir = new File(navDir, "segments");
            profileDir = new File(navDir, "profiles");
            if (!mapsDir.mkdirs() && !mapsDir.exists()) Log.w(TAG, "mkdirs failed: " + mapsDir);
            if (!segmentsDir.mkdirs() && !segmentsDir.exists()) Log.w(TAG, "mkdirs failed: " + segmentsDir);
        }

        mapFile = resolveSelectedMap();
        poiFile = resolvePoiFor(mapFile);

        // Display-only recorded-ride mode: a JSON track passed from the dashboard (see EXTRA_TRACK).
        final String trackJson = getIntent() != null ? getIntent().getStringExtra(EXTRA_TRACK) : null;
        final boolean displayTrack = trackJson != null && !trackJson.trim().isEmpty();

        // No offline map yet → header + a clear message + "Download maps" (never a dead-end).
        if (mapFile == null) {
            loadedMapPath = null;
            setContentView(buildNoMapScreen());
            UiChrome.applyFullscreen(this);
            if (displayTrack) toast("Download a map first to view the recorded route");
            return;
        }

        try {
            AndroidGraphicFactory.createInstance(getApplication());
            createMarkerBitmaps();
            setContentView(buildMapScreen());
            initMap();
            loadedMapPath = mapFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "map init failed", t);
            loadedMapPath = null;
            setContentView(buildNoMapScreen());
            UiChrome.applyFullscreen(this);
            if (displayTrack) toast("Download a map first to view the recorded route");
            return;
        }

        // Draw the recorded ride and fit the map to it (display-only; no routing needed).
        if (displayTrack) showRecordedTrack(trackJson);

        // Device TTS for spoken guidance - uses installed voice data only (no download prompt).
        voiceOn = getSharedPreferences("nav", MODE_PRIVATE).getBoolean("voice_on", true);
        navVoice = NavVoice.forDevice();
        tts = new TtsHelper(this, navVoice.locale());

        ensureLocationPermission();
        startLocationUpdates();
        UiChrome.applyFullscreen(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) UiChrome.applyFullscreen(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the download manager may have installed or switched the active map.
        File now = resolveSelectedMap();
        String nowPath = now != null ? now.getAbsolutePath() : null;
        if (!eq(nowPath, loadedMapPath)) {
            recreate();
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Choose the chrome colour palette from the persisted app theme ("lb" pref "theme_dark",
     * default dark). Dark keeps the existing look; light swaps in a readable light palette. Called
     * once at the very start of onCreate, before any view is built. The map render theme is
     * independent (see mapDark) and is NOT affected here.
     */
    private void initTheme() {
        boolean dark = getSharedPreferences("lb", MODE_PRIVATE).getBoolean("theme_dark", true);
        if (dark) {
            cBg = NavUi.BG;          // 0xFF0E1116
            cBar = 0xCC0E1116;       // translucent dark over the map
            cBanner = 0xE6101418;    // near-opaque dark overlay bar
            cText = 0xFFFFFFFF;
            cMuted = NavUi.MUTED;    // 0xFF9AA0A6
            cBorder = NavUi.BORDER;  // 0xFF1C2035
            cAccent = NavUi.ACCENT;  // 0xFFC6F135
            cOnAccent = 0xFF0E1116;  // dark text on the accent chip
        } else {
            cBg = 0xFFF2F5FA;
            cBar = 0xF2FFFFFF;       // near-opaque white over the map (stays readable)
            cBanner = 0xF2FFFFFF;
            cText = 0xFF0A0D18;
            cMuted = 0xFF5A6A88;
            cBorder = 0xFFCDD5E4;
            cAccent = 0xFF2FEBA2;
            cOnAccent = 0xFF06121C;  // dark text on the accent chip
        }
    }

    // ─────────────────────────────────────────────── active map / poi resolution ──

    /** The active offline map: explicit selection, else any downloaded map, else legacy nav/germany.map. */
    private File resolveSelectedMap() {
        if (navDir == null) return null;
        SharedPreferences sp = getSharedPreferences("nav", MODE_PRIVATE);
        String sel = sp.getString("selected_map", null);
        if (sel != null && mapsDir != null) {
            try {
                // Path-traversal guard: a stored selection must resolve inside the maps dir.
                File f = PathGuard.childOf(mapsDir, sel);
                if (f.isFile() && f.length() > 0) return f;
            } catch (IOException e) {
                Log.w(TAG, "rejected stored map selection: " + sel, e);
            }
        }
        if (mapsDir != null && mapsDir.isDirectory()) {
            File[] fs = mapsDir.listFiles((FilenameFilter) (dir, name) -> name.endsWith(".map"));
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.length() > 0) return f;
                }
            }
        }
        File legacy = new File(navDir, "germany.map"); // backwards compatible with the old adb layout
        if (legacy.isFile() && legacy.length() > 0) return legacy;
        return null;
    }

    /** POI DB sitting next to the active map ({@code <base>.poi}), else legacy nav/germany.poi. */
    private File resolvePoiFor(File map) {
        if (map != null) {
            String base = map.getName();
            if (base.endsWith(".map")) base = base.substring(0, base.length() - 4);
            try {
                // Path-traversal guard: the POI DB must sit next to the map, inside its own dir.
                File p = PathGuard.childOf(map.getParentFile(), base + ".poi");
                if (p.isFile() && p.length() > 0) return p;
            } catch (IOException e) {
                Log.w(TAG, "rejected poi path for " + map.getName(), e);
            }
        }
        if (navDir != null) {
            File legacy = new File(navDir, "germany.poi");
            if (legacy.isFile() && legacy.length() > 0) return legacy;
        }
        return null;
    }

    private boolean hasPoi() {
        return poiFile != null && poiFile.isFile() && poiFile.length() > 0;
    }

    /**
     * GPS-based automatic map selection. If the ACTIVE offline map does not cover the current
     * location but another downloaded map does, switch to that map - persist the choice and rebuild
     * the screen - so the correct country map loads automatically as the rider crosses a border,
     * with no manual "Use" tap. Overlapping maps that both cover the point keep the current one (no
     * thrash), and switching is skipped during active turn-by-turn navigation so an in-progress
     * session is never yanked out from under the rider.
     *
     * @return {@code true} if a switch was triggered (the activity is being recreated).
     */
    private boolean maybeAutoSelectMap(LatLong here) {
        if (mapsDir == null || here == null || navigating) return false;
        // Active map already covers us → keep it (also the common single-map case).
        if (activeMapBbox != null && activeMapBbox.contains(here)) return false;
        File better = firstMapContaining(here);
        if (better == null) return false;
        if (loadedMapPath != null && better.getAbsolutePath().equals(loadedMapPath)) return false;
        getSharedPreferences("nav", MODE_PRIVATE).edit()
                .putString("selected_map", better.getName()).apply();
        recreate();
        return true;
    }

    /** The first downloaded {@code .map} whose own bounding box contains {@code here} (else null). */
    private File firstMapContaining(LatLong here) {
        if (mapsDir == null || !mapsDir.isDirectory()) return null;
        File[] fs = mapsDir.listFiles((FilenameFilter) (dir, name) -> name.endsWith(".map"));
        if (fs == null) return null;
        for (File f : fs) {
            if (!f.isFile() || f.length() == 0) continue;
            final File guarded;
            try {
                // Path-traversal guard: resolve the name back under the maps dir.
                guarded = PathGuard.childOf(mapsDir, f.getName());
            } catch (IOException e) {
                Log.w(TAG, "rejected map path: " + f.getName(), e);
                continue;
            }
            BoundingBox bb = readMapBbox(guarded);
            if (bb != null && bb.contains(here)) return guarded;
        }
        return null;
    }

    /** Read a {@code .map} file's own bounding box from its Mapsforge header (null on failure). */
    private static BoundingBox readMapBbox(File map) {
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

    private void openDownloadManager() {
        try {
            startActivity(new Intent(this, MapDownloadActivity.class));
        } catch (Throwable t) {
            Log.e(TAG, "open map download failed", t);
        }
    }

    // ─────────────────────────────────────────────── no-map screen ──

    private View buildNoMapScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.addView(NavUi.header(this, "Navigation", this::openDownloadManager));
        root.addView(NavUi.divider(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("No offline map installed");
        title.setTextColor(cAccent);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPadding(0, 0, 0, dp(12));
        col.addView(title);

        TextView body = new TextView(this);
        body.setText("Offline navigation needs an offline map for the area you ride in. "
                + "Download one directly in the app - pick your country and the map downloads over "
                + "your connection, then routing works fully offline.\n\n"
                + "Bike routing data (BRouter segments) downloads automatically the first time you "
                + "route in an area.");
        body.setTextColor(cText);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        col.addView(body);

        Button dlBtn = new Button(this);
        dlBtn.setText("Download maps");
        dlBtn.setOnClickListener(v -> openDownloadManager());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(20);
        dlBtn.setLayoutParams(blp);
        col.addView(dlBtn);

        TextView attribution = new TextView(this);
        attribution.setText("© OpenStreetMap contributors (ODbL)");
        attribution.setTextColor(cMuted);
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        attribution.setPadding(0, dp(24), 0, 0);
        col.addView(attribution);

        scroll.addView(col);
        root.addView(scroll);
        return root;
    }

    // ─────────────────────────────────────────────── map screen ──

    private View buildMapScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        LinearLayout header = NavUi.header(this, "Navigation", this::openDownloadManager);
        addDarkMapToggleToHeader(header);
        addVoiceToggleToHeader(header);
        root.addView(header);
        root.addView(NavUi.divider(this));

        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mapView = new MapView(this);
        content.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Route input: a Start row and a Destination row. Each field has a "Here" button that
        //    inserts the current GPS position; an empty Start means "use my current position". ──
        int p = dp(6);

        // Start row: [Start input .......................] [📍] - the pin fills the field with my GPS.
        LinearLayout startRow = new LinearLayout(this);
        startRow.setOrientation(LinearLayout.HORIZONTAL);
        startRow.setBackgroundColor(cBar);
        startRow.setGravity(Gravity.CENTER_VERTICAL);
        startRow.setPadding(p, p, p, 0);

        final EditText start = new EditText(this);
        start.setHint("Start: lat, lon (empty = current position)");
        start.setTextColor(cText);
        start.setHintTextColor(cMuted);
        start.setSingleLine(true);
        start.setInputType(InputType.TYPE_CLASS_TEXT);
        start.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        start.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        startRow.addView(start);

        Button startHere = iconButton("📍", "Use my location as start");
        startHere.setOnClickListener(v -> useCurrentLocation(start));
        startRow.addView(startHere);

        // Destination row: [Destination input .............] [📍] - the pin fills the field with my GPS.
        LinearLayout destRow = new LinearLayout(this);
        destRow.setOrientation(LinearLayout.HORIZONTAL);
        destRow.setBackgroundColor(cBar);
        destRow.setGravity(Gravity.CENTER_VERTICAL);
        destRow.setPadding(p, p, p, 0);

        final EditText dest = new EditText(this);
        dest.setHint("Destination: lat, lon");
        dest.setTextColor(cText);
        dest.setHintTextColor(cMuted);
        dest.setSingleLine(true);
        dest.setInputType(InputType.TYPE_CLASS_TEXT);
        dest.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        destRow.addView(dest);

        Button destHere = iconButton("📍", "Use my location as destination");
        destHere.setOnClickListener(v -> useCurrentLocation(dest));
        destRow.addView(destHere);

        // Action row (above the "Route:" profile selector): [Route] [Start] [Center], three text
        // buttons in the same segmented look as the three profile buttons. "Route" computes the route
        // from the Start / Destination fields; "Start" enters active turn-by-turn navigation and stays
        // hidden until a route exists; "Center" recenters the map on the current position and resumes
        // auto-follow.
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setBackgroundColor(cBar);
        actionRow.setPadding(p, p, p, dp(8)); // small gap before the "Route:" selector row below

        Button route = segButton("Route");
        styleSeg(route, false);
        route.setOnClickListener(v -> {
            // Start: empty means "current GPS position"; otherwise it must parse as lat, lon.
            String sTxt = start.getText().toString().trim();
            if (sTxt.isEmpty()) {
                startPoint = null;
            } else {
                LatLong s = parseLatLon(sTxt);
                if (s == null) {
                    toast("Enter start as lat, lon, or leave it empty for your current position");
                    return;
                }
                startPoint = s;
            }
            LatLong d = parseLatLon(dest.getText().toString());
            if (d != null) setDestination(d, false);
            if (destination == null) {
                toast("Enter a destination (lat, lon) or long-press the map");
                return;
            }
            requestRoute();
        });
        actionRow.addView(route);

        startBtn = segButton("Start");
        styleSeg(startBtn, false);
        startBtn.setVisibility(View.GONE);
        startBtn.setOnClickListener(v -> startNavigation());
        actionRow.addView(startBtn);

        Button center = segButton("Center");
        styleSeg(center, false);
        center.setOnClickListener(v -> {
            followMode = true;
            if (lastLocation != null && mapView != null) {
                mapView.setCenter(new LatLong(lastLocation.getLatitude(), lastLocation.getLongitude()));
            }
        });
        actionRow.addView(center);

        topContainer = new LinearLayout(this);
        topContainer.setOrientation(LinearLayout.VERTICAL);
        topContainer.addView(startRow);
        topContainer.addView(destRow);
        topContainer.addView(actionRow);

        // Route-preference selector (Balanced / Shortest / Bike paths) - persisted in prefs "nav".
        routeProfile = getSharedPreferences("nav", MODE_PRIVATE).getString("route_profile", "trekking");

        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setGravity(Gravity.CENTER_VERTICAL);
        profileRow.setBackgroundColor(cBar);
        profileRow.setPadding(p, 0, p, p);

        TextView styleLbl = new TextView(this);
        styleLbl.setText("Route:");
        styleLbl.setTextColor(cMuted);
        styleLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams styleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        styleLp.rightMargin = dp(6);
        styleLbl.setLayoutParams(styleLp);
        profileRow.addView(styleLbl);

        btnBalanced = segButton("Balanced");
        btnShortest = segButton("Shortest");
        btnQuiet = segButton("Bike paths");
        btnBalanced.setOnClickListener(v -> selectProfile("trekking"));
        btnShortest.setOnClickListener(v -> selectProfile("shortest"));
        btnQuiet.setOnClickListener(v -> selectProfile("quiet"));
        profileRow.addView(btnBalanced);
        profileRow.addView(btnShortest);
        profileRow.addView(btnQuiet);
        updateProfileButtons();
        topContainer.addView(profileRow);

        // One-line explanation of the selected route profile, updated on every selection change.
        profileDesc = new TextView(this);
        profileDesc.setTextColor(cMuted);
        profileDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        profileDesc.setBackgroundColor(cBar);
        profileDesc.setPadding(p, 0, p, p);
        topContainer.addView(profileDesc);
        updateProfileDesc();

        // POI toggle row - only shown when a POI database is present (hidden gracefully otherwise).
        // Two toggles only (Camping / Charging): they always fit side by side, and each shows ALL
        // sites of its kind. No socket sub-filter - roughly half of OSM charging stations carry no
        // socket:* tag at all, so a Schuko/Type2 filter would silently hide most real stations.
        // The socket type, when known, is shown on tap.
        if (hasPoi()) {
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(p, p, p, p);
            chips.setBackgroundColor(cBar);

            final ToggleButton tgCamp = chip("⛺ Camping");
            tgCamp.setOnCheckedChangeListener((b, checked) -> {
                showCamping = checked;
                reloadPois();
            });
            chips.addView(tgCamp);

            final ToggleButton tgCharge = chip("⚡ Charging");
            tgCharge.setOnCheckedChangeListener((b, checked) -> {
                showCharging = checked;
                reloadPois();
            });
            chips.addView(tgCharge);

            topContainer.addView(chips);
        }

        FrameLayout.LayoutParams topContainerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topContainerLp.gravity = Gravity.TOP;
        content.addView(topContainer, topContainerLp);

        // Turn-by-turn banner (bottom).
        banner = new TextView(this);
        banner.setText("Set a destination, then tap Route.");
        banner.setTextColor(cText);
        banner.setBackgroundColor(cBanner);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        banner.setPadding(dp(14), dp(10), dp(14), dp(10));
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.gravity = Gravity.BOTTOM;
        content.addView(banner, bLp);

        // Prominent NEXT-TURN card (active navigation only) - sits over the top of the map.
        navCard = buildNavCard();
        navCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.TOP;
        content.addView(navCard, navLp);

        // OSM attribution (mandatory for ODbL data).
        TextView attribution = new TextView(this);
        attribution.setText("© OpenStreetMap contributors");
        attribution.setTextColor(0xFFCFCFCF);
        attribution.setBackgroundColor(0x66000000);
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        attribution.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams aLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.gravity = Gravity.BOTTOM | Gravity.END;
        aLp.bottomMargin = dp(52);
        content.addView(attribution, aLp);

        root.addView(content);
        return root;
    }

    private ToggleButton chip(String label) {
        ToggleButton t = new ToggleButton(this);
        t.setTextOn(label);
        t.setTextOff(label);
        t.setText(label);
        t.setAllCaps(false);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setIncludeFontPadding(false);
        t.setMinWidth(0);
        t.setMinimumWidth(0);
        t.setMinHeight(0);
        t.setMinimumHeight(0);
        t.setPadding(dp(6), dp(3), dp(6), dp(3));
        // Flat segmented look matching the route-profile buttons: cBorder when off, cAccent when on.
        // A state-driven background + text color restyle the toggle on check, so callers keep their
        // own OnCheckedChangeListener purely for behaviour (no styling code needed there).
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_checked}, new ColorDrawable(cAccent));
        bg.addState(new int[0], new ColorDrawable(cBorder));
        t.setBackground(bg);
        t.setTextColor(new ColorStateList(
                new int[][]{ new int[]{android.R.attr.state_checked}, new int[0] },
                new int[]{ cOnAccent, cText }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * One segment of the route-preference control - equal-width, restyled by {@link #styleSeg}.
     * Kept deliberately compact (small text, tight padding, no default 48dp button min-height) so
     * the three segments read as a small, unobtrusive segmented row consistent with the app's other
     * small controls.
     */
    private Button segButton(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setIncludeFontPadding(false);
        // Drop the platform Button's large default min-size + insets so the row can be short.
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), dp(3), dp(6), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * A compact, icon-only action button (a single pictogram) sized to sit next to a full-width input
     * field without crowding it. Same flat look as {@link #segButton} - no oversized platform min-size,
     * {@code cBorder} background, {@code cText} glyph - but it hugs its content instead of stretching.
     * The label is a pictogram, so {@code description} carries the meaning for accessibility and as a
     * long-press tooltip.
     */
    private Button iconButton(String glyph, String description) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(glyph);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setIncludeFontPadding(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setTextColor(cText);
        b.setBackgroundColor(cBorder);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        if (description != null) {
            b.setContentDescription(description);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.setTooltipText(description);
            }
        }
        return b;
    }

    /**
     * Persist the chosen routing profile and refresh the selector. If a destination is already set,
     * immediately recompute the route with the new profile (no second tap on "Route" needed); if no
     * destination is set yet, just remember the selection for the next route.
     */
    private void selectProfile(String base) {
        if (base.equals(routeProfile)) return; // no change → nothing to recompute
        routeProfile = base;
        getSharedPreferences("nav", MODE_PRIVATE).edit().putString("route_profile", base).apply();
        updateProfileButtons();
        if (destination != null) {
            // Re-route with the freshly-selected profile (uses the current routeProfile).
            requestRoute();
        }
    }

    private void updateProfileButtons() {
        styleSeg(btnBalanced, "trekking".equals(routeProfile));
        styleSeg(btnShortest, "shortest".equals(routeProfile));
        styleSeg(btnQuiet, "quiet".equals(routeProfile));
        updateProfileDesc();
    }

    /** Set the explanatory line under the selector to describe the currently-selected profile. */
    private void updateProfileDesc() {
        if (profileDesc == null) return;
        final String d;
        switch (routeProfile) {
            case "shortest":
                d = "Shortest distance (may use bigger roads).";
                break;
            case "quiet":
                d = "Prefers cycleways and field tracks, avoids main roads.";
                break;
            case "trekking":
            default:
                d = "Mix of roads and paths, avoids motorways.";
                break;
        }
        profileDesc.setText(d);
    }

    private void styleSeg(Button b, boolean on) {
        if (b == null) return;
        b.setTextColor(on ? cOnAccent : cText);
        b.setBackgroundColor(on ? cAccent : cBorder);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initMap() {
        mapView.setClickable(true);
        mapView.getMapScaleBar().setVisible(true);
        mapView.setBuiltInZoomControls(true);

        tileCache = AndroidUtil.createTileCache(this, "navcache",
                mapView.getModel().displayModel.getTileSize(), 1f,
                mapView.getModel().frameBufferModel.getOverdrawFactor());

        MapDataStore mapDataStore = new MapFile(mapFile);
        try {
            activeMapBbox = mapDataStore.boundingBox();
        } catch (Throwable t) {
            Log.w(TAG, "could not read active map bbox", t);
            activeMapBbox = null;
        }
        tileRendererLayer = new TileRendererLayer(tileCache, mapDataStore,
                mapView.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
        tileRendererLayer.setXmlRenderTheme(resolveRenderTheme());
        mapView.getLayerManager().getLayers().add(tileRendererLayer);

        // Dark backdrop while tiles are loading / at the map edges.
        mapView.getModel().displayModel.setBackgroundColor(0xFF0E1116);

        // Long-press anywhere sets the destination.
        mapView.getLayerManager().getLayers().add(new Layer() {
            @Override
            public void draw(BoundingBox boundingBox, byte zoomLevel, Canvas canvas, Point topLeftPoint) {
                // transparent - event capture only
            }

            @Override
            public boolean onLongPress(LatLong tapLatLong, Point layerXY, Point tapXY) {
                ui.post(() -> setDestination(tapLatLong, true));
                return true;
            }
        });

        // A user drag/pan disables auto-follow, so the map no longer snaps back to the GPS position
        // on the next fix. The "⌖ Center" button re-enables following. Returning false lets the
        // MapView still handle the pan gesture itself.
        mapView.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) followMode = false;
            return false;
        });

        // Reload visible POIs (debounced) whenever the viewport changes.
        mapView.getModel().mapViewPosition.addObserver(new Observer() {
            @Override
            public void onChange() {
                if (!showCamping && !showCharging) return;
                long now = System.currentTimeMillis();
                if (now - lastPoiReloadAt < 1200L) return;
                lastPoiReloadAt = now;
                ui.postDelayed(() -> reloadPois(), 400L);
            }
        });

        mapView.setCenter(DEFAULT_CENTER);
        mapView.setZoomLevel(START_ZOOM);
    }

    /**
     * The render theme for the tile layer:
     * <ol>
     *   <li>Dark map enabled → the bundled dark theme at {@code assets/render/dark.xml}, loaded as an
     *       {@link AssetsRenderTheme}. It is force-parsed here so a malformed theme falls back to
     *       {@link InternalRenderTheme#OSMARENDER} instead of leaving a blank map.</li>
     *   <li>Else an external theme dropped at {@code nav/theme.xml}, if present.</li>
     *   <li>Else the bundled Mapsforge DEFAULT theme, which labels place=town from zoom 8 and
     *       place=village from zoom 12 (unlike OSMARENDER, which only names towns from zoom 12).</li>
     * </ol>
     */
    private XmlRenderTheme resolveRenderTheme() {
        if (mapDark) {
            try {
                AssetsRenderTheme dark = new AssetsRenderTheme(getAssets(), "render/", "dark.xml");
                // Force a parse now: a parse failure throws here and we fall back below (never blank).
                if (mapView != null) {
                    RenderThemeHandler.getRenderTheme(AndroidGraphicFactory.INSTANCE,
                            mapView.getModel().displayModel, dark);
                }
                return dark;
            } catch (Throwable t) {
                Log.w(TAG, "dark render theme failed to load/parse, using bundled DEFAULT", t);
                return InternalRenderTheme.DEFAULT;
            }
        }
        try {
            File ext = navDir != null ? new File(navDir, "theme.xml") : null;
            if (ext != null && ext.isFile()) {
                return new ExternalRenderTheme(ext);
            }
        } catch (Throwable t) {
            Log.w(TAG, "external theme load failed, using bundled", t);
        }
        // DEFAULT is the bundled Mapsforge theme; unlike OSMARENDER it labels place=town from
        // zoom 8 and place=village from zoom 12, so moderately sized towns are named at mid zoom,
        // not only when fully zoomed in.
        return InternalRenderTheme.DEFAULT;
    }

    /**
     * Add the compact dark-map toggle (a sun/moon glyph) to the right side of the title bar, just
     * left of the "Maps" action. It reflects and toggles the persisted {@code map_dark} preference.
     */
    private void addDarkMapToggleToHeader(LinearLayout header) {
        if (header == null) return;
        mapDark = getSharedPreferences("nav", MODE_PRIVATE).getBoolean("map_dark", false);
        mapDarkIcon = new TextView(this);
        mapDarkIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mapDarkIcon.setGravity(Gravity.CENTER);
        mapDarkIcon.setClickable(true);
        mapDarkIcon.setLayoutParams(new LinearLayout.LayoutParams(
                dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
        mapDarkIcon.setOnClickListener(v -> toggleMapDark());
        updateMapDarkIcon();
        // Insert before the last child (the "Maps" action / spacer) so it sits on the right side.
        int idx = Math.max(0, header.getChildCount() - 1);
        header.addView(mapDarkIcon, idx);
    }

    /** Reflect the current dark-map state in the title-bar icon (moon = dark on, sun = dark off). */
    private void updateMapDarkIcon() {
        if (mapDarkIcon == null) return;
        mapDarkIcon.setText(mapDark ? "☾" : "☀"); // ☾ moon (dark on) / ☀ sun (dark off)
        mapDarkIcon.setTextColor(mapDark ? NavUi.ACCENT : NavUi.TEXT);
        mapDarkIcon.setContentDescription(mapDark ? "Dark map on" : "Dark map off");
    }

    /** Toggle the dark-map render theme: persist it, update the icon, and re-render the tiles. */
    private void toggleMapDark() {
        mapDark = !mapDark;
        getSharedPreferences("nav", MODE_PRIVATE).edit().putBoolean("map_dark", mapDark).apply();
        updateMapDarkIcon();
        applyRenderTheme();
        toast(mapDark ? "Dark map on" : "Dark map off");
    }

    /**
     * Add the compact voice-guidance toggle (a speaker glyph) to the title bar, next to the dark-map
     * icon. It reflects and toggles the persisted {@code voice_on} preference (default on).
     */
    private void addVoiceToggleToHeader(LinearLayout header) {
        if (header == null) return;
        voiceOn = getSharedPreferences("nav", MODE_PRIVATE).getBoolean("voice_on", true);
        voiceIcon = new TextView(this);
        voiceIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        voiceIcon.setGravity(Gravity.CENTER);
        voiceIcon.setClickable(true);
        voiceIcon.setLayoutParams(new LinearLayout.LayoutParams(
                dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
        voiceIcon.setOnClickListener(v -> toggleVoice());
        updateVoiceIcon();
        // Insert before the last child (the "Maps" action) so it sits next to the dark-map icon.
        int idx = Math.max(0, header.getChildCount() - 1);
        header.addView(voiceIcon, idx);
    }

    /** Reflect the current voice state in the title-bar icon (speaker on / muted). */
    private void updateVoiceIcon() {
        if (voiceIcon == null) return;
        voiceIcon.setText(voiceOn ? "🔊" : "🔇");
        voiceIcon.setTextColor(voiceOn ? NavUi.ACCENT : NavUi.MUTED);
        voiceIcon.setContentDescription(voiceOn ? "Voice guidance on" : "Voice guidance off");
    }

    /** Toggle voice guidance: persist it, update the icon, and silence any current utterance. */
    private void toggleVoice() {
        voiceOn = !voiceOn;
        getSharedPreferences("nav", MODE_PRIVATE).edit().putBoolean("voice_on", voiceOn).apply();
        updateVoiceIcon();
        if (!voiceOn && tts != null) tts.stop();
        toast(voiceOn ? "Voice guidance on" : "Voice guidance off");
    }

    /** Re-apply the current render theme to the live tile layer and force tiles to re-render. */
    private void applyRenderTheme() {
        if (tileRendererLayer == null || mapView == null) return;
        try {
            tileRendererLayer.setXmlRenderTheme(resolveRenderTheme());
            // Drop cached tiles so they re-render with the new theme, then redraw.
            if (tileCache != null) tileCache.purge();
            tileRendererLayer.requestRedraw();
            mapView.getLayerManager().redrawLayers();
        } catch (Throwable t) {
            Log.e(TAG, "applyRenderTheme failed", t);
        }
    }

    // ─────────────────────────────────────────────── recorded-ride display ──

    /**
     * Draw a recorded ride (JSON array of {@code {lat, lon}}) as a polyline and fit the map to it.
     * Display-only: auto-follow is disabled so the fitted view is not yanked to the GPS position.
     */
    private void showRecordedTrack(String json) {
        try {
            if (mapView == null) return;
            final List<LatLong> pts = parseTrackPoints(json);
            if (pts.isEmpty()) {
                toast("Recorded route has no points");
                return;
            }
            followMode = false;

            Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
            paint.setColor(0xFF2FA0EB);
            paint.setStrokeWidth(dp(6));
            paint.setStyle(Style.STROKE);
            if (trackLayer != null) {
                mapView.getLayerManager().getLayers().remove(trackLayer);
                trackLayer = null;
            }
            trackLayer = new Polyline(paint, AndroidGraphicFactory.INSTANCE);
            trackLayer.addPoints(pts);
            mapView.getLayerManager().getLayers().add(trackLayer);

            if (banner != null) banner.setText("Recorded route - " + pts.size() + " points");

            // Fit the map to the track's bounding box once the MapView has been laid out.
            final BoundingBox bb = new BoundingBox(pts);
            final LatLong first = pts.get(0);
            mapView.post(() -> fitToBounds(bb, first));
        } catch (Throwable t) {
            Log.e(TAG, "showRecordedTrack failed", t);
            toast("Could not display the recorded route");
        }
    }

    /** Center + zoom the map so the whole bounding box fits; clamps zoom for degenerate/tiny tracks. */
    private void fitToBounds(BoundingBox bb, LatLong fallbackCenter) {
        try {
            if (mapView == null || bb == null) return;
            int w = mapView.getWidth();
            int h = mapView.getHeight();
            if (w <= 0 || h <= 0) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
            int tile = mapView.getModel().displayModel.getTileSize();
            byte zoom = LatLongUtils.zoomForBounds(new Dimension(w, h), bb, tile);
            if (zoom > 17) zoom = 17;
            if (zoom < 3) zoom = 3;
            LatLong center = bb.getCenterPoint();
            mapView.getModel().mapViewPosition.setMapPosition(
                    new MapPosition(center != null ? center : fallbackCenter, zoom));
            mapView.getLayerManager().redrawLayers();
        } catch (Throwable t) {
            Log.e(TAG, "fitToBounds failed", t);
        }
    }

    /** Parse a JSON array of {@code {lat, lon}} objects into LatLong points (invalid entries skipped). */
    private static List<LatLong> parseTrackPoints(String json) {
        List<LatLong> out = new ArrayList<>();
        if (json == null) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
                out.add(new LatLong(lat, lon));
            }
        } catch (Throwable t) {
            Log.w(TAG, "recorded-route parse failed", t);
        }
        return out;
    }

    // ─────────────────────────────────────────────── location ──

    private void ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC) startLocationUpdates();
    }

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            if (locationManager == null) {
                locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            }
            if (locationManager == null) return;

            // Native LocationManager keeps the app fully open / free of Google Play Services.
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000L, 2f, locationListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener, Looper.getMainLooper());
            }
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) {
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (last != null) onLocation(last);
        } catch (SecurityException se) {
            Log.w(TAG, "location permission missing", se);
        } catch (Throwable t) {
            Log.e(TAG, "startLocationUpdates failed", t);
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { onLocation(location); }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    private void onLocation(Location location) {
        if (location == null || mapView == null) return;
        lastLocation = location;
        LatLong here = new LatLong(location.getLatitude(), location.getLongitude());

        // GPS-based automatic map selection: if the active map does not cover us but a downloaded
        // one does, switch to it (this rebuilds the screen, so stop processing this fix).
        if (maybeAutoSelectMap(here)) return;

        if (meMarker == null) {
            meMarker = new Marker(here, dotMe, 0, 0);
            mapView.getLayerManager().getLayers().add(meMarker);
        } else {
            meMarker.setLatLong(here);
        }
        if (followMode) {
            mapView.setCenter(here);
        }
        mapView.getLayerManager().redrawLayers();

        if (routePts != null && !routePts.isEmpty()) {
            updateNavigation(here);
        }
    }

    // ─────────────────────────────────────────────── destination / routing ──

    private void setDestination(LatLong d, boolean recenterHint) {
        if (d == null || mapView == null) return;
        destination = d;
        if (destMarker == null) {
            destMarker = new Marker(d, dotDest, 0, 0);
            mapView.getLayerManager().getLayers().add(destMarker);
        } else {
            destMarker.setLatLong(d);
        }
        mapView.getLayerManager().redrawLayers();
        banner.setText(String.format(Locale.US,
                "Destination set: %.5f, %.5f - tap Route", d.latitude, d.longitude));
    }

    /** Fill a "lat, lon" input with the current GPS position, or toast if there is no fix yet. */
    private void useCurrentLocation(EditText field) {
        if (field == null) return;
        if (lastLocation == null) {
            toast("Waiting for GPS fix…");
            return;
        }
        field.setText(String.format(Locale.US, "%.5f, %.5f",
                lastLocation.getLatitude(), lastLocation.getLongitude()));
    }

    private void requestRoute() {
        if (destination == null) {
            toast("No destination set");
            return;
        }
        // Route start: an explicitly entered Start point wins; otherwise use the current GPS
        // position, falling back to the map centre if there is no fix yet.
        LatLong from = startPoint != null
                ? startPoint
                : (lastLocation != null
                        ? new LatLong(lastLocation.getLatitude(), lastLocation.getLongitude())
                        : (mapView != null ? mapView.getModel().mapViewPosition.getCenter() : null));
        if (from == null) {
            toast("Waiting for GPS fix…");
            return;
        }
        final double fLat = from.latitude, fLon = from.longitude;
        final double tLat = destination.latitude, tLon = destination.longitude;
        final String prof = routeProfile;
        setBanner("Preparing bike route…");
        worker.execute(() -> routeFlow(fLat, fLon, tLat, tLon, prof));
    }

    /**
     * Worker thread: make sure the BRouter profile + the {@code .rd5} segments covering the route
     * area are present (downloading any that are missing, with progress in the banner), then route.
     */
    private void routeFlow(double fLat, double fLon, double tLat, double tLon, String profileBase) {
        try {
            File profile = BikeRouter.ensureProfile(getApplicationContext(), profileDir, profileBase);

            List<String> tiles = BikeRouter.tilesFor(fLat, fLon, tLat, tLon, 0.6);
            if (!ensureSegments(tiles)) return; // cancelled while downloading

            BikeRouter.RouteResult r = BikeRouter.route(segmentsDir, profile, fLat, fLon, tLat, tLon);
            ui.post(() -> applyRoute(r));
        } catch (NavDownloader.CancelledException ce) {
            setBanner("Route cancelled.");
        } catch (BikeRouter.RoutingException re) {
            Log.w(TAG, "routing failed: " + re.getMessage());
            ui.post(() -> {
                toast("No bike route found for this area");
                banner.setText("No route found.");
            });
        } catch (Throwable t) {
            Log.e(TAG, "routeFlow failed", t);
            ui.post(() -> {
                String m = t.getMessage();
                toast("Routing failed" + (m != null ? ": " + m : ""));
                banner.setText("Routing failed.");
            });
        }
    }

    /**
     * Worker thread: download any {@code .rd5} tiles in {@code tiles} that are missing from
     * {@code segmentsDir}, showing progress in the banner. A 404 (e.g. an all-sea tile) is skipped.
     *
     * @return {@code false} if cancelled via {@link #routeCancel}, else {@code true}.
     */
    private boolean ensureSegments(List<String> tiles) throws IOException {
        List<String> missing = new ArrayList<>();
        for (String t : tiles) {
            File seg = PathGuard.childOf(segmentsDir, t + ".rd5");
            if (!seg.isFile() || seg.length() == 0) missing.add(t);
        }
        int idx = 0;
        for (String t : missing) {
            if (routeCancel.get()) return false;
            idx++;
            final int cur = idx, total = missing.size();
            final String tile = t;
            setBanner("Downloading routing data " + cur + "/" + total + "  (" + tile + ")…");
            File seg = PathGuard.childOf(segmentsDir, tile + ".rd5");
            try {
                NavDownloader.download(BikeRouter.SEGMENT_BASE_URL + tile + ".rd5", seg,
                        (done, tot) -> {
                            int pct = tot > 0 ? (int) (done * 100 / tot) : 0;
                            setBanner("Downloading routing data " + cur + "/" + total + "  "
                                    + tile + "  " + pct + "%  ("
                                    + NavDownloader.humanBytes(done)
                                    + (tot > 0 ? "/" + NavDownloader.humanBytes(tot) : "") + ")");
                        }, routeCancel);
            } catch (NavDownloader.HttpException he) {
                // 404 → no segment for this tile (e.g. all sea) - skip it, keep going.
                if (he.code == 404) {
                    Log.i(TAG, "no BRouter segment for " + tile + " (skipping)");
                } else {
                    throw he;
                }
            }
        }
        return !routeCancel.get();
    }

    /** UI thread: draw the polyline and prepare live-guidance data. */
    private void applyRoute(BikeRouter.RouteResult r) {
        try {
            if (mapView == null || r == null) return;
            if (routeLayer != null) {
                mapView.getLayerManager().getLayers().remove(routeLayer);
                routeLayer = null;
            }

            List<LatLong> pts = new ArrayList<>(r.points.size());
            for (double[] p : r.points) pts.add(new LatLong(p[0], p[1]));
            if (pts.size() < 2) {
                toast("Route too short");
                banner.setText("Route too short.");
                return;
            }

            Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
            paint.setColor(0xFF2FA0EB);
            paint.setStrokeWidth(dp(6));
            paint.setStyle(Style.STROKE);
            routeLayer = new Polyline(paint, AndroidGraphicFactory.INSTANCE);
            routeLayer.addPoints(pts);
            mapView.getLayerManager().getLayers().add(routeLayer);
            // Keep markers on top of the route line.
            if (meMarker != null) {
                mapView.getLayerManager().getLayers().remove(meMarker);
                mapView.getLayerManager().getLayers().add(meMarker);
            }
            if (destMarker != null) {
                mapView.getLayerManager().getLayers().remove(destMarker);
                mapView.getLayerManager().getLayers().add(destMarker);
            }

            buildGuidance(pts);

            followMode = true;
            if (navigating) {
                // Reroute during active navigation: keep the close follow camera on the rider.
                mapView.getModel().mapViewPosition.setZoomLevel(NAV_ZOOM);
                LatLong c = lastLocation != null
                        ? new LatLong(lastLocation.getLatitude(), lastLocation.getLongitude())
                        : pts.get(0);
                mapView.setCenter(c);
            } else {
                mapView.getModel().mapViewPosition.setCenter(pts.get(0));
            }
            mapView.getLayerManager().redrawLayers();

            double km = r.distanceMeters / 1000.0;
            banner.setText(String.format(Locale.US,
                    "Bike route: %.1f km - follow the blue line", km));

            // A route now exists → offer "Start" (unless we are already navigating).
            if (startBtn != null) startBtn.setVisibility(navigating ? View.GONE : View.VISIBLE);

            if (lastLocation != null) {
                updateNavigation(new LatLong(lastLocation.getLatitude(), lastLocation.getLongitude()));
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyRoute failed", t);
        }
    }

    /**
     * Precompute cumulative distances and maneuver anchors for the live banner. BRouter returns
     * geometry only, so turns are derived from bearing changes along the polyline.
     */
    private void buildGuidance(List<LatLong> pts) {
        routePts = pts;
        int n = pts.size();
        cumDist = new double[n];
        cumDist[0] = 0;
        for (int i = 1; i < n; i++) {
            cumDist[i] = cumDist[i - 1] + haversine(pts.get(i - 1), pts.get(i));
        }

        List<Integer> idxs = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        double lastTurnDist = -1000;
        for (int i = 1; i < n - 1; i++) {
            double b1 = bearing(pts.get(i - 1), pts.get(i));
            double b2 = bearing(pts.get(i), pts.get(i + 1));
            double turn = angleDiff(b1, b2);      // + = right, - = left
            if (Math.abs(turn) >= TURN_THRESHOLD_DEG) {
                if (cumDist[i] - lastTurnDist < 25) continue; // don't cluster micro-turns
                lastTurnDist = cumDist[i];
                idxs.add(i);
                texts.add(turnText(turn));
            }
        }
        // Always guarantee a terminal "arrive" maneuver at the last point.
        idxs.add(n - 1);
        texts.add("Arrive at destination");

        maneuverIdx = new int[idxs.size()];
        maneuverText = new String[texts.size()];
        for (int i = 0; i < idxs.size(); i++) {
            maneuverIdx[i] = idxs.get(i);
            maneuverText[i] = texts.get(i);
        }

        // Fresh maneuver set (initial route or a reroute) - allow every maneuver to announce again.
        lastFarIdx = -1;
        lastNearIdx = -1;
        announcedArrive = false;
    }

    /** Live guidance: update the banner and trigger re-routing when off-route. */
    private void updateNavigation(LatLong here) {
        if (routePts == null || cumDist == null || maneuverIdx == null) return;
        int k = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < routePts.size(); i++) {
            double d = haversine(here, routePts.get(i));
            if (d < best) {
                best = d;
                k = i;
            }
        }

        // Off-route → recompute (throttled).
        if (best > OFF_ROUTE_METERS) {
            long now = System.currentTimeMillis();
            if (destination != null && now - lastRerouteAt > REROUTE_COOLDOWN_MS) {
                lastRerouteAt = now;
                banner.setText("Off route - recalculating…");
                if (navigating && navTurnText != null) {
                    navTurnArrow.setText("↻");
                    navTurnText.setText("Recalculating…");
                    navTurnDist.setText("");
                }
                final double fLat = here.latitude, fLon = here.longitude;
                final double tLat = destination.latitude, tLon = destination.longitude;
                final String prof = routeProfile;
                worker.execute(() -> routeFlow(fLat, fLon, tLat, tLon, prof));
            }
            return;
        }

        // Next maneuver strictly ahead of our nearest route point.
        int mi = -1;
        for (int i = 0; i < maneuverIdx.length; i++) {
            if (maneuverIdx[i] > k) {
                mi = i;
                break;
            }
        }
        String nextText;
        double distToNext;
        int maneuverKey;   // stable key (index into maneuverIdx[]) for the per-maneuver voice guards
        if (mi < 0) {
            nextText = "Arrive at destination";
            distToNext = Math.max(0, cumDist[cumDist.length - 1] - cumDist[k]);
            maneuverKey = maneuverIdx.length - 1;
        } else {
            nextText = maneuverText[mi];
            distToNext = Math.max(0, cumDist[maneuverIdx[mi]] - cumDist[k]);
            maneuverKey = mi;
        }
        double remaining = Math.max(0, cumDist[cumDist.length - 1] - cumDist[k]);
        banner.setText(String.format(Locale.US, "%s in %s%n%s to destination",
                nextText, formatMeters(distToNext), formatMeters(remaining)));

        if (navigating) {
            updateNavCard(nextText, distToNext, remaining);
            announce(maneuverKey, mi, nextText, distToNext, remaining);
        }
    }

    // ─────────────────────────────────────────────── active navigation mode ──

    /** Enter active turn-by-turn navigation: close follow camera, top card, voice, hidden controls. */
    private void startNavigation() {
        if (routePts == null || routePts.isEmpty()) {
            toast("Compute a route first");
            return;
        }
        navigating = true;
        // Fresh run - let every maneuver announce again.
        lastFarIdx = -1;
        lastNearIdx = -1;
        announcedArrive = false;

        if (topContainer != null) topContainer.setVisibility(View.GONE);
        if (banner != null) banner.setVisibility(View.GONE);
        if (startBtn != null) startBtn.setVisibility(View.GONE);
        if (navCard != null) navCard.setVisibility(View.VISIBLE);

        followMode = true;
        if (mapView != null) {
            preNavZoom = mapView.getModel().mapViewPosition.getZoomLevel();
            mapView.getModel().mapViewPosition.setZoomLevel(NAV_ZOOM);
            LatLong center = lastLocation != null
                    ? new LatLong(lastLocation.getLatitude(), lastLocation.getLongitude())
                    : routePts.get(0);
            mapView.setCenter(center);
        }

        LatLong here = lastLocation != null
                ? new LatLong(lastLocation.getLatitude(), lastLocation.getLongitude())
                : routePts.get(0);
        updateNavigation(here);
    }

    /** Leave active navigation and return to the route overview (normal zoom + banner). */
    private void stopNavigation() {
        navigating = false;
        if (tts != null) tts.stop();
        if (navCard != null) navCard.setVisibility(View.GONE);
        if (topContainer != null) topContainer.setVisibility(View.VISIBLE);
        if (banner != null) banner.setVisibility(View.VISIBLE);
        if (startBtn != null && routePts != null && !routePts.isEmpty()) {
            startBtn.setVisibility(View.VISIBLE);
        }
        if (mapView != null) {
            mapView.getModel().mapViewPosition.setZoomLevel(preNavZoom);
        }
        if (lastLocation != null && routePts != null && !routePts.isEmpty()) {
            updateNavigation(new LatLong(lastLocation.getLatitude(), lastLocation.getLongitude()));
        }
    }

    /** Build the top NEXT-TURN card: big arrow + instruction + distance, secondary line, Stop. */
    private View buildNavCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(cBanner);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        navTurnArrow = new TextView(this);
        navTurnArrow.setText("↑");
        navTurnArrow.setTextColor(cAccent);
        navTurnArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        navTurnArrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                dp(56), ViewGroup.LayoutParams.WRAP_CONTENT);
        arrowLp.rightMargin = dp(12);
        navTurnArrow.setLayoutParams(arrowLp);
        row.addView(navTurnArrow);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        navTurnText = new TextView(this);
        navTurnText.setText("-");
        navTurnText.setTextColor(cText);
        navTurnText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        navTurnText.setTypeface(Typeface.DEFAULT_BOLD);
        navTurnText.setSingleLine(true);
        navTurnText.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(navTurnText);

        navTurnDist = new TextView(this);
        navTurnDist.setText("");
        navTurnDist.setTextColor(cAccent);
        navTurnDist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        navTurnDist.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(navTurnDist);

        row.addView(texts);
        card.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(8);
        row2.setLayoutParams(row2Lp);

        navSecondary = new TextView(this);
        navSecondary.setText("");
        navSecondary.setTextColor(cMuted);
        navSecondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        navSecondary.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(navSecondary);

        Button stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setAllCaps(false);
        stopBtn.setOnClickListener(v -> stopNavigation());
        row2.addView(stopBtn);

        card.addView(row2);
        return card;
    }

    /** Refresh the NEXT-TURN card: arrow glyph, instruction, distance, and remaining + ETA line. */
    private void updateNavCard(String instruction, double distToNext, double remaining) {
        if (navTurnText == null) return;
        navTurnArrow.setText(arrowFor(instruction));
        navTurnText.setText(instruction);
        navTurnDist.setText(formatMeters(distToNext));
        navSecondary.setText(formatMeters(remaining) + " to destination · " + etaText(remaining));
    }

    /** Rough ETA from remaining distance: recent GPS speed if usable, else an assumed cycling speed. */
    private String etaText(double remainingMeters) {
        if (remainingMeters < ARRIVE_ANNOUNCE_M) return "arriving";
        double speedMps = CYCLING_KMH / 3.6;
        if (lastLocation != null && lastLocation.hasSpeed() && lastLocation.getSpeed() > 1.0) {
            speedMps = lastLocation.getSpeed();
        }
        int mins = (int) Math.round(remainingMeters / speedMps / 60.0);
        if (mins < 1) mins = 1;
        return "~" + mins + " min";
    }

    /** A simple direction glyph for the card, derived from the English instruction text. */
    private static String arrowFor(String t) {
        if (t == null) return "↑";
        String s = t.toLowerCase(Locale.US);
        if (s.contains("arrive")) return "⚑";
        if (s.contains("left")) return "←";
        if (s.contains("right")) return "→";
        return "↑"; // continue straight
    }

    /**
     * Speak the next maneuver as the rider approaches it. Two thresholds, each fired at most once per
     * maneuver: a heads-up at ~200 m ("In 200 meters, …") and a cue at the turn (~30 m, "… now"), plus
     * a single arrival announcement near the end. Spoken in the phone's language via {@link NavVoice}
     * (the on-screen card stays English); no-op when voice is off.
     */
    private void announce(int maneuverKey, int mi, String instruction, double distToNext, double remaining) {
        if (!voiceOn || tts == null || !tts.isReady() || navVoice == null) return;

        boolean isArrive = (mi < 0) || (mi == maneuverIdx.length - 1);
        if (isArrive) {
            if (!announcedArrive && remaining <= ARRIVE_ANNOUNCE_M) {
                announcedArrive = true;
                tts.speak(navVoice.arrive());
            }
            return;
        }

        String phrase = navVoice.maneuver(instruction);
        // Far heads-up (~200 m).
        if (distToNext <= ANNOUNCE_FAR_M && distToNext > ANNOUNCE_NEAR_M && lastFarIdx != maneuverKey) {
            lastFarIdx = maneuverKey;
            tts.speak(navVoice.far(phrase));
        }
        // At-the-turn cue (~30 m).
        if (distToNext <= ANNOUNCE_NEAR_M && lastNearIdx != maneuverKey) {
            lastNearIdx = maneuverKey;
            tts.speak(navVoice.near(phrase));
        }
    }

    private static String turnText(double turn) {
        String dir = turn > 0 ? "right" : "left";
        double a = Math.abs(turn);
        if (a >= 115) return "Turn sharp " + dir;
        return "Turn " + dir;
    }

    // ─────────────────────────────────────────────── POI overlays ──

    private void reloadPois() {
        if (mapView == null) return;
        if (!showCamping && !showCharging) {
            clearPoiMarkers();
            return;
        }
        if (!hasPoi()) {
            toast("No POI data for this map");
            clearPoiMarkers();
            return;
        }
        final BoundingBox bb;
        try {
            bb = mapView.getBoundingBox();
        } catch (Throwable t) {
            return;
        }
        final boolean wantCamp = showCamping;
        final boolean wantCharge = showCharging;
        worker.execute(() -> loadPois(bb, wantCamp, wantCharge));
    }

    /** Worker thread: query the offline POI database and filter by tags. */
    private void loadPois(BoundingBox bb, boolean wantCamp, boolean wantCharge) {
        try {
            PoiPersistenceManager mgr = ensurePoiManager();
            if (mgr == null) {
                ui.post(() -> toast("POI database could not be opened"));
                return;
            }
            PoiCategoryFilter filter = new WhitelistPoiCategoryFilter();
            try {
                PoiCategory root = mgr.getCategoryManager().getRootCategory();
                ((WhitelistPoiCategoryFilter) filter).addCategory(root);
            } catch (Throwable t) {
                Log.w(TAG, "no root POI category", t);
            }

            java.util.Collection<PointOfInterest> pois =
                    mgr.findInRect(bb, filter, null, null, 800, false);

            final List<PoiHit> hits = new ArrayList<>();
            for (PointOfInterest poi : pois) {
                PoiKind kind = classify(poi);
                if (kind == PoiKind.CAMPING && wantCamp) {
                    hits.add(new PoiHit(poi, kind));
                } else if (kind == PoiKind.CHARGING && wantCharge) {
                    hits.add(new PoiHit(poi, kind));
                }
            }
            ui.post(() -> renderPois(hits));
        } catch (Throwable t) {
            Log.e(TAG, "loadPois failed", t);
            ui.post(() -> toast("POI load failed"));
        }
    }

    private synchronized PoiPersistenceManager ensurePoiManager() {
        if (poiManager != null && !poiManager.isClosed()) return poiManager;
        try {
            poiManager = AndroidPoiPersistenceManagerFactory.getPoiPersistenceManager(
                    poiFile.getAbsolutePath());
            return poiManager;
        } catch (Throwable t) {
            Log.e(TAG, "open POI db failed", t);
            return null;
        }
    }

    private void renderPois(List<PoiHit> hits) {
        if (mapView == null) return;
        clearPoiMarkers();
        for (final PoiHit hit : hits) {
            Bitmap bmp = hit.kind == PoiKind.CAMPING ? dotCamp : dotCharge;
            LatLong at = new LatLong(hit.poi.getLatitude(), hit.poi.getLongitude());
            Marker m = new Marker(at, bmp, 0, 0) {
                @Override
                public boolean onTap(LatLong tapLatLong, Point layerXY, Point tapXY) {
                    if (contains(layerXY, tapXY)) {
                        showPoiDialog(hit);
                        return true;
                    }
                    return false;
                }
            };
            poiMarkers.add(m);
            mapView.getLayerManager().getLayers().add(m);
        }
        mapView.getLayerManager().redrawLayers();
    }

    private void clearPoiMarkers() {
        if (mapView == null) return;
        for (Marker m : poiMarkers) {
            mapView.getLayerManager().getLayers().remove(m);
        }
        poiMarkers.clear();
        mapView.getLayerManager().redrawLayers();
    }

    private void showPoiDialog(PoiHit hit) {
        String name = hit.poi.getName();
        if (name == null || name.trim().isEmpty()) name = "(unnamed)";

        // Curated, human-readable detail view. We deliberately do NOT dump every raw OSM tag - many
        // are noise (wikidata refs, normalized_name, source, wheelchair, ...) that only confuse. We
        // surface the handful of fields a rider cares about, and render the website as a clickable link.
        StringBuilder html = new StringBuilder();
        html.append(String.format(Locale.US, "%.5f, %.5f", hit.poi.getLatitude(), hit.poi.getLongitude()));

        if (hit.kind == PoiKind.CHARGING) {
            List<String> sockets = new ArrayList<>();
            if (hasSchuko(hit.poi)) sockets.add("Schuko");
            if (hasType2(hit.poi)) sockets.add("Type 2");
            appendField(html, "Sockets", sockets.isEmpty() ? "unknown" : TextUtils.join(", ", sockets));
        }
        appendField(html, "Operator", tagValue(hit.poi, "operator", "brand", "network"));
        appendField(html, "Capacity", tagValue(hit.poi, "capacity"));
        appendField(html, "Opening hours", tagValue(hit.poi, "opening_hours"));
        appendField(html, "Fee", tagValue(hit.poi, "fee", "charge"));
        appendField(html, "Access", tagValue(hit.poi, "access"));
        appendField(html, "Description", tagValue(hit.poi, "description", "description:de", "note"));
        appendField(html, "Phone", tagValue(hit.poi, "phone", "contact:phone"));

        String web = tagValue(hit.poi, "website", "contact:website", "url", "operator:website");
        if (web != null && !web.trim().isEmpty()) {
            String url = web.trim();
            if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
            html.append("<br><br><b>Website</b><br><a href=\"")
                    .append(escapeHtml(url)).append("\">")
                    .append(escapeHtml(web.trim())).append("</a>");
        }

        try {
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle((hit.kind == PoiKind.CAMPING ? "⛺ " : "⚡ ") + name)
                    .setMessage(fromHtml(html.toString()))
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Route here", (d, w) -> {
                        setDestination(new LatLong(hit.poi.getLatitude(), hit.poi.getLongitude()), false);
                        requestRoute();
                    })
                    .create();
            dlg.show();
            // Make the website link tappable. Reusing the themed message TextView keeps the dialog's
            // own text colors (a fully custom view would risk unreadable text on some themes).
            TextView msg = dlg.findViewById(android.R.id.message);
            if (msg != null) msg.setMovementMethod(LinkMovementMethod.getInstance());
        } catch (Throwable t) {
            Log.e(TAG, "poi dialog failed", t);
        }
    }

    private void appendField(StringBuilder html, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        html.append("<br><b>").append(label).append("</b>: ").append(escapeHtml(value.trim()));
    }

    /** First non-empty value among the given OSM tag keys (case-insensitive), or null. */
    private static String tagValue(PointOfInterest poi, String... keys) {
        if (poi.getTags() == null) return null;
        for (String want : keys) {
            for (Tag t : poi.getTags()) {
                if (t == null || t.key == null || t.value == null) continue;
                if (t.key.equalsIgnoreCase(want) && !t.value.trim().isEmpty()) return t.value;
            }
        }
        return null;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @SuppressWarnings("deprecation")
    private static CharSequence fromHtml(String s) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY);
        }
        return Html.fromHtml(s);
    }

    // POI classification helpers (OSM tags are the source of truth, independent of POI category naming).

    private enum PoiKind { CAMPING, CHARGING, OTHER }

    private static final class PoiHit {
        final PointOfInterest poi;
        final PoiKind kind;
        PoiHit(PointOfInterest poi, PoiKind kind) { this.poi = poi; this.kind = kind; }
    }

    private static PoiKind classify(PointOfInterest poi) {
        if (poi.getTags() != null) {
            for (Tag t : poi.getTags()) {
                if (t == null || t.key == null) continue;
                String k = t.key.toLowerCase(Locale.US);
                String v = t.value == null ? "" : t.value.toLowerCase(Locale.US);
                if ("tourism".equals(k) && "camp_site".equals(v)) return PoiKind.CAMPING;
                if ("amenity".equals(k) && "charging_station".equals(v)) return PoiKind.CHARGING;
            }
        }
        // Fall back to the POI category title.
        try {
            PoiCategory c = poi.getCategory();
            if (c != null && c.getTitle() != null) {
                String title = c.getTitle().toLowerCase(Locale.US);
                if (title.contains("camp")) return PoiKind.CAMPING;
                if (title.contains("charg")) return PoiKind.CHARGING;
            }
        } catch (Throwable ignored) {
        }
        return PoiKind.OTHER;
    }

    private static boolean hasSchuko(PointOfInterest poi) {
        if (poi.getTags() == null) return false;
        for (Tag t : poi.getTags()) {
            if (t == null || t.key == null) continue;
            String k = t.key.toLowerCase(Locale.US);
            if ((k.equals("socket:schuko") || k.startsWith("socket:sev1011")) && !isZero(t.value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasType2(PointOfInterest poi) {
        if (poi.getTags() == null) return false;
        for (Tag t : poi.getTags()) {
            if (t == null || t.key == null) continue;
            String k = t.key.toLowerCase(Locale.US);
            // socket:type2, socket:type2_combo, socket:type2_cable …
            if (k.startsWith("socket:type2") && !isZero(t.value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isZero(String v) {
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.US);
        return v.equals("0") || v.equals("no") || v.equals("false");
    }

    // ─────────────────────────────────────────────── helpers ──

    private void createMarkerBitmaps() {
        int r = dp(14);
        dotMe = dot(0xFF2FA0EB, r);      // blue - current position
        dotDest = dot(0xFFE0399B, r);    // magenta - destination
        dotCamp = dot(0xFF3FBF5F, r);    // green - camping
        dotCharge = dot(0xFFF0A030, r);  // orange - charging
    }

    private Bitmap dot(int color, int size) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), Color.WHITE);
        g.setSize(size, size);
        g.setBounds(0, 0, size, size);
        return AndroidGraphicFactory.convertToBitmap(g);
    }

    private static LatLong parseLatLon(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        String[] parts = s.split("[,;\\s]+");
        if (parts.length < 2) return null;
        try {
            double lat = Double.parseDouble(parts[0]);
            double lon = Double.parseDouble(parts[1]);
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
            return new LatLong(lat, lon);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double haversine(LatLong a, LatLong b) {
        double R = 6371000.0;
        double dLat = Math.toRadians(b.latitude - a.latitude);
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double la1 = Math.toRadians(a.latitude);
        double la2 = Math.toRadians(b.latitude);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Initial bearing from a to b, degrees 0..360. */
    private static double bearing(LatLong a, LatLong b) {
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double brng = Math.toDegrees(Math.atan2(y, x));
        return (brng + 360.0) % 360.0;
    }

    /** Signed smallest angle from→to in (-180,180]; positive = right turn. */
    private static double angleDiff(double from, double to) {
        double d = (to - from) % 360.0;   // normalize without a floating-point loop counter
        if (d > 180) d -= 360;
        else if (d < -180) d += 360;
        return d;
    }

    private static String formatMeters(double m) {
        if (m < 1000) return String.format(Locale.US, "%d m", Math.round(m));
        return String.format(Locale.US, "%.1f km", m / 1000.0);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void setBanner(String msg) {
        ui.post(() -> {
            if (banner != null) banner.setText(msg);
        });
    }

    private void toast(String msg) {
        ui.post(() -> {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        routeCancel.set(true);
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            if (locationManager != null) locationManager.removeUpdates(locationListener);
        } catch (Throwable ignored) {
        }
        try {
            worker.shutdownNow();
        } catch (Throwable ignored) {
        }
        try {
            if (poiManager != null) poiManager.close();
        } catch (Throwable ignored) {
        }
        try {
            if (tileRendererLayer != null) tileRendererLayer.onDestroy();
        } catch (Throwable ignored) {
        }
        try {
            if (tileCache != null) tileCache.destroy();
        } catch (Throwable ignored) {
        }
        try {
            if (mapView != null) mapView.destroyAll();
        } catch (Throwable ignored) {
        }
        try {
            AndroidGraphicFactory.clearResourceMemoryCache();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
