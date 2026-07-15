// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.mapsforge.core.model.BoundingBox;

import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

/**
 * Offline BICYCLE routing via BRouter (btools).
 *
 * <p>BRouter routes over small, per-region {@code .rd5} segment files (5°×5° tiles) that are
 * downloaded on demand inside the app - no multi-GB pre-built graph. The shipped trekking bike
 * profile ({@code assets/brouter/trekking.brf}) gives {@code highway=motorway|motorway_link} a
 * cost factor of 10000, i.e. motorways/trunk are effectively excluded.
 *
 * <p>Data layout under {@code getExternalFilesDir("nav")}:
 * <ul>
 *   <li>{@code segments/E5_N45.rd5 …} - downloaded routing segments</li>
 *   <li>{@code profiles/trekking.brf}, {@code profiles/lookups.dat} - copied from assets</li>
 * </ul>
 */
final class BikeRouter {

    /** Base URL for BRouter routing segments (5°×5° tiles). ODbL OpenStreetMap data. */
    static final String SEGMENT_BASE_URL = "https://brouter.de/brouter/segments4/";

    private BikeRouter() {}

    /** Routing failed in a way we can present to the user (no route, engine error, …). */
    static final class RoutingException extends Exception {
        RoutingException(String msg) { super(msg); }
    }

    /** A computed route: geometry (lat/lon pairs) plus total distance. */
    static final class RouteResult {
        final List<double[]> points = new ArrayList<>(); // each is {lat, lon}
        int distanceMeters;
    }

    // ─────────────────────────────────────────────── profile / assets ──

    /**
     * Copies the bundled default ("trekking") BRouter profile + lookup table into {@code profileDir}
     * and returns the profile file. Backward-compatible overload.
     */
    static File ensureProfile(Context ctx, File profileDir) throws IOException {
        return ensureProfile(ctx, profileDir, "trekking");
    }

    /**
     * Copies the bundled BRouter profile {@code brouter/<profileBase>.brf} + lookup table into
     * {@code profileDir} (once each) and returns the {@code <profileBase>.brf} file.
     * {@code lookups.dat} MUST sit next to the {@code .brf} - BRouter reads it from the profile's
     * parent directory.
     *
     * @param profileBase profile base name without extension, e.g. {@code "trekking"},
     *                    {@code "shortest"} or {@code "quiet"}.
     */
    static File ensureProfile(Context ctx, File profileDir, String profileBase) throws IOException {
        if (!profileDir.exists() && !profileDir.mkdirs() && !profileDir.isDirectory()) {
            throw new IOException("cannot create " + profileDir);
        }
        File brf = new File(profileDir, profileBase + ".brf");
        File lookups = new File(profileDir, "lookups.dat");
        copyAssetIfChanged(ctx, "brouter/" + profileBase + ".brf", brf);
        copyAssetIfChanged(ctx, "brouter/lookups.dat", lookups);
        return brf;
    }

    /**
     * Copies the bundled asset {@code assetPath} to {@code dest} when {@code dest} is missing OR its
     * bytes differ from the current bundled asset.
     *
     * <p>The content check is what makes the route-profile selector actually work: a plain
     * copy-if-missing keeps serving the STALE {@code .brf}/{@code lookups.dat} left in the profiles
     * dir by a previous app version, so after an update that ships new or rewritten profiles the
     * three selections (trekking/shortest/quiet) could all resolve to identical old content and
     * switching the profile would change nothing about the computed route. Re-copying only when the
     * content actually differs keeps the on-disk profile in lock-step with the bundled one without
     * needlessly touching {@code lookups.dat}'s timestamp (which would thrash BRouter's profile
     * cache). The profile assets are tiny (tens of KB), so reading them fully is cheap.
     */
    private static void copyAssetIfChanged(Context ctx, String assetPath, File dest) throws IOException {
        byte[] asset = readAll(ctx.getAssets().open(assetPath));
        if (dest.isFile() && dest.length() == asset.length && Arrays.equals(readFile(dest), asset)) {
            return;
        }
        OutputStream out = new FileOutputStream(dest);
        try {
            out.write(asset);
            out.flush();
        } finally {
            try { out.close(); } catch (IOException ignored) { }
        }
    }

