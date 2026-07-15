// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.Window;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Applies the app-wide immersive full-screen preference (SharedPreferences "lb" / key "fullscreen",
 *  default OFF) to any Activity, matching MainActivity so every screen looks identical. When off, the
 *  Android status bar (battery, clock, notifications) is shown AND the content is pushed below it.
 *
 *  The window is ALWAYS edge-to-edge (setDecorFitsSystemWindows(false)) on every screen and we are the
 *  single source of truth for the system-bar insets: we apply them as padding on the activity content
 *  root (android.R.id.content) ourselves. Never letting the framework fit/consume the insets keeps the
 *  behaviour pixel-identical across content types - the WebView dashboard AND the native Nav/Download
 *  screens - instead of the framework double- or zero-ing the inset differently per view type.
 *
 *  The insets listener reads the CURRENT fullscreen pref on every pass, so toggling the preference and
 *  calling this method again re-applies the correct padding immediately. */
final class UiChrome {
    private UiChrome() {}

    // Marker so the OnApplyWindowInsetsListener is only installed once per content view.
    private static final String INSET_TAG = "lb_inset_listener";

    /** The app's dark title-bar colour - matches telemetry.html {@code --card} (the WebView topbar
     *  background) AND {@link NavUi#BAR_BG} (the native header). The system status/navigation bars are
     *  tinted to this so the status-bar strip visually continues into the app title bar with no seam. */
    static final int BAR_COLOR = 0xFF111420;

    static void applyFullscreen(final Activity a) {
        try {
            boolean fs = a.getSharedPreferences("lb", Context.MODE_PRIVATE).getBoolean("fullscreen", false);
            Window w = a.getWindow();
            View decor = w.getDecorView();
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, decor);
            // Always edge-to-edge: WE manage the insets (padding below), never the framework, so the
            // status-bar behaviour is identical on the WebView and the native activities.
            WindowCompat.setDecorFitsSystemWindows(w, false);

            // Tint the system bars to the app's dark title-bar colour with LIGHT icons, so the
            // status-bar strip looks like a seamless continuation of the title bar (not a separate
            // bar). setStatusBar/NavigationBarColor covers older APIs; on Android 15+ (targetSdk 35+)
            // those are ignored under enforced edge-to-edge, so the content-view background below is
            // what actually tints the strip (a view's background is drawn across its padding region).
            try {
                w.setStatusBarColor(BAR_COLOR);
                w.setNavigationBarColor(BAR_COLOR);
            } catch (Throwable ignored) {}
            c.setAppearanceLightStatusBars(false);      // dark bar => light (white) icons
            c.setAppearanceLightNavigationBars(false);

            if (fs) {
                c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsetsCompat.Type.systemBars());
            } else {
                c.show(WindowInsetsCompat.Type.systemBars());
            }

            // Manage padding on the content view so the app sits below the status bar when fullscreen
            // is off (and edge-to-edge when it is on). This listener is the SINGLE source of truth for
            // the top/bottom inset - it CONSUMES the insets so no child (the WebView, whose CSS would
            // otherwise re-add env(safe-area-inset-*)) can double them into a "huge bar". Install once;
            // it reads the pref live so a later toggle is handled by requestApplyInsets() below.
            final View content = a.findViewById(android.R.id.content);
            if (content != null) {
                // The status/nav-bar strips are the content view's OWN top/bottom padding, so its
                // background paints them the dark title-bar colour on every API level.
                content.setBackgroundColor(BAR_COLOR);
                if (!INSET_TAG.equals(content.getTag())) {
                    content.setTag(INSET_TAG);
                    ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                        try {
                            boolean fsNow = a.getSharedPreferences("lb", Context.MODE_PRIVATE)
                                    .getBoolean("fullscreen", false);
                            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                            int top = fsNow ? 0 : bars.top;
                            int bottom = fsNow ? 0 : bars.bottom;
                            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
                        } catch (Throwable ignored) {}
                        // Consume: children see zero insets, so nothing else can add top/bottom inset.
                        return WindowInsetsCompat.CONSUMED;
                    });
                }
                ViewCompat.requestApplyInsets(content);
            }
        } catch (Throwable ignored) {}
    }
}
