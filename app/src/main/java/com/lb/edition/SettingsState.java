// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import org.json.JSONObject;

/**
 * The maintained "current settings" state of the scooter. It is kept up to date from the incoming
 * 55 71 main-control frame (and motor mode from 55 72) so that a PARTIAL update coming from the
 * dashboard (LB.sendSetting) can be merged onto it and serialised into ONE valid full 0x18 frame
 * (BLE_PROTOCOL §3.4). Field names mirror the settings JSON contract in BRIDGE.md.
 *
 * Defaults are best-effort typical values for a Fighter Mini Pro (60 V). In normal use the state
 * is populated from a real 55 71 read before the user changes anything, so the defaults only
 * matter if a setting is written before the first telemetry arrives.
 */
final class SettingsState {

    // Settings exposed through LB.sendSetting
    volatile int gear = 1;                 // 1..5
    volatile double wheel = 8.5;           // internal wheel unit (55 71 t[6] * 0.1)
    volatile int sysProTemp = 80;          // protection temp
    volatile int motorPolePairs = 15;
    volatile int assistSpeedLimit = 25;    // a[10] per-gear/assist limit (55 71 t[10])
    volatile int speedLimit = 25;          // a[11] main limit (55 71 t[11])
    volatile int fCurrent = 0;             // a[12] front current limit (55 71 t[12])
    volatile int rCurrent = 0;             // a[13] rear current limit (55 71 t[13])
    volatile int packVolt = 60;            // a[15] nominal pack voltage
    volatile boolean enfEcon = false;      // eco
    volatile boolean isUnitMile = false;
    volatile boolean atMode = false;
    volatile boolean isSmart = false;
    volatile int cruise = 0;               // 0 none, 1 auto, 2 manual (matches the original app + display)
    volatile boolean abs = false;
    volatile boolean startMode = false;    // launch mode
    volatile int fStartLevel = 0;
    volatile int rStartLevel = 0;
    volatile int eabsLevel = 0;
    volatile int sleepTime = 0;            // 0..7
    volatile int prTime = 0;               // 0..31

    // Motor mode bits (kept from 55 72 read-back; used for a[4] bit7 / a[17] bit7)
    volatile int rmStatus = 1;             // rear motor active
    volatile int doubleMotor = 1;          // dual / front motor active

    volatile boolean received71 = false;

    /** Update the state from a decoded 55 71 main-control frame (t = 20 unsigned bytes). */
    synchronized void updateFrom71(int[] t) {
        gear = t[3] & 0xFF;

        int r = t[4] & 0xFF;                       // rControlStatus (LSB-first)
        int b1 = (r >> 1) & 1, b2 = (r >> 2) & 1;
        cruise = (b2 << 1) | b1;                   // (bit2<<1)|bit1
        abs = ((r >> 3) & 1) != 0;
        startMode = ((r >> 6) & 1) != 0;

        motorPolePairs = t[5] & 0xFF;
        wheel = (t[6] & 0xFF) * 0.1;
        sysProTemp = t[7] & 0xFF;

        fStartLevel = t[8] & 0x0F;                 // low nibble
        eabsLevel = (t[9] >> 4) & 0x0F;            // high nibble
        rStartLevel = t[9] & 0x0F;                 // low nibble

        assistSpeedLimit = t[10] & 0xFF;
        speedLimit = t[11] & 0xFF;
        fCurrent = t[12] & 0xFF;
        rCurrent = t[13] & 0xFF;
        packVolt = t[15] & 0xFF;

        int sys = t[17] & 0xFF;                    // systemStatus flags (LSB-first)
        enfEcon = (sys & 0x01) != 0;               // bit0
        isUnitMile = (sys & 0x02) != 0;            // bit1
        atMode = (sys & 0x04) != 0;                // bit2
        isSmart = (sys & 0x10) != 0;               // bit4

        int sp = t[18] & 0xFF;
        sleepTime = sp & 0x07;
        prTime = (sp >> 3) & 0x1F;

        received71 = true;
    }

