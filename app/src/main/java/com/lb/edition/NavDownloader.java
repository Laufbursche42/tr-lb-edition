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
    /** Absolute sanity ceiling for one download (the largest EU country .map files are a few GB). */
    private static final long MAX_DOWNLOAD_BYTES = 12L * 1024 * 1024 * 1024;   // 12 GB

    static void download(String url, File dest, Progress cb, AtomicBoolean cancel) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("cannot create dir: " + parent);
        }
        // Path-traversal guard: the ".part" companion must resolve inside the destination's own
        // directory (dest.getName() carries no separators, but validate for defence in depth).
        File baseDir = parent != null ? parent : dest.getAbsoluteFile().getParentFile();
        File part = PathGuard.childOf(baseDir, dest.getName() + ".part");
        File meta = PathGuard.childOf(baseDir, dest.getName() + ".part.meta");   // stored ETag/Last-Modified
        long have = part.exists() ? part.length() : 0L;
        String validator = have > 0 ? readLine(meta) : null;
        // Never resume without a validator to pin the remote file version: if the file changed on the
        // mirror (maps are regenerated regularly), appending new bytes at the old offset would splice
        // two different files into a corrupt result. Restart cleanly instead.
        if (have > 0 && validator == null) { part.delete(); meta.delete(); have = 0; }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        // Neutral User-Agent - override Android's default (which leaks device model + OS version).
        c.setRequestProperty("User-Agent", "TR-LB-Edition");
        c.setRequestProperty("Accept-Encoding", "identity"); // keep Content-Length meaningful
        if (have > 0) {
            c.setRequestProperty("Range", "bytes=" + have + "-");
            c.setRequestProperty("If-Range", validator);   // server returns 200 (full) if it changed
        }

        try {
            c.connect();
            int code = c.getResponseCode();

            boolean append;
            long total;
            if (code == HttpURLConnection.HTTP_PARTIAL) {          // 206 - validator matched, resume
                append = true;
                long remaining = c.getContentLengthLong();
                total = remaining >= 0 ? have + remaining : -1;
            } else if (code == HttpURLConnection.HTTP_OK) {        // 200 - full download from scratch
                append = false;
                have = 0;
                total = c.getContentLengthLong();
                writeLine(meta, validatorOf(c));                  // remember the version for a later resume
            } else if (code == 416) {                             // Range Not Satisfiable - stale .part
                if (part.exists() && !part.delete()) throw new IOException("could not clear stale part: " + part);
                meta.delete();
                c.disconnect();
                download(url, dest, cb, cancel);                  // restart once, cleanly (have is now 0)
                return;
            } else {
                throw new HttpException(code, url);
            }

            // Free-space guard: refuse before writing if the remaining bytes (plus a margin) will not fit.
            if (total > 0) ensureFreeSpace(baseDir, (total - have) + (16L << 20));

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
                    // Stop a misbehaving/compromised host from streaming unbounded data: cap at the
                    // declared size (plus 1 MB slack) and at an absolute ceiling.
                    if (done > MAX_DOWNLOAD_BYTES || (total > 0 && done > total + (1L << 20))) {
                        throw new IOException("download exceeded the expected size - aborting");
                    }
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
        meta.delete();   // validator no longer needed once the file is complete
    }

    /** Prefer the strong ETag, else Last-Modified; may be null (then a later resume restarts clean). */
    private static String validatorOf(HttpURLConnection c) {
        String etag = c.getHeaderField("ETag");
        if (etag != null && !etag.isEmpty()) return etag;
        return c.getHeaderField("Last-Modified");
    }

    private static String readLine(File f) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeLine(File f, String s) {
        if (s == null || s.isEmpty()) { f.delete(); return; }
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    /** Best-effort free-space check; never blocks the download on its own failure to measure. */
    private static void ensureFreeSpace(File dir, long needed) throws IOException {
        long free;
        try {
            free = new android.os.StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Throwable ignored) {
            return;   // could not measure - do not block
        }
        if (free < needed) {
            throw new IOException("not enough free space: need " + humanBytes(needed) + ", have " + humanBytes(free));
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
