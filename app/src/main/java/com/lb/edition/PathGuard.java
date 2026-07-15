// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import java.io.File;
import java.io.IOException;

/**
 * Path-traversal guard for file operations under an app-owned base directory.
 *
 * <p>Every file name the app handles already comes from a trusted source - the built-in country
 * catalog in {@link MapDownloadActivity}, BRouter tile names computed from coordinates in
 * {@link BikeRouter}, or a previously-stored selection - so traversal is not reachable in practice.
 * These helpers add defence in depth: the resolved <em>canonical</em> path is verified to stay
 * inside the intended base directory before the file is used, so even a crafted name (".." segments
 * or an absolute path) can never escape it.
 */
final class PathGuard {

    private PathGuard() {}

    /**
     * Resolves {@code name} against {@code baseDir} and returns the file only if its canonical path
     * stays inside {@code baseDir}.
     *
     * @throws IOException if {@code name} escapes {@code baseDir}, or the path cannot be canonicalized.
     */
    static File childOf(File baseDir, String name) throws IOException {
        return ensureInside(baseDir, new File(baseDir, name));
    }

    /**
     * Verifies {@code target} resolves inside {@code baseDir} and returns it. Use when the target
     * {@link File} is built elsewhere (e.g. an absolute path from an intent extra) rather than from
     * a name under the base.
     *
     * @throws IOException if {@code target} escapes {@code baseDir}, or the path cannot be canonicalized.
     */
    static File ensureInside(File baseDir, File target) throws IOException {
        String base = baseDir.getCanonicalPath();
        String path = target.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator)) {
            throw new IOException("path escapes base directory: " + target);
        }
        return target;
    }
}
