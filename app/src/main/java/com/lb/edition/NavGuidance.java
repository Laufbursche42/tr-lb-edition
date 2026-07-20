// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

/**
 * Pure turn-by-turn guidance math. Given the flattened route (parallel lat/lon arrays, cumulative
 * distances and maneuver anchors) plus the current position, it works out the nearest route point,
 * whether we are off-route, the next maneuver, the distance to it and the distance remaining.
 *
 * <p>No Android / UI / TTS / Mapsforge dependencies, so the exact same logic runs inside
 * {@link NavigationService} (off the map screen) and could be unit-tested. This is a byte-for-byte
 * lift of the algorithm that used to live in {@code NavActivity.updateNavigation}.
 */
final class NavGuidance {

    private NavGuidance() {}

    /** Beyond this distance to the nearest route point we consider the rider off-route. */
    static final double OFF_ROUTE_METERS = 60.0;

    /** Result of one guidance evaluation. */
    static final class Fix {
        int nearestIdx;        // index of the nearest route point
        double nearestDist;    // metres to that nearest point
        boolean offRoute;      // nearestDist > OFF_ROUTE_METERS
        boolean arrive;        // the next "maneuver" is the destination (or the final maneuver)
        int mi;                // index into maneuverIdx[] of the next maneuver, or -1 for arrive
        int maneuverKey;       // stable per-maneuver key for the once-each voice guards
        String nextText;       // instruction text for the next maneuver
        double distToNextM;    // metres to the next maneuver
        double remainingM;     // metres to the destination
    }

    /**
     * @return null if the route is empty / invalid.
     */
    static Fix compute(double lat, double lon,
                       double[] lats, double[] lons, double[] cumDist,
                       int[] maneuverIdx, String[] maneuverText) {
        if (lats == null || lons == null || lats.length == 0 || cumDist == null
                || maneuverIdx == null || maneuverText == null || cumDist.length == 0) {
            return null;
        }

        int k = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < lats.length; i++) {
            double d = haversine(lat, lon, lats[i], lons[i]);
            if (d < best) {
                best = d;
                k = i;
            }
        }

        Fix f = new Fix();
        f.nearestIdx = k;
        f.nearestDist = best;
        f.offRoute = best > OFF_ROUTE_METERS;
        if (f.offRoute) return f;

        // Next maneuver strictly ahead of our nearest route point.
        int mi = -1;
        for (int i = 0; i < maneuverIdx.length; i++) {
            if (maneuverIdx[i] > k) {
                mi = i;
                break;
            }
        }
        f.mi = mi;
        double last = cumDist[cumDist.length - 1];
        if (mi < 0) {
            f.nextText = "Arrive at destination";
            f.distToNextM = Math.max(0, last - cumDist[k]);
            f.maneuverKey = maneuverIdx.length - 1;
        } else {
            f.nextText = maneuverText[mi];
            f.distToNextM = Math.max(0, cumDist[maneuverIdx[mi]] - cumDist[k]);
            f.maneuverKey = mi;
        }
        // Mirror the announce() rule: the last maneuver is treated as "arrive" too.
        f.arrive = (mi < 0) || (mi == maneuverIdx.length - 1);
        f.remainingM = Math.max(0, last - cumDist[k]);
        return f;
    }

    /** Great-circle distance in metres (identical to NavActivity.haversine). */
    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double la1 = Math.toRadians(lat1);
        double la2 = Math.toRadians(lat2);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** A compact glyph for the map card / dashboard banner, derived from the instruction text. */
    static String arrowFor(String instruction, boolean arrive) {
        if (arrive) return "⚑";                 // flag
        if (instruction == null) return "↑";    // up
        String s = instruction.toLowerCase();
        if (s.contains("sharp left")) return "↖";   // upper-left
        if (s.contains("sharp right")) return "↗";  // upper-right
        if (s.contains("left")) return "←";         // left
        if (s.contains("right")) return "→";        // right
        if (s.contains("recalcul")) return "↻";     // reroute
        return "↑";                                  // straight / continue
    }

    /** Human-readable distance, e.g. "240 m" or "1.3 km". */
    static String formatMeters(double m) {
        if (m < 1000) return Math.round(m) + " m";
        return String.format(java.util.Locale.US, "%.1f km", m / 1000.0);
    }
}
