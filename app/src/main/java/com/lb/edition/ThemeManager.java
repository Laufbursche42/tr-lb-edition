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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
        getThemeFile(name, "json").delete();
        if (name.equals(getActiveThemeName())) prefs.edit().remove(PREF_ACTIVE).apply();
        prefs.edit().remove("theme_tiles_" + name).remove("theme_vars_" + name).apply();
    }

    /** File handle for a theme asset (does not imply existence). */
    File getThemeFile(String name, String ext) {
        return new File(themesDir(), name + "." + ext);
    }

    // ── ZIP bundle import/export ───────────────────────────────────────────

    /**
     * Import a theme from a ZIP bundle (theme.html + theme.css required; theme.json optional).
     * theme.json: {"name":"..."} sets the display name; fallback = zip filename without .zip.
     * Returns String[2]: [0] = imported name (null on error), [1] = error (null on success).
     */
    String[] importThemeZip(String zipFilename, InputStream zipStream) {
        String fallback = zipFilename != null ? zipFilename : "imported";
        if (fallback.toLowerCase(java.util.Locale.US).endsWith(".zip"))
            fallback = fallback.substring(0, fallback.length() - 4);
        if (fallback.isEmpty()) fallback = "imported";

        byte[] htmlBytes = null, cssBytes = null, jsonBytes = null;
        String manifestName = null;
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String leaf = leafName(entry.getName());
                    if (leaf.equalsIgnoreCase("theme.html")) {
                        htmlBytes = readZipEntry(zis);
                    } else if (leaf.equalsIgnoreCase("theme.css")) {
                        cssBytes = readZipEntry(zis);
                    } else if (leaf.equalsIgnoreCase("theme.json")) {
                        jsonBytes = readZipEntry(zis);
                        if (jsonBytes != null) {
                            try {
                                String nm = new JSONObject(new String(jsonBytes, StandardCharsets.UTF_8))
                                        .optString("name", "").trim();
                                if (!nm.isEmpty()) manifestName = nm;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                zis.closeEntry();
            }
        } catch (Throwable t) {
            return new String[]{null, "zip read failed: " + t.getMessage()};
        }
        if (htmlBytes == null) return new String[]{null, "theme.html missing in zip"};
        if (cssBytes == null) return new String[]{null, "theme.css missing in zip"};
        String name = sanitizeName(manifestName != null ? manifestName : fallback);
        String err = importTheme(name, htmlBytes, cssBytes);
        if (err != null) return new String[]{null, err};
        // Rebuild the JSON with the final (sanitized) name and save it alongside html/css
        try {
            org.json.JSONObject meta = new org.json.JSONObject();
            meta.put("name", name);
            // Re-parse original zip json to carry through cssVars, strings, scope, version.
            // version is ONLY set if the theme declares it; never fabricate a default.
            if (jsonBytes != null) {
                try {
                    org.json.JSONObject orig = new org.json.JSONObject(new String(jsonBytes, StandardCharsets.UTF_8));
                    if (orig.has("cssVars")) meta.put("cssVars", orig.get("cssVars"));
                    if (orig.has("strings")) meta.put("strings", orig.get("strings"));
                    if (orig.has("scope")) meta.put("scope", orig.get("scope"));
                    if (orig.has("version")) meta.put("version", orig.get("version"));
                } catch (Throwable ignored) {}
            }
            writeFile(getThemeFile(name, "json"), meta.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
        return new String[]{name, null};
    }

    /**
     * Package the named theme into a .zip in the app cache. Returns the File, or null on error.
     */
    File exportThemeZip(String name) {
        try {
            File htmlFile = getThemeFile(name, "html");
            File cssFile  = getThemeFile(name, "css");
            if (!htmlFile.exists() || !cssFile.exists()) return null;
            File outDir = new File(ctx.getCacheDir(), "theme_export");
            if (!outDir.exists()) outDir.mkdirs();
            File out = new File(outDir, name + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
                putZipEntry(zos, "theme.html", readFile(htmlFile));
                putZipEntry(zos, "theme.css",  readFile(cssFile));
                try {
                    File jsonFile = getThemeFile(name, "json");
                    if (jsonFile.exists()) {
                        putZipEntry(zos, "theme.json", readFile(jsonFile));
                    } else {
                        putZipEntry(zos, "theme.json", new JSONObject().put("name", name).toString().getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Throwable ignored) {}
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Per-theme customisation (tile config + CSS variable overrides) ────

    /** JSON object {order:[ids], hidden:[ids]} for the named theme's tile layout. */
    String getTileConfig(String name) {
        if (name == null || name.isEmpty()) return "{}";
        return prefs.getString("theme_tiles_" + name, "{}");
    }

    void saveTileConfig(String name, String json) {
        if (name == null || name.isEmpty()) return;
        prefs.edit().putString("theme_tiles_" + name, json == null ? "{}" : json).apply();
    }

    /** JSON object {varName: value} with user CSS-variable overrides for the named theme. */
    String getThemeVars(String name) {
        if (name == null || name.isEmpty()) return "{}";
        return prefs.getString("theme_vars_" + name, "{}");
    }

    void saveThemeVars(String name, String json) {
        if (name == null || name.isEmpty()) return;
        prefs.edit().putString("theme_vars_" + name, json == null ? "{}" : json).apply();
    }

    /** Raw theme.json content for the named theme, or "{}" if missing. */
    String getThemeMeta(String name) {
        try {
            if (name == null || name.isEmpty()) return "{}";
            File f = getThemeFile(name, "json");
            if (!f.exists()) return "{}";
            byte[] raw = readFile(f);
            return raw == null ? "{}" : new String(raw, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "{}";
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private File themesDir() {
        return new File(ctx.getFilesDir(), "themes");
    }

    private static String leafName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String sanitizeName(String name) {
        String s = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "imported" : s;
    }

    private static byte[] readZipEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_THEME_BYTES) throw new IOException("entry too large");
        }
        return out.toByteArray();
    }

    private static void putZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
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
     * Strip everything that could execute: script tags, iframe embeds, inline event handlers,
     * javascript:/data: URLs and external http/https sources.
     * Theme HTML is structure + CSS hooks only; no code runs.
     */
    static String sanitizeHtml(String html) {
        if (html == null) return "";
        // Strip <script ...>...</script> (including multi-line, case-insensitive)
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        // Strip <iframe> - can embed arbitrary documents in the same origin context
        html = html.replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "");
        html = html.replaceAll("(?is)<iframe[^>]*/?>", "");
        // Strip <base> - changes URL resolution for all relative links in the document
        html = html.replaceAll("(?is)<base[^>]*/?>", "");
        // Strip inline event handlers: on<word>=
        html = html.replaceAll("(?i)\\son\\w+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]*)", "");
        // Strip javascript: in href/src/action/formaction attributes
        html = html.replaceAll("(?i)(href|src|action|formaction)\\s*=\\s*[\"']?\\s*javascript:[^\"'>\\s]*[\"']?",
                "$1=\"\"");
        // Strip data: URIs from src/href (can carry executable HTML or JS)
        html = html.replaceAll("(?i)(src|href)\\s*=\\s*\"data:[^\"]*\"", "$1=\"\"");
        html = html.replaceAll("(?i)(src|href)\\s*=\\s*'data:[^']*'", "$1=''");
        // Strip external http/https URLs from src= and href= (only relative paths are allowed)
        html = html.replaceAll("(?i)(src|href)\\s*=\\s*\"https?://[^\"]*\"", "$1=\"\"");
        html = html.replaceAll("(?i)(src|href)\\s*=\\s*'https?://[^']*'", "$1=''");
        return html;
    }
}
