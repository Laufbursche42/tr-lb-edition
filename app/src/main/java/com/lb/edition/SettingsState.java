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
    volatile int protectionTemp = 120;     // protection temp, firmware factory value (range 80..130)
    volatile int motorPolePairs = 15;
    volatile int assistSpeedLimit = 25;    // a[10] per-gear/assist limit (55 71 t[10])
    volatile int speedLimit = 25;          // a[11] main limit (55 71 t[11])
    volatile int frontCurrent = 0;         // a[12] front current limit (55 71 t[12])
    volatile int rearCurrent = 0;          // a[13] rear current limit (55 71 t[13])
    volatile int packVoltage = 60;         // a[15] nominal pack voltage
    volatile boolean ecoMode = false;
    volatile boolean unitMiles = false;
    volatile boolean antiTheft = false;
    volatile boolean tractionControl = false;
    volatile int cruise = 0;               // 0 none, 1 auto, 2 manual, as the display shows it
    volatile boolean abs = false;
    volatile boolean startMode = false;    // launch mode
    volatile int frontStartLevel = 0;
    volatile int rearStartLevel = 0;
    volatile int eabsRegen = 0;
    volatile int sleepTime = 0;            // 0..7
    volatile int powerOffTime = 0;         // auto power-off timer, 0..31

    // Motor mode bits (kept from 55 72 read-back; used for a[4] bit7 / a[17] bit7)
    volatile int rearMotorOn = 1;          // rear motor active
    volatile int dualMotor = 1;            // dual / front motor active

    volatile boolean received71 = false;

    /** Update the state from a decoded 55 71 main-control frame (t = 20 unsigned bytes). */
    synchronized void updateFrom71(int[] t) {
        gear = t[3] & 0xFF;

        int r = t[4] & 0xFF;                       // rearControlStatus (LSB-first)
        int b1 = (r >> 1) & 1, b2 = (r >> 2) & 1;
        cruise = (b2 << 1) | b1;                   // (bit2<<1)|bit1
        abs = ((r >> 3) & 1) != 0;
        startMode = ((r >> 6) & 1) != 0;

        motorPolePairs = t[5] & 0xFF;
        wheel = (t[6] & 0xFF) * 0.1;
        protectionTemp = t[7] & 0xFF;

        frontStartLevel = t[8] & 0x0F;             // low nibble
        eabsRegen = (t[9] >> 4) & 0x0F;            // high nibble
        rearStartLevel = t[9] & 0x0F;              // low nibble

        assistSpeedLimit = t[10] & 0xFF;
        speedLimit = t[11] & 0xFF;
        frontCurrent = t[12] & 0xFF;
        rearCurrent = t[13] & 0xFF;
        packVoltage = t[15] & 0xFF;

        int sys = t[17] & 0xFF;                    // systemStatus flags (LSB-first)
        ecoMode = (sys & 0x01) != 0;               // bit0
        unitMiles = (sys & 0x02) != 0;             // bit1
        antiTheft = (sys & 0x04) != 0;             // bit2
        tractionControl = (sys & 0x10) != 0;       // bit4

        int sp = t[18] & 0xFF;
        sleepTime = sp & 0x07;
        powerOffTime = (sp >> 3) & 0x1F;

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
        if (o.has("protectionTemp")) protectionTemp = o.optInt("protectionTemp", protectionTemp);
        if (o.has("frontCurrent")) frontCurrent = o.optInt("frontCurrent", frontCurrent);
        if (o.has("rearCurrent")) rearCurrent = o.optInt("rearCurrent", rearCurrent);
        if (o.has("packVoltage")) packVoltage = o.optInt("packVoltage", packVoltage);
        if (o.has("motorPolePairs")) motorPolePairs = o.optInt("motorPolePairs", motorPolePairs);
        if (o.has("ecoMode")) ecoMode = o.optBoolean("ecoMode", ecoMode);
        if (o.has("unitMiles")) unitMiles = o.optBoolean("unitMiles", unitMiles);
        if (o.has("antiTheft")) antiTheft = o.optBoolean("antiTheft", antiTheft);
        if (o.has("tractionControl")) tractionControl = o.optBoolean("tractionControl", tractionControl);
        if (o.has("cruise")) cruise = o.optInt("cruise", cruise);
        if (o.has("abs")) abs = o.optBoolean("abs", abs);
        if (o.has("startMode")) startMode = o.optBoolean("startMode", startMode);
        if (o.has("frontStartLevel")) frontStartLevel = o.optInt("frontStartLevel", frontStartLevel);
        if (o.has("rearStartLevel")) rearStartLevel = o.optInt("rearStartLevel", rearStartLevel);
        if (o.has("eabsRegen")) eabsRegen = o.optInt("eabsRegen", eabsRegen);
        if (o.has("sleepTime")) sleepTime = o.optInt("sleepTime", sleepTime);
        if (o.has("prTime")) powerOffTime = o.optInt("prTime", powerOffTime);
        if (o.has("rearMotorOn")) rearMotorOn = o.optInt("rearMotorOn", rearMotorOn);
        if (o.has("dualMotor")) dualMotor = o.optInt("dualMotor", dualMotor);
    }

    /**
     * Build ONE per-gear 0x18 settings frame for {@code gear} (controller gear/assist index). The
     * six per-gear/assist values are read from {@code vals} (keys follow the per-gear block:
     * {@code speedLimit, eabsRegen, frontStartLevel, rearStartLevel, frontCurrent, rearCurrent});
     * any missing key falls back to the maintained current-gear value. Every OTHER config byte stays
     * at the maintained current state (BLE_PROTOCOL §3.4). Values are clamped byte/nibble-safe so the
     * frame is always valid.
     */
    synchronized byte[] gearFrame(int gear, JSONObject vals) {
        int sl   = clamp(optI(vals, "speedLimit",      assistSpeedLimit), 0, 255);
        int eabs = clamp(optI(vals, "eabsRegen",       eabsRegen),        0, 15);   // a[8]/a[9] nibble
        int fs   = clamp(optI(vals, "frontStartLevel", frontStartLevel),  0, 15);   // a[8] low nibble
        int rs   = clamp(optI(vals, "rearStartLevel",  rearStartLevel),   0, 15);   // a[9] low nibble
        int fc   = clamp(optI(vals, "frontCurrent",    frontCurrent),     0, 100);
        int rc   = clamp(optI(vals, "rearCurrent",     rearCurrent),      0, 100);
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
