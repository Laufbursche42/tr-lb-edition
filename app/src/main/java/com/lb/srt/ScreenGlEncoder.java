// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.srt;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Captures a Surface (fed by MediaProjection) into an OpenGL texture and re-draws it into
 * an H.264 encoder input surface at a FIXED frame rate. Redrawing on a timer - even when the
 * screen is static and MediaProjection delivers no new frames - guarantees a real constant
 * fps / constant bitrate stream, which the MTK encoder's KEY_REPEAT_PREVIOUS_FRAME_AFTER does
 * NOT provide. Shaders are embedded here as strings, so no res/raw resources are needed.
 */
public class ScreenGlEncoder implements android.graphics.SurfaceTexture.OnFrameAvailableListener {

    public interface Callback {
        void onVideoInfo(byte[] sps, byte[] pps);
        void onVideoFrame(ByteBuffer buf, MediaCodec.BufferInfo info);
    }

    private static final String TAG = "lbsrt";
    private static final int KEYFRAME_EVERY = 4;   // force an IDR every N frames (~7.5/s at 30fps)

    private final int width, height, fps, bitrate;
    private final Callback cb;

    private MediaCodec encoder;
    private Surface encoderSurface;              // encoder input (EGL draws here)
    private android.graphics.SurfaceTexture cameraTexture; // MediaProjection draws here
    private Surface inputSurface;                // wraps cameraTexture, given to MediaProjection

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program, texId, aPos, aTex, uTexMatrix;
    private FloatBuffer quadVertices, quadTex;
    private final float[] texMatrix = new float[16];

    private volatile boolean running = false;
    private volatile boolean frameAvailable = false;
    private final Object frameLock = new Object();
    private Thread thread;

    private static final float[] QUAD = { -1,-1,  1,-1,  -1,1,  1,1 };
    private static final float[] TEX  = {  0, 0,  1, 0,   0,1,  1,1 };

    private static final String VERT =
        "attribute vec4 aPos;\n" +
        "attribute vec4 aTex;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "varying vec2 vTex;\n" +
        "void main(){ gl_Position = aPos; vTex = (uTexMatrix * aTex).xy; }\n";

    private static final String FRAG =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform samplerExternalOES sTex;\n" +
        "void main(){ gl_FragColor = texture2D(sTex, vTex); }\n";

    public ScreenGlEncoder(int width, int height, int fps, int bitrate, Callback cb) {
        this.width = width; this.height = height; this.fps = fps; this.bitrate = bitrate; this.cb = cb;
    }

