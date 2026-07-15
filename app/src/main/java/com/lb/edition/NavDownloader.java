// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resumable HTTP(S) file download with progress reporting and cancellation.
 *
 * <p>Used to fetch offline navigation data directly inside the app - Mapsforge {@code .map}
 * vector maps and BRouter {@code .rd5} routing segments - with no adb / manual steps. Pure
 * {@link HttpURLConnection}, no third-party HTTP library.
 *
 * <p>The file is streamed to {@code <dest>.part} first; on success it is renamed to {@code dest}.
 * A leftover {@code .part} is resumed with an HTTP {@code Range} request, so a cancelled or
 * interrupted download can be continued rather than restarted.
 */
final class NavDownloader {

    /** Progress callback: {@code done} and {@code total} bytes ({@code total} <= 0 if unknown). */
    interface Progress {
        void onProgress(long done, long total);
    }

    /** Thrown when the download is cancelled via the {@code cancel} flag. */
    static final class CancelledException extends IOException {
        CancelledException() { super("download cancelled"); }
    }

    /** Thrown for a non-success HTTP status (carries the code so callers can special-case e.g. 404). */
    static final class HttpException extends IOException {
        final int code;
        HttpException(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }
    }

    private NavDownloader() {}

    /**
     * Downloads {@code url} into {@code dest}, resuming a partial {@code <dest>.part} when possible.
     *
     * @param cancel optional flag; set {@code true} from another thread to abort (leaves the
     *               {@code .part} file in place so the download can resume later).
     */
    static void download(String url, File dest, Progress cb, AtomicBoolean cancel) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("cannot create dir: " + parent);
        }
        // Path-traversal guard: the ".part" companion must resolve inside the destination's own
        // directory (dest.getName() carries no separators, but validate for defence in depth).
        File baseDir = parent != null ? parent : dest.getAbsoluteFile().getParentFile();
        File part = PathGuard.childOf(baseDir, dest.getName() + ".part");
        long have = part.exists() ? part.length() : 0L;

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        // Neutral User-Agent - override Android's default (which leaks device model + OS version).
        c.setRequestProperty("User-Agent", "TR-LB-Edition");
        c.setRequestProperty("Accept-Encoding", "identity"); // keep Content-Length meaningful
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");

        try {
            c.connect();
            int code = c.getResponseCode();

            boolean append;
            long total;
            if (code == HttpURLConnection.HTTP_PARTIAL) {          // 206 - server honoured the resume
                append = true;
                long remaining = c.getContentLengthLong();
                total = remaining >= 0 ? have + remaining : -1;
            } else if (code == HttpURLConnection.HTTP_OK) {        // 200 - full download from scratch
                append = false;
                have = 0;
                total = c.getContentLengthLong();
            } else {
                throw new HttpException(code, url);
            }

            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(part, append);
            try {
                byte[] buf = new byte[1 << 16];
                long done = have;
                long lastCb = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancel != null && cancel.get()) throw new CancelledException();
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (cb != null && now - lastCb >= 200) {
                        lastCb = now;
                        cb.onProgress(done, total);
                    }
                }
                out.flush();
                if (cb != null) cb.onProgress(done, total);
            } finally {
                closeQuietly(out);
                closeQuietly(in);
            }
        } finally {
            c.disconnect();
        }

        // Atomically publish the completed file.
        if (dest.exists() && !dest.delete()) {
            throw new IOException("could not replace " + dest);
        }
        if (!part.renameTo(dest)) {
            throw new IOException("could not finalize " + dest);
        }
    }

    /** Human-readable byte count, e.g. "3.0 GB". */
    static String humanBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(java.util.Locale.US, "%.1f GB", gb);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) { }
        }
    }
}