    /** Read a (small) input stream fully, always closing it. */
    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 15);
            byte[] buf = new byte[1 << 15];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Read a (small) file fully into memory. */
    private static byte[] readFile(File f) throws IOException {
        return readAll(new FileInputStream(f));
    }

    // ─────────────────────────────────────────────── segment tiles ──

    /** SW-corner index (multiple of 5, may be negative) of the 5° tile containing {@code deg}. */
    private static int floor5(double deg) {
        return (int) Math.floor(deg / 5.0) * 5;
    }

    /** BRouter tile name for a SW corner, e.g. lon=5,lat=45 → "E5_N45"; lon=-5 → "W5". */
    static String tileName(int lonFloor, int latFloor) {
        String lonPart = lonFloor >= 0 ? "E" + lonFloor : "W" + (-lonFloor);
        String latPart = latFloor >= 0 ? "N" + latFloor : "S" + (-latFloor);
        return lonPart + "_" + latPart;
    }

    /**
     * All segment tile names covering the bounding box of the two points, expanded by
     * {@code marginDeg} so a route bulging outside the direct box is still covered.
     */
    static List<String> tilesFor(double lat1, double lon1, double lat2, double lon2, double marginDeg) {
        double minLat = Math.min(lat1, lat2) - marginDeg;
        double maxLat = Math.max(lat1, lat2) + marginDeg;
        double minLon = Math.min(lon1, lon2) - marginDeg;
        double maxLon = Math.max(lon1, lon2) + marginDeg;
        List<String> tiles = new ArrayList<>();
        for (int lat = floor5(minLat); lat <= floor5(maxLat); lat += 5) {
            for (int lon = floor5(minLon); lon <= floor5(maxLon); lon += 5) {
                String t = tileName(lon, lat);
                if (!tiles.contains(t)) tiles.add(t);
            }
        }
        return tiles;
    }

    /**
     * All segment tile names covering the given bounding box, expanded by {@code marginDeg}. Handy
     * for pre-fetching routing data for a whole downloaded map area (whose bbox comes from the
     * {@code .map} header).
     */
    static List<String> tilesFor(BoundingBox bb, double marginDeg) {
        return tilesFor(bb.minLatitude, bb.minLongitude, bb.maxLatitude, bb.maxLongitude, marginDeg);
    }

    // ─────────────────────────────────────────────── routing ──

    /**
     * Computes a bike route start → end over the segments present in {@code segmentDir}, using the
     * profile at {@code profileFile}. Runs synchronously - call from a worker thread.
     */
    static RouteResult route(File segmentDir, File profileFile,
                             double fromLat, double fromLon, double toLat, double toLon)
            throws RoutingException {
        RoutingContext rc = new RoutingContext();
        rc.localFunction = profileFile.getAbsolutePath(); // BRouter parses the profile from here

        List<OsmNodeNamed> waypoints = new ArrayList<>();
        waypoints.add(node(fromLon, fromLat, "from"));
        waypoints.add(node(toLon, toLat, "to"));

        RoutingEngine engine = new RoutingEngine(null, null, segmentDir, waypoints, rc, 0);
        engine.quite = true;
        engine.doRun(0);

        String err = engine.getErrorMessage();
        if (err != null) throw new RoutingException(err);

        OsmTrack track = engine.getFoundTrack();
        if (track == null || track.nodes == null || track.nodes.isEmpty()) {
            throw new RoutingException("no route found");
        }

        RouteResult result = new RouteResult();
        for (OsmPathElement e : track.nodes) {
            double lat = e.getILat() / 1_000_000.0 - 90.0;
            double lon = e.getILon() / 1_000_000.0 - 180.0;
            result.points.add(new double[]{lat, lon});
        }
        result.distanceMeters = track.distance;
        return result;
    }

    /** BRouter integer coordinate convention: ilon=(lon+180)*1e6, ilat=(lat+90)*1e6. */
    private static OsmNodeNamed node(double lon, double lat, String name) {
        OsmNodeNamed n = new OsmNodeNamed();
        n.ilon = (int) ((lon + 180.0) * 1_000_000.0 + 0.5);
        n.ilat = (int) ((lat + 90.0) * 1_000_000.0 + 0.5);
        n.name = name;
        return n;
    }
}
