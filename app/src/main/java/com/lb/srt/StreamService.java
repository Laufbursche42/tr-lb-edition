// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.srt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import com.pedro.common.ConnectChecker;
import com.pedro.common.VideoCodec;
import com.pedro.srt.srt.SrtClient;

import java.nio.ByteBuffer;

/**
 * Foreground service: MediaProjection captures the screen into a GL texture, ScreenGlEncoder
 * re-draws it at a CONSTANT 30 fps and H.264-encodes it, and the frames are pushed straight
 * into RootEncoder's SrtClient. The constant redraw is what keeps the stream alive at a real
 * bitrate even when the screen is a still image.
 */
public class StreamService extends Service implements ConnectChecker, ScreenGlEncoder.Callback {

    private static final String TAG = "lbsrt";
    private static final String CH = "lb_srt";
    private static final int NOTIF_ID = 101;

    private static final int FPS = 30;
    // CBR target. High enough that even a static screen stays above the relay's ~1.5 Mbps floor.
    private static final int BITRATE = 4_000_000;

    private static volatile String lastStatus = "idle";
    public static String getStatus() { return lastStatus; }
    public static void setStatus(String s) { lastStatus = s; }

    private volatile SrtClient srtClient;
    private ScreenGlEncoder glEncoder;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;

    private volatile boolean running = false;
    private volatile boolean videoInfoSent = false;
    private volatile boolean userStopped = false;
    private String currentUrl;
    private byte[] spsData, ppsData;   // emitted once by the encoder; kept for reconnect

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();

