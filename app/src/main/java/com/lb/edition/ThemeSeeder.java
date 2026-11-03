// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.InputStream;

/**
 * Seeds bundled themes into the user's theme library. A theme is seeded if absent, and the whole
 * bundled set is re-seeded (overwriting the built-in Carbon/Lambo) when BUNDLE_VERSION rises, so an
 * app update ships the updated themes to existing installs too. User-imported themes (any other
 * name) are never touched. Default stays active unless the user switches.
 */
class ThemeSeeder {

    private static final String[] BUNDLED = { "Carbon", "Lambo" };
    // Bump whenever the bundled theme .zips change so existing installs re-seed the new version.
    private static final int BUNDLE_VERSION = 7;
    private static final String PREF = "lb_theme_bundle";
    private static final String KEY = "seededVersion";

    static void seed(Context ctx, ThemeManager tm) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean force = sp.getInt(KEY, 0) < BUNDLE_VERSION;
        for (String name : BUNDLED) {
            seedOne(ctx, tm, name, force);
        }
        if (force) sp.edit().putInt(KEY, BUNDLE_VERSION).apply();
    }

    private static void seedOne(Context ctx, ThemeManager tm, String name, boolean force) {
        if (!force && tm.getThemeFile(name, "html").exists()) return;
        try (InputStream is = ctx.getAssets().open("themes/" + name + ".zip")) {
            tm.importThemeZip(name + ".zip", is);   // overwrites an existing theme of the same name
        } catch (Throwable ignored) {}
    }
}