    /** Configure the encoder + its input surface. Call before start(). */
    public void prepare() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        encoder = MediaCodec.createEncoderByType("video/avc");
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();
    }

    /** The surface MediaProjection should render the screen onto (available after start()). */
    public Surface getInputSurface() { return inputSurface; }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "lb-gl");
        thread.start();
        // Wait until the GL thread has created the camera SurfaceTexture / inputSurface.
        synchronized (frameLock) {
            while (inputSurface == null && running) {
                try { frameLock.wait(2000); } catch (InterruptedException ignored) {}
                break;
            }
        }
    }

    public void stop() {
        running = false;
        if (thread != null) { try { thread.join(800); } catch (InterruptedException ignored) {} }
        thread = null;
    }

    @Override
    public void onFrameAvailable(android.graphics.SurfaceTexture st) {
        synchronized (frameLock) { frameAvailable = true; frameLock.notifyAll(); }
    }

    // ── GL thread ──
    private void loop() {
        try {
            initEgl();
            initGl();
            // Now the camera texture exists -> publish inputSurface for MediaProjection.
            synchronized (frameLock) { frameLock.notifyAll(); }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long frameNs = 1_000_000_000L / fps;
            long nextDraw = System.nanoTime();
            Matrix.setIdentityM(texMatrix, 0);
            Log.i(TAG, "gl loop running");
            int draws = 0;

            while (running) {
                // Consume a new camera frame if one arrived (non-blocking).
                boolean update;
                synchronized (frameLock) { update = frameAvailable; frameAvailable = false; }
                if (update) {
                    try { cameraTexture.updateTexImage(); cameraTexture.getTransformMatrix(texMatrix); }
                    catch (Throwable t) { Log.e(TAG, "updateTexImage", t); }
                }

                // Draw the (possibly unchanged) texture -> feeds one frame to the encoder.
                drawFrame();
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime());
                boolean swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                draws++;
                // Force frequent keyframes. A static screen compresses P-frames to ~0, so CBR
                // starves and the bitrate collapses. Regular keyframes give CBR real data to
                // fill, holding a constant bitrate even on a frozen screen.
                if (draws % KEYFRAME_EVERY == 0) requestKeyframe();
                if (draws % 60 == 1) Log.i(TAG, "gl draws=" + draws + " swap=" + swapped + " err=" + EGL14.eglGetError());

                drainEncoder(info);

                // Pace to the target fps.
                nextDraw += frameNs;
                long sleep = nextDraw - System.nanoTime();
                if (sleep > 0) { try { Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L)); } catch (InterruptedException ignored) {} }
                else nextDraw = System.nanoTime();
            }
        } catch (Throwable t) {
            Log.e(TAG, "gl loop", t);
        } finally {
            releaseEncoder();
            releaseEgl();
        }
    }

    private int encOut = 0;
    private void drainEncoder(MediaCodec.BufferInfo info) {
        while (true) {
            int idx = encoder.dequeueOutputBuffer(info, 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = encoder.getOutputFormat();
                ByteBuffer sps = of.getByteBuffer("csd-0");
                ByteBuffer pps = of.getByteBuffer("csd-1");
                if (sps != null && pps != null) cb.onVideoInfo(toBytes(sps), toBytes(pps));
            } else if (idx >= 0) {
                ByteBuffer buf = encoder.getOutputBuffer(idx);
                boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!cfg && info.size > 0 && buf != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    cb.onVideoFrame(buf, info);
                    encOut++;
                    if (encOut % 30 == 1) Log.i(TAG, "enc out #" + encOut + " size=" + info.size);
                }
                encoder.releaseOutputBuffer(idx, false);
            } else {
                break; // INFO_TRY_AGAIN_LATER
            }
        }
    }

    /** Force the next encoded frame to be an IDR (used on reconnect). */
    public void requestKeyframe() {
        try {
            if (encoder != null) {
                android.os.Bundle b = new android.os.Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(b);
            }
        } catch (Throwable ignored) {}
    }

    private void drawFrame() {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadVertices);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    private void initGl() {
        program = buildProgram(VERT, FRAG);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        quadVertices = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVertices.put(QUAD).position(0);
        quadTex = ByteBuffer.allocateDirect(TEX.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadTex.put(TEX).position(0);

        cameraTexture = new android.graphics.SurfaceTexture(texId);
        cameraTexture.setDefaultBufferSize(width, height);
        cameraTexture.setOnFrameAvailableListener(this);
        inputSurface = new Surface(cameraTexture);
    }

    private int buildProgram(String v, String f) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, v);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, f);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);
        int[] attribs = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0);
        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        int[] surfAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfAttribs, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void releaseEncoder() {
        try { if (encoder != null) { encoder.signalEndOfInputStream(); } } catch (Throwable ignored) {}
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Throwable ignored) {}
        encoder = null;
        try { if (inputSurface != null) inputSurface.release(); } catch (Throwable ignored) {}
        inputSurface = null;
        try { if (cameraTexture != null) cameraTexture.release(); } catch (Throwable ignored) {}
        cameraTexture = null;
        try { if (encoderSurface != null) encoderSurface.release(); } catch (Throwable ignored) {}
        encoderSurface = null;
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private static byte[] toBytes(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        byte[] a = new byte[d.remaining()];
        d.get(a);
        return a;
    }
}
