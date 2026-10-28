// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Stores and serves user-imported dashboard themes (HTML + CSS file pairs). */
class ThemeManager {

    private static final String PREF_ACTIVE = "theme_active";
    private static final int MAX_THEME_BYTES = 2 * 1024 * 1024;   // 2 MB per file

    private final Context ctx;
    private final SharedPreferences prefs;

    ThemeManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences("lb", Context.MODE_PRIVATE);
    }

    // ── Query ──────────────────────────────────────────────────────────────

    /** JSON array [{name, active}] with "Default" first, then alphabetical imported themes. */
    String listThemes() {
        try {
            JSONArray arr = new JSONArray();
            String active = getActiveThemeName();
            JSONObject def = new JSONObject();
            def.put("name", "Default");
            def.put("active", active.isEmpty());
            arr.put(def);
            File dir = themesDir();
            if (dir.exists()) {
                List<String> names = new ArrayList<>();
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String n = f.getName();
                        if (n.endsWith(".html")) names.add(n.substring(0, n.length() - 5));
                    }
                }
                java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                for (String name : names) {
                    JSONObject o = new JSONObject();
                    o.put("name", name);
                    o.put("active", name.equals(active));
                    arr.put(o);
                }
            }
            return arr.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    /** Active theme name from prefs; empty string = Default (built-in). */
    String getActiveThemeName() {
        return prefs.getString(PREF_ACTIVE, "");
    }

    /** Sanitized HTML of the named theme, or null if it does not exist. */
    String getThemeHtml(String name) {
        try {
            if (name == null || name.isEmpty()) return null;
            File f = getThemeFile(name, "html");
            if (!f.exists()) return null;
            byte[] raw = readFile(f);
            if (raw == null) return null;
            return sanitizeHtml(new String(raw, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Raw CSS bytes of the named theme, or null if it does not exist. */
    byte[] readThemeCss(String name) {
        try {
            if (name == null || name.isEmpty()) return null;
            File f = getThemeFile(name, "css");
            if (!f.exists()) return null;
            return readFile(f);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /**
     * Store an imported theme. Returns null on success, an error string on failure.
     * Sanitizes the HTML before writing.
     */
    String importTheme(String name, byte[] htmlBytes, byte[] cssBytes) {
        try {
            if (name == null || name.isEmpty()) return "name required";
            if (htmlBytes == null || htmlBytes.length == 0) return "html empty";
            if (cssBytes == null || cssBytes.length == 0) return "css empty";
            if (htmlBytes.length > MAX_THEME_BYTES || cssBytes.length > MAX_THEME_BYTES)
                return "file too large";
            File dir = themesDir();
            if (!dir.exists() && !dir.mkdirs()) return "storage error";
            String html = sanitizeHtml(new String(htmlBytes, StandardCharsets.UTF_8));
            writeFile(getThemeFile(name, "html"), html.getBytes(StandardCharsets.UTF_8));
            writeFile(getThemeFile(name, "css"), cssBytes);
            return null;
        } catch (Throwable t) {
            return "import failed: " + t.getMessage();
        }
    }

    void activateTheme(String name) {
        if (name == null || name.isEmpty()) {
            prefs.edit().remove(PREF_ACTIVE).apply();
        } else {
            prefs.edit().putString(PREF_ACTIVE, name).apply();
        }
    }

    /** Delete theme files. No-op if the theme does not exist. */
    void deleteTheme(String name) {
        if (name == null || name.isEmpty()) return;
        getThemeFile(name, "html").delete();
        getThemeFile(name, "css").delete();
        if (name.equals(getActiveThemeName())) prefs.edit().remove(PREF_ACTIVE).apply();
    }

    /** File handle for a theme asset (does not imply existence). */
    File getThemeFile(String name, String ext) {
        return new File(themesDir(), name + "." + ext);
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private File themesDir() {
        return new File(ctx.getFilesDir(), "themes");
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), MAX_THEME_BYTES))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_THEME_BYTES) throw new IOException("file too large");
            }
            return out.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /**
     * Strip everything that could execute: script tags, inline event handlers,
     * javascript: URLs and external http/https sources.
     * Theme HTML is structure + CSS hooks only; no code runs.
     */
    static String sanitizeHtml(String html) {
        if (html == null) return "";
        // Strip <script ...>...</script> (including multi-line, case-insensitive)
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        // Strip inline event handlers: on<word>=
        html = html.replaceAll("(?i)\\son\\w+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]*)", "");
        // Strip javascript: in href/src/action/formaction attributes
        html = html.replaceAll("(?i)(href|src|action|formaction)\\s*=\\s*[\"']?\\s*javascript:[^\"'>\\s]*[\"']?",
                "$1=\"\"");
        // Strip external http/https URLs from src= and href= (only file: or relative are allowed)
        html = html.replaceAll("(?i)(src|href)\\s*=\\s*\"https?://[^\"]*\"", "$1=\"\"");
        html = html.replaceAll("(?i)(src|href)\\s*=\\s*'https?://[^']*'", "$1=''");
        return html;
    }
}
