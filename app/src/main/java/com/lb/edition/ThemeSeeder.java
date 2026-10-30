// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.content.Context;

import java.io.InputStream;

/**
 * Seeds bundled themes into the user's theme library on first install.
 * Each theme is seeded only if it is not already present - user-imported or user-edited
 * themes are never overwritten. Default stays the active theme unless the user switches.
 */
class ThemeSeeder {

    private static final String[] BUNDLED = { "Carbon", "Lambo" };

    static void seed(Context ctx, ThemeManager tm) {
        for (String name : BUNDLED) {
            seedIfAbsent(ctx, tm, name);
        }
    }

    private static void seedIfAbsent(Context ctx, ThemeManager tm, String name) {
        if (tm.getThemeFile(name, "html").exists()) return;
        String assetPath = "themes/" + name + ".zip";
        try (InputStream is = ctx.getAssets().open(assetPath)) {
            tm.importThemeZip(name + ".zip", is);
        } catch (Throwable ignored) {}
    }
}
