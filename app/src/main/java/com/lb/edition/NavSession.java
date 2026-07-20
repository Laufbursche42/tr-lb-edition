// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide holder for the ACTIVE navigation session so it survives switching between the
 * dashboard ({@link MainActivity} / WebView) and the map ({@link NavActivity}).
 *
 * <p>The foreground {@link NavigationService} owns the runtime (GPS, guidance, voice) and publishes
 * a {@link State} snapshot here on every fix; {@link NavActivity} and {@link MainActivity} observe
 * it to draw the map card and the dashboard banner respectively. Exactly one session is active at a
 * time. The flattened route is stored here too so re-opening NavActivity can redraw the line without
 * re-routing.
 */
final class NavSession {

    private NavSession() {}

    /** A guidance snapshot, published by the service on each location fix. */
    static final class State {
        final boolean active;
        final boolean arrived;
        final boolean offRoute;
        final String turnText;      // e.g. "Turn left onto Main St"
        final String turnArrow;     // glyph: ← → ↑ ↻ ⚑ …
        final double distToTurnM;
        final double remainingM;
        final double meLat, meLon;  // current position (NaN when unknown)
        final boolean hasFix;

        State(boolean active, boolean arrived, boolean offRoute, String turnText, String turnArrow,
              double distToTurnM, double remainingM, double meLat, double meLon, boolean hasFix) {
            this.active = active;
            this.arrived = arrived;
            this.offRoute = offRoute;
            this.turnText = turnText != null ? turnText : "";
            this.turnArrow = turnArrow != null ? turnArrow : "";
            this.distToTurnM = distToTurnM;
            this.remainingM = remainingM;
            this.meLat = meLat;
            this.meLon = meLon;
            this.hasFix = hasFix;
        }

        static State idle() {
            return new State(false, false, false, "", "", 0, 0, Double.NaN, Double.NaN, false);
        }
    }

    interface Listener {
        void onNavState(State s);
    }

    // ── Active route (set by NavActivity on Start / after a reroute; read to redraw on reopen) ──
    static volatile double[] lats;
    static volatile double[] lons;
    static volatile double[] cumDist;
    static volatile int[] maneuverIdx;
    static volatile String[] maneuverText;
    static volatile double destLat = Double.NaN;
    static volatile double destLon = Double.NaN;
    static volatile String profile = "trekking";

    private static volatile boolean active = false;
    private static volatile State state = State.idle();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    static boolean isActive() {
        return active;
    }

    static State state() {
        return state;
    }

    /** Replace the active route (called on Start and on every successful reroute). */
    static void setRoute(double[] la, double[] lo, double[] cum, int[] mIdx, String[] mText,
                         double dLat, double dLon, String prof) {
        lats = la;
        lons = lo;
        cumDist = cum;
        maneuverIdx = mIdx;
        maneuverText = mText;
        destLat = dLat;
        destLon = dLon;
        if (prof != null) profile = prof;
    }

    /** Mark the session active (the service is now running). */
    static void begin() {
        active = true;
    }

    /** Publish a new guidance snapshot to all observers. */
    static void publish(State s) {
        if (s == null) return;
        state = s;
        active = s.active;
        for (Listener l : listeners) {
            try {
                l.onNavState(s);
            } catch (Throwable ignored) {
            }
        }
    }

    /** End the session and notify observers with an idle state. */
    static void end() {
        active = false;
        lats = null;
        lons = null;
        cumDist = null;
        maneuverIdx = null;
        maneuverText = null;
        destLat = Double.NaN;
        destLon = Double.NaN;
        State s = State.idle();
        state = s;
        for (Listener l : listeners) {
            try {
                l.onNavState(s);
            } catch (Throwable ignored) {
            }
        }
    }

    static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }
}
