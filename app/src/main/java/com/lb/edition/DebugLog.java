// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * In-app debug logger for the standalone TR-LB Edition app.
 *
 * <p>When enabled it records this process's own native logs (every {@code Log.*} call, including the
 * {@code lbble}/{@code lbedition} BLE tags) by running {@code logcat --pid=<self>} on a background
 * thread and appending each line to a rotating file at
 * {@code getExternalFilesDir("logs")/tr-lb-debug.log}. JS-forwarded lines (from {@code LB.log(...)})
 * are appended via {@link #append(String)} with a timestamp and a {@code JS:} prefix.
 *
 * <p>The enabled state is persisted in {@link android.content.SharedPreferences} under the key
 * {@code lb_debug}, so debug mode survives app restarts. The file is capped at ~2 MB; on reaching the
 * cap it is rotated in place, keeping the most recent tail.
 *
 * <p>Every public method is null/exception-safe and never throws to the caller.
 */
public final class DebugLog {

    private static final String TAG = "lbdebug";
    private static final String PREFS = "lb_prefs";
    private static final String KEY_DEBUG = "lb_debug";
    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "tr-lb-debug.log";
    private static final long MAX_BYTES = 2L * 1024 * 1024; // ~2 MB cap

    private final Context ctx;
    private final Object lock = new Object();
    private final SimpleDateFormat tsFmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private volatile boolean running = false;
    private Thread thread;
    private volatile Process proc;
    private Writer writer;      // guarded by lock
    private long bytesWritten;  // guarded by lock
    // Connection-specific identifiers (device address, custom BLE name) to scrub
    // literally from every line, so a user's MAC or scooter name never reaches an
    // exported log even when it is not a pattern the regex scrub recognises.
    private final java.util.LinkedHashSet<String> redactLiterals = new java.util.LinkedHashSet<>(); // guarded by lock

    public DebugLog(Context context) {
        this.ctx = context != null ? context.getApplicationContext() : null;
    }

    /** @return the persisted debug-mode flag (default false). */
    public boolean isEnabled() {
        try {
            if (ctx == null) return false;
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEBUG, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Persist the flag and start/stop capture accordingly. */
    public void setEnabled(boolean on) {
        try {
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_DEBUG, on).apply();
            }
        } catch (Throwable t) {
            Log.e(TAG, "setEnabled persist failed", t);
        }
        try {
            if (on) start();
            else stop();
        } catch (Throwable t) {
            Log.e(TAG, "setEnabled start/stop failed", t);
        }
    }

    /** @return the log file (creating the dir) or null if unavailable. */
    public File getLogFile() {
        try {
            if (ctx == null) return null;
            File dir = ctx.getExternalFilesDir(LOG_DIR);
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, LOG_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Start capturing (idempotent). Safe to call on app start when already enabled. */
    public synchronized void start() {
        if (running) return;
        File f = getLogFile();
        if (f == null) {
            Log.e(TAG, "start: no log file");
            return;
        }
        try {
            synchronized (lock) {
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
                bytesWritten = f.length();
            }
        } catch (Throwable t) {
            Log.e(TAG, "start: open writer failed", t);
            return;
        }
        running = true;
        writeLine("==== debug logging started " + tsFmt.format(new Date())
                + " pid=" + android.os.Process.myPid() + " ====");
        thread = new Thread(this::captureLoop, "lb-debug-logcat");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "debug logging started");
    }

    /** Stop capturing and flush/close the file (idempotent). */
    public synchronized void stop() {
        if (!running && writer == null) return;
        running = false;
        Process p;
        synchronized (lock) {
            p = proc;
            proc = null;
        }
        try {
            if (p != null) p.destroy();
        } catch (Throwable ignored) {
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {
            }
        }
        synchronized (lock) {
            if (writer != null) {
                try {
                    writer.write("==== debug logging stopped " + tsFmt.format(new Date()) + " ====\n");
                    writer.flush();
                    writer.close();
                } catch (Throwable ignored) {
                }
                writer = null;
            }
        }
        Log.i(TAG, "debug logging stopped");
    }

    /** Append a JS-forwarded line (timestamped, prefixed with "JS:"). No-op unless capturing. */
    public void append(String s) {
        if (s == null || !running) return;
        writeLine(tsFmt.format(new Date()) + " JS: " + s);
    }

    // ── internals ──

    private void captureLoop() {
        Process p = null;
        BufferedReader r = null;
        try {
            // Filter to this process only, "time" verbose format (date + time + level + tag).
            p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/logcat", "-v", "time", "--pid=" + android.os.Process.myPid()});
            synchronized (lock) {
                proc = p;
            }
            r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = r.readLine()) != null) {
                writeLine(line);
            }
        } catch (Throwable t) {
            Log.e(TAG, "logcat capture ended", t);
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── Always-on redaction ──────────────────────────────────────────────────
    // Keep the logs useful for troubleshooting, but never let personal data reach
    // an exported/shared log: scrub MACs, device/secret ids, tokens and JWTs at the
    // source (every written line), mirroring the websites' maskSecrets + redact
    // pipeline. Space-separated frame bytes ("55 71 ..") are 2-char tokens and
    // survive, so the actual scooter traffic is still readable. This is always on
    // (the in-app "redact" control is shown checked and locked, to match the sites).
    private static final Pattern RE_JWT       = Pattern.compile("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}");
    private static final Pattern RE_BEARER_KV = Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_TOKEN_KV  = Pattern.compile("\\b(access[_-]?token|refresh[_-]?token|token|jwt|password|passwd|pwd|secret|code|otp)\\b(\\s*[:=]\\s*)(\"?)([^\\s\",}]+)\\3", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_MAC       = Pattern.compile("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b");
    private static final Pattern RE_SECRET_KV = Pattern.compile("\\b(secret|token|key|aes|pwd|password|pin|mac|serial|vin|uid|imei)\\b(\\s*[:=]\\s*)(\"?)([^\\s\",]+)\\3", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HEX       = Pattern.compile("\\b[0-9A-Fa-f]{16,}\\b");

    /** Always-on PII/secret scrub applied to every written line (see comment above). */
    static String redactLine(String line) {
        if (line == null) return null;
        String s = line;
        s = RE_JWT.matcher(s).replaceAll("[redacted-jwt]");
        s = RE_BEARER_KV.matcher(s).replaceAll("Bearer ***");
        s = RE_TOKEN_KV.matcher(s).replaceAll("$1$2***");
        s = RE_MAC.matcher(s).replaceAll("[redacted-mac]");
        s = RE_SECRET_KV.matcher(s).replaceAll("$1$2[redacted]");
        s = RE_HEX.matcher(s).replaceAll("[redacted-hex]");
        return s;
    }

    /** Register a connection identifier (device address or custom BLE name) to scrub
     *  literally from every logged line. Short or blank values are ignored. Thread-safe. */
    public void addRedactLiteral(String v) {
        if (v == null) return;
        String t = v.trim();
        if (t.length() < 3) return;
        synchronized (lock) {
            if (redactLiterals.contains(t)) return;
            if (redactLiterals.size() >= 32) {               // cap: drop the oldest
                java.util.Iterator<String> it = redactLiterals.iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            redactLiterals.add(t);
        }
    }

    /** Forget all registered identifiers (e.g. on disconnect). */
    public void clearRedactLiterals() {
        synchronized (lock) { redactLiterals.clear(); }
    }

    private void writeLine(String line) {
        if (line == null) return;
        synchronized (lock) {
            if (writer == null) return;
            try {
                String out = redactLine(line);
                if (!redactLiterals.isEmpty()) {
                    for (String lit : redactLiterals) {
                        if (out.contains(lit)) out = out.replace(lit, "[redacted-id]");
                    }
                }
                writer.write(out);
                writer.write('\n');
                writer.flush();
                bytesWritten += out.length() + 1;
                if (bytesWritten > MAX_BYTES) rotate();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Rotate in place, keeping the most recent ~half. Caller holds {@code lock}. */
    private void rotate() {
        try {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (Throwable ignored) {
                }
                writer = null;
            }
            File f = getLogFile();
            if (f == null) return;
            long len = f.length();
            long keep = MAX_BYTES / 2;
            if (len > keep) {
                byte[] tail = new byte[(int) keep];
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                try {
                    raf.seek(len - keep);
                    raf.readFully(tail);
                } finally {
                    raf.close();
                }
                // Drop the leading partial line so the file starts on a boundary.
                int start = 0;
                for (int i = 0; i < tail.length; i++) {
                    if (tail[i] == '\n') {
                        start = i + 1;
                        break;
                    }
                }
                FileOutputStream fos = new FileOutputStream(f, false);
                try {
                    fos.write(("==== log rotated (kept tail) " + tsFmt.format(new Date()) + " ====\n")
                            .getBytes("UTF-8"));
                    fos.write(tail, start, tail.length - start);
                } finally {
                    fos.close();
                }
            }
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
            bytesWritten = f.length();
        } catch (Throwable t) {
            Log.e(TAG, "rotate failed", t);
            // Best effort: try to reopen so capture can continue.
            try {
                File f = getLogFile();
                if (f != null) {
                    writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
                    bytesWritten = f.length();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