    /** Merge a partial settings JSON object from the dashboard. All keys optional; unknown ignored. */
    synchronized void merge(JSONObject o) {
        if (o == null) return;
        if (o.has("speedLimit")) {
            int v = o.optInt("speedLimit", speedLimit);
            speedLimit = v;
            assistSpeedLimit = v;   // keep per-gear limit in sync with the requested main limit
        }
        if (o.has("gear")) gear = o.optInt("gear", gear);
        if (o.has("wheel")) wheel = o.optDouble("wheel", wheel);
        if (o.has("sysProTemp")) sysProTemp = o.optInt("sysProTemp", sysProTemp);
        if (o.has("fCurrent")) fCurrent = o.optInt("fCurrent", fCurrent);
        if (o.has("rCurrent")) rCurrent = o.optInt("rCurrent", rCurrent);
        if (o.has("packVolt")) packVolt = o.optInt("packVolt", packVolt);
        if (o.has("motorPolePairs")) motorPolePairs = o.optInt("motorPolePairs", motorPolePairs);
        if (o.has("enfEcon")) enfEcon = o.optBoolean("enfEcon", enfEcon);
        if (o.has("isUnitMile")) isUnitMile = o.optBoolean("isUnitMile", isUnitMile);
        if (o.has("atMode")) atMode = o.optBoolean("atMode", atMode);
        if (o.has("isSmart")) isSmart = o.optBoolean("isSmart", isSmart);
        if (o.has("cruise")) cruise = o.optInt("cruise", cruise);
        if (o.has("abs")) abs = o.optBoolean("abs", abs);
        if (o.has("startMode")) startMode = o.optBoolean("startMode", startMode);
        if (o.has("fStartLevel")) fStartLevel = o.optInt("fStartLevel", fStartLevel);
        if (o.has("rStartLevel")) rStartLevel = o.optInt("rStartLevel", rStartLevel);
        if (o.has("eabsLevel")) eabsLevel = o.optInt("eabsLevel", eabsLevel);
        if (o.has("sleepTime")) sleepTime = o.optInt("sleepTime", sleepTime);
        if (o.has("prTime")) prTime = o.optInt("prTime", prTime);
        if (o.has("rmStatus")) rmStatus = o.optInt("rmStatus", rmStatus);
        if (o.has("doubleMotor")) doubleMotor = o.optInt("doubleMotor", doubleMotor);
    }

    /**
     * Build ONE per-gear 0x18 settings frame for {@code gear} (controller gear/assist index). The
     * six per-gear/assist values are read from {@code vals} (keys mirror the original app's
     * assistList: {@code speedLimit, eabsLevel, fStartLevel, rStartLevel, fCurrent, rCurrent}); any
     * missing key falls back to the maintained current-gear value. Every OTHER config byte stays at
     * the maintained current state (BLE_PROTOCOL §3.4). Values are clamped byte/nibble-safe so the
     * frame is always valid.
     */
    synchronized byte[] gearFrame(int gear, JSONObject vals) {
        int sl   = clamp(optI(vals, "speedLimit",  assistSpeedLimit), 0, 255);
        int eabs = clamp(optI(vals, "eabsLevel",   eabsLevel),        0, 15);   // a[8]/a[9] nibble
        int fs   = clamp(optI(vals, "fStartLevel", fStartLevel),      0, 15);   // a[8] low nibble
        int rs   = clamp(optI(vals, "rStartLevel", rStartLevel),      0, 15);   // a[9] low nibble
        int fc   = clamp(optI(vals, "fCurrent",    fCurrent),         0, 255);
        int rc   = clamp(optI(vals, "rCurrent",    rCurrent),         0, 255);
        return CommandBuilder.sendGearSetting(this, gear & 0xFF, sl, eabs, fs, rs, fc, rc);
    }

    private static int optI(JSONObject o, String key, int def) {
        if (o == null || !o.has(key)) return def;
        return o.optInt(key, def);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
