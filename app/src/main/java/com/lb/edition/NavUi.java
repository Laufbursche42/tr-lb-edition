// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared UI building blocks for the navigation screens so the top app bar is IDENTICAL and the
 * SAME fixed height everywhere ({@link NavActivity} and {@link MapDownloadActivity}). Matches the
 * dashboard look: dark bar, back button, centred bold title.
 */
final class NavUi {

    // Palette mirrors the dashboard design tokens (telemetry.html --card / --border / --text …).
    static final int BG      = 0xFF0E1116;
    static final int BAR_BG  = 0xFF111420;
    static final int BORDER  = 0xFF1C2035;
    static final int TEXT    = 0xFFE4EDF6;
    static final int MUTED   = 0xFF9AA0A6;
    static final int ACCENT  = 0xFFC6F135;

    // Light-mode counterparts (used when the app theme is light). The map tiles are NOT affected.
    static final int BAR_BG_L = 0xFFFFFFFF;
    static final int BORDER_L = 0xFFCDD5E4;
    static final int TEXT_L   = 0xFF0A0D18;
    static final int ACCENT_L = 0xFF2FEBA2;

    /** @return true if the app theme is dark (SharedPreferences "lb"/"theme_dark", default true). */
    static boolean isDark(Context c) {
        try {
            return c.getSharedPreferences("lb", Context.MODE_PRIVATE).getBoolean("theme_dark", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Fixed app-bar height in dp - the SAME on every navigation screen. */
    static final int HEADER_H_DP = 56;

    /**
     * Left/right zone width (dp). The back arrow and the balancing right-hand zone share this width
     * so the centred title lands in the exact middle of the bar - identical to the dashboard's
     * sub-pages, whose {@code .tb-title} sits between a 40px back button and a 40px right spacer.
     */
    static final int SIDE_W_DP = 48;

    private NavUi() {}

    /**
     * Builds the fixed-height top app bar so it matches the dashboard sub-page headers
     * (telemetry.html {@code #topbar} / {@code .tb-title}): a dark bar, a left back arrow that
     * finishes the activity, and a CENTRED bold title at the dashboard's weight/size/colour
     * (16sp / bold / {@code --text}). When {@code onMaps} is non-null a right-hand "Maps" text
     * button is kept; otherwise a same-width spacer keeps the title perfectly centred.
     */
    static LinearLayout header(final Activity a, String title, final Runnable onMaps) {
        boolean dark = isDark(a);
        int cBar = dark ? BAR_BG : BAR_BG_L;
        int cText = dark ? TEXT : TEXT_L;
        int cAccent = dark ? ACCENT : ACCENT_L;

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(cBar);
        int h = dp(a, HEADER_H_DP);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        int side = dp(a, SIDE_W_DP);

        // Left: back arrow - matches the dashboard's .icon-btn back button ("←", 20px).
        TextView back = new TextView(a);
        back.setText("←"); // ←
        back.setTextColor(cText);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        back.setGravity(Gravity.CENTER);
        back.setLayoutParams(new LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.MATCH_PARENT));
        back.setClickable(true);
        back.setOnClickListener(v -> a.finish());
        bar.addView(back);

        // Centre: bold title - matches .tb-title (16sp / 700 / --text / .02em / centred).
        TextView tv = new TextView(a);
        tv.setText(title);
        tv.setTextColor(cText);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.02f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        tv.setLayoutParams(tlp);
        tv.setPadding(dp(a, 4), 0, dp(a, 4), 0);
        bar.addView(tv);

        // Right: the "Maps" action (kept), or a same-width spacer so the title stays centred.
        if (onMaps != null) {
            TextView maps = new TextView(a);
            maps.setText("Maps");
            maps.setTextColor(cAccent);
            maps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            maps.setTypeface(Typeface.DEFAULT_BOLD);
            maps.setGravity(Gravity.CENTER);
            maps.setMinWidth(side);
            maps.setPadding(dp(a, 12), 0, dp(a, 12), 0);
            maps.setClickable(true);
            maps.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            maps.setOnClickListener(v -> onMaps.run());
            bar.addView(maps);
        } else {
            View spacer = new View(a);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(
                    side, ViewGroup.LayoutParams.MATCH_PARENT));
            bar.addView(spacer);
        }
        return bar;
    }

    /** 1px bottom divider drawn under the app bar. */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(isDark(c) ? BORDER : BORDER_L);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