        if (intent != null && "STOP".equals(intent.getAction())) {
            userStopped = true;
            stopEverything();
            setStatus("stopped");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.hasExtra("url")) {
            if (running) stopEverything();   // fresh start tears down any old session
            userStopped = false;
            currentUrl = intent.getStringExtra("url");
            int code = intent.getIntExtra("code", -1);
            Intent data = intent.getParcelableExtra("data");
            Log.i(TAG, "onStartCommand url=" + currentUrl + " code=" + code + " data=" + (data != null));
            try {
                startStreaming(code, data);
            } catch (Throwable t) {
                Log.e(TAG, "startStreaming error", t);
                setStatus("error: " + t.getMessage());
                stopEverything();
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void startStreaming(int code, Intent data) throws Exception {
        int w = 1080, h = 2400, dpi = 440;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                w = dm.widthPixels; h = dm.heightPixels; dpi = dm.densityDpi;
            }
        } catch (Throwable t) { Log.e(TAG, "metrics", t); }
        // H.264 encoder caps a frame at 2560x1440 -> scale the portrait screen to fit, keep
        // aspect, align to 16.
        final int MAX_LONG = 1440, MAX_SHORT = 2560;
        int lo = Math.min(w, h), hi = Math.max(w, h);
        if (hi > MAX_LONG) { lo = (int) ((long) lo * MAX_LONG / hi); hi = MAX_LONG; }
        if (lo > MAX_SHORT) { hi = (int) ((long) hi * MAX_SHORT / lo); lo = MAX_SHORT; }
        if (h >= w) { w = lo; h = hi; } else { w = hi; h = lo; }
        w = Math.max(16, (w / 16) * 16);
        h = Math.max(16, (h / 16) * 16);

        srtClient = new SrtClient(this);
        srtClient.setVideoCodec(VideoCodec.H264);
        srtClient.setOnlyVideo(true);
        setStatus("connecting");
        srtClient.connect(currentUrl);
        Log.i(TAG, "srt connect: " + currentUrl);

        glEncoder = new ScreenGlEncoder(w, h, FPS, BITRATE, this);
        glEncoder.prepare();
        glEncoder.start();
        Surface projSurface = glEncoder.getInputSurface();
        Log.i(TAG, "gl encoder started " + w + "x" + h + "@" + FPS + "fps CBR " + BITRATE + " dpi=" + dpi);

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { Log.i(TAG, "projection onStop"); }
        }, main);
        virtualDisplay = projection.createVirtualDisplay("lb-stream", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, projSurface, null, null);

        running = true;
        videoInfoSent = false;
    }

    // ── ScreenGlEncoder.Callback (called from the GL thread) ──
    @Override
    public void onVideoInfo(byte[] sps, byte[] pps) {
        spsData = (sps != null) ? sps.clone() : null;
        ppsData = (pps != null) ? pps.clone() : null;
        applyVideoInfo();
        Log.i(TAG, "setVideoInfo sps/pps sent");
    }

    @Override
    public void onVideoFrame(ByteBuffer buf, MediaCodec.BufferInfo info) {
        SrtClient c = srtClient;
        if (videoInfoSent && c != null) {
            try { c.sendVideo(buf, info); } catch (Throwable t) { if (running) Log.e(TAG, "sendVideo", t); }
        }
    }

    private void applyVideoInfo() {
        SrtClient c = srtClient;
        if (c != null && spsData != null && ppsData != null) {
            try {
                c.setVideoInfo(ByteBuffer.wrap(spsData), ByteBuffer.wrap(ppsData), null);
                videoInfoSent = true;
            } catch (Throwable t) { Log.e(TAG, "applyVideoInfo", t); }
        }
    }

    private void stopEverything() {
        running = false;
        try { if (glEncoder != null) glEncoder.stop(); } catch (Throwable ignored) {}
        glEncoder = null;
        try { if (srtClient != null && srtClient.isStreaming()) srtClient.disconnect(); } catch (Throwable t) { Log.e(TAG, "disconnect", t); }
        srtClient = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
        virtualDisplay = null;
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        projection = null;
        videoInfoSent = false;
    }

    private void startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground type failed, fallback", t);
            try { startForeground(NOTIF_ID, buildNotification()); }
            catch (Throwable t2) { Log.e(TAG, "startForeground fallback failed", t2); }
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // minSdk is 26, so notification channels always exist.
        nm.createNotificationChannel(
                new NotificationChannel(CH, "Streaming", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = new Notification.Builder(this, CH);
        return b.setContentTitle("Teverun LB Stream")
                .setContentText("Screen is being streamed")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopEverything();
    }

    // ── ConnectChecker ──
    @Override public void onConnectionStarted(String url) { Log.i(TAG, "onConnectionStarted " + url); setStatus("connecting"); }
    @Override public void onConnectionSuccess() { Log.i(TAG, "onConnectionSuccess"); setStatus("live"); }
    @Override public void onConnectionFailed(String reason) {
        Log.e(TAG, "onConnectionFailed: " + reason);
        if (!userStopped && running && currentUrl != null) {
            setStatus("reconnecting");
            main.postDelayed(() -> {
                if (userStopped || !running) return;
                try {
                    try { if (srtClient != null) srtClient.disconnect(); } catch (Throwable ignored) {}
                    videoInfoSent = false;
                    SrtClient c = new SrtClient(this);
                    c.setVideoCodec(VideoCodec.H264);
                    c.setOnlyVideo(true);
                    srtClient = c;
                    c.connect(currentUrl);
                    applyVideoInfo();                       // re-send stored SPS/PPS
                    if (glEncoder != null) glEncoder.requestKeyframe();  // force an IDR
                    Log.i(TAG, "reconnect attempt");
                } catch (Throwable t) { Log.e(TAG, "reconnect", t); }
            }, 2500);
            return;
        }
        setStatus("failed: " + reason);
    }
    @Override public void onNewBitrate(long bitrate) {}
    @Override public void onDisconnect() { Log.i(TAG, "onDisconnect"); }
    @Override public void onAuthError() { Log.e(TAG, "onAuthError"); setStatus("auth error"); }
    @Override public void onAuthSuccess() { Log.i(TAG, "onAuthSuccess"); }
}
