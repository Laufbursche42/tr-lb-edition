// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Splits incoming BLE notification payloads into 20-byte frames, validates CRC-8, and decodes the
 * Teverun telemetry frames (VCU -> phone) into a live-data model (BLE_PROTOCOL §2). The model is
 * serialised to JSON with the field names from BRIDGE.md / §2.6 (plus the short aliases the
 * dashboard's existing parseBLE() already reads, so telemetry.html works unmodified).
 *
 * Thread-safety: onNotify() runs on the GATT callback thread; toJson() may run on the UI thread.
 * All mutable model fields are guarded by this instance's monitor.
 */
final class FrameParser {

    private final SettingsState settings;

    // Feature flag (set from the BLE name by BleManager): T2* platform reads capacity from a
    // different byte pair in 55 53.
    volatile boolean isVer2 = false;

    // Phone-side RSSI (set by BleManager); not part of the VCU frames.
    volatile int rssi = 0;

    // Bluetooth advertised name of the connected scooter (set by BleManager); not part of the VCU
    // frames. Forms the prefix of the full FIN (BLE name + frame number) shown on the info page.
    volatile String btName = "";

    // ── Live model (BRIDGE.md §2.6) ──
    private double realSpeed, avgSpeed, maxSpeed;
    private int SOC, soh;
    private double VolPack, poleVol, power;
    private double rMotorCurrent, fMotorCurrent;
    private int rMotorTemp, fMotorTemp;
    private double singleMile, totalMile, enFeedBack;
    private int gear, speedLimit, chargeCounter, customKey;
    private int doubleMotor, rmStatus;
    private int[] systemStatus = new int[8];
    private int[] ecuStatus1 = new int[8];
    private int[] ecuStatus2 = new int[8];
    private int[] rControlStatus = new int[8];
    private int[] fControlStatus = new int[8];

    // 55 54 error/severity array (t[2..18]); index = error type, value = severity (value>0 => fault).
    private int[] errors = new int[17];

    // Battery detail (surfaced through top[] / bottom[])
    private double packCurrent;
    private final double[] batTemp = new double[7];   // 55 52 t[10..16]: cell0-2 / MOS0-1 / BMS-PCB0-1
    private int maxCellTemp, minCellTemp;
    private int maxCellV, minCellV, capacity;
    private boolean have52 = false, have53 = false;

    // 55 53 BMS relays / MOS control / balance / cell count
    private int relay1, relay2, relay3;               // t[2]/t[3]/t[4] (relay3 == relay M / charMode)
    private int chrMosState, dischrMosState;          // t[5]/t[6]
    private int volListLength;                        // t[14] cell count
    private int[] balState0 = new int[8];             // t[7] per-cell balancing bitfield
    private int[] batteryStatus = new int[8];         // t[3] doubles as a BMS status bitfield

    // 55 72 extended controller / ECU status
    private int[] fEcuStatus1 = new int[8];           // t[2]
    private int[] fEcuStatus2 = new int[8];           // t[3] ([3] = doubleMotor)
    private int[] systemStatus3 = new int[8];         // t[18]

    // 55 73 extended status
    private int[] systemStatus2 = new int[8];         // t[16]: [0]=batLock, [1]=gpsLock
    private int[] status73 = new int[8];              // t[18]: [3]=PowerMode, [4]=LightSens, [5]=Voice, AtLevel=(bit7<<1)|bit6

    private int chargeStatus;                         // 55 54 t[17] (charge-image index)
    private boolean have54 = false, have72 = false, have73 = false;

    // Raw speed (kept to derive realSpeed once wheel size is known)
    private int speedRaw = 0;

    // Identity / version strings (55 41..45, 4D)
    private volatile String batCode = "";
    private volatile String frameNum = "";
    private String swVer = "", hwVer = "", displayVer = "";

    FrameParser(SettingsState settings) {
        this.settings = settings;
    }

    /**
     * Split a notification into 20-byte frames, validate each CRC-8, and decode. A single
     * notification can carry several concatenated 20-byte frames.
     */
    void onNotify(byte[] value) {
        if (value == null || value.length < 20) return;
        for (int off = 0; off + 20 <= value.length; off += 20) {
            int[] t = new int[20];
            for (int i = 0; i < 20; i++) t[i] = value[off + i] & 0xFF;
            if (!validCrc(t)) continue;
            if (t[0] != 0x55) continue;
            try {
                dispatch(t);
            } catch (Throwable ignored) {
                // never let a malformed frame break the pipeline
            }
        }
    }

    private static boolean validCrc(int[] t) {
        return CommandBuilder.crc8(t, 19) == (t[19] & 0xFF);
    }

    // Diagnostic: which frame ids have been seen at least once (index = frame id byte). Logged on
    // first sight so the in-app debug log makes it obvious whether the 55 71 settings frame actually
    // arrives from this unit (the settings page is empty when it never does).
    private final boolean[] seenFrame = new boolean[256];

    private void dispatch(int[] t) {
        int id = t[1] & 0xFF;
        if (!seenFrame[id]) {
            seenFrame[id] = true;
            android.util.Log.i("lbble", "first frame seen: 55 " + Integer.toHexString(id));
        }
        switch (t[1]) {
            case 0x52: parse52(t); break;
            case 0x53: parse53(t); break;
            case 0x54: parse54(t); break;
            case 0x71: parse71(t); break;
            case 0x72: parse72(t); break;
            case 0x73: parse73(t); break;
            case 0x41: batCode = ascii(t, 2, 16, "AW"); break;
            case 0x42: frameNum = ascii(t, 2, 18, null); break;
            case 0x43: parse43(t); break;
            case 0x44: parse44(t); break;
            case 0x45: case 0x4D: /* other fw version strings - not surfaced */ break;
            case 0x79: case 0x7A: /* Tetra 4-motor - not used by this unit */ break;
            default: break;
        }
    }

    // ── helpers ──

    private static int u8(int[] t, int i) { return t[i] & 0xFF; }

    private static int u16(int[] t, int i) { return ((t[i] & 0xFF) << 8) | (t[i + 1] & 0xFF); }

    /** LSB-first bit array (index 0 = bit0), matching formattingBalStatus(). */
    private static int[] bits(int v) {
        int[] b = new int[8];
        for (int i = 0; i < 8; i++) b[i] = (v >> i) & 1;
        return b;
    }

    private static String ascii(int[] t, int from, int toInclusive, String prefixIfMissing) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= toInclusive && i < 20; i++) {
            int c = t[i] & 0xFF;
            if (c >= 0x20 && c <= 0x7E) sb.append((char) c);
        }
        String s = sb.toString().trim();
        if (prefixIfMissing != null && !s.isEmpty() && !s.startsWith(prefixIfMissing)) {
            s = prefixIfMissing + s;
        }
        return s;
    }

    // ── frame decoders ──

    private synchronized void parse52(int[] t) {
        VolPack = u16(t, 2) * 0.1;
        poleVol = u16(t, 4) * 0.1;
        packCurrent = u16(t, 6) * 0.1 - 1000;     // < 0 => regeneration
        SOC = u8(t, 8);
        soh = u8(t, 9);                            // raw*0.01*100 == raw
        for (int i = 0; i < 7; i++) batTemp[i] = u8(t, 10 + i) - 40;
        maxCellTemp = u8(t, 17) - 40;
        minCellTemp = u8(t, 18) - 40;
        have52 = true;
    }

    private synchronized void parse53(int[] t) {
        relay1 = u8(t, 2);
        relay2 = u8(t, 3);
        relay3 = u8(t, 4);                         // relay M / charMode
        chrMosState = u8(t, 5);
        dischrMosState = u8(t, 6);
        balState0 = bits(u8(t, 7));                // per-cell balancing bitfield
        if (u8(t, 3) != 0xFF) batteryStatus = bits(u8(t, 3));  // t[3] doubles as BMS status
        capacity = isVer2 ? u16(t, 10) : u16(t, 8);
        chargeCounter = u16(t, 12);
        volListLength = u8(t, 14);                 // cell count
        maxCellV = u16(t, 15);
        minCellV = u16(t, 17);
        have53 = true;
    }

    /**
     * 55 54 - error codes / charge status. t[2..18] carry a per-error severity byte; the array index
     * is the error type, the value is the severity (value > 0 => fault). t[17] doubles as the charge
     * status (error index 15). Kept raw here; the dashboard applies the app's warn thresholds.
     */
    private synchronized void parse54(int[] t) {
        for (int i = 0; i < errors.length; i++) errors[i] = u8(t, i + 2);
        chargeStatus = u8(t, 17);                  // charge-image index (error index 15)
        have54 = true;
    }

    private synchronized void parse71(int[] t) {
        gear = u8(t, 3);
        rControlStatus = bits(u8(t, 4));
        speedLimit = u8(t, 11);
        fControlStatus = bits(u8(t, 16));
        systemStatus = bits(u8(t, 17));
        // keep the maintained settings state current so partial writes produce a valid full frame
        settings.updateFrom71(t);
    }

    private synchronized void parse72(int[] t) {
        fEcuStatus1 = bits(u8(t, 2));
        int[] fEcu2 = bits(u8(t, 3));
        fEcuStatus2 = fEcu2;
        doubleMotor = fEcu2[3];
        fMotorCurrent = u16(t, 4) * 0.1;
        int fTempRaw = u8(t, 9);
        if (fTempRaw > 0) fMotorTemp = fTempRaw;     // per §2.4 table: raw (no -40 offset)

        ecuStatus1 = bits(u8(t, 10));
        ecuStatus2 = bits(u8(t, 11));
        rmStatus = ecuStatus2[3];
        rMotorCurrent = u16(t, 12) * 0.1;
        speedRaw = u16(t, 15);
        int rTempRaw = u8(t, 17);
        if (rTempRaw > 0) rMotorTemp = rTempRaw;
        if (u8(t, 18) != 0xFF) systemStatus3 = bits(u8(t, 18));
        have72 = true;

        // sync current motor mode into the settings state so a settings write preserves it
        settings.rmStatus = rmStatus;
        settings.doubleMotor = doubleMotor;

        recomputeDerived();
    }

    private synchronized void parse73(int[] t) {
        avgSpeed = u16(t, 2) * 0.1;
        maxSpeed = u16(t, 4) * 0.1;
        singleMile = u16(t, 6) * 0.1;
        totalMile = ((t[8] & 0xFF) << 16) | ((t[9] & 0xFF) << 8) | (t[10] & 0xFF); // 3-byte BE
        enFeedBack = u16(t, 11) * 0.1;
        if (u8(t, 16) != 0xFF) systemStatus2 = bits(u8(t, 16));   // [0]=batLock, [1]=gpsLock
        customKey = u8(t, 17);
        if (u8(t, 18) != 0xFF) status73 = bits(u8(t, 18));        // PowerMode/LightSens/Voice/AtLevel
        have73 = true;
    }

    private synchronized void parse43(int[] t) {
        if (u8(t, 2) > 0) swVer = u8(t, 2) + "." + u8(t, 3) + "." + u8(t, 4);
        if (u8(t, 6) > 0) hwVer = u8(t, 6) + "." + u8(t, 7) + "." + u8(t, 8);
    }

    /**
     * 55 44 - display / battery / LC firmware (BLE_PROTOCOL §2.3). t[2]=disProType, t[3]=disProCode,
     * t[4].t[5].t[6] = display SW version (decimal, dotted). FF FF FF => version unknown, keep blank.
     */
    private synchronized void parse44(int[] t) {
        if (!(u8(t, 4) == 0xFF && u8(t, 5) == 0xFF && u8(t, 6) == 0xFF)) {
            displayVer = u8(t, 4) + "." + u8(t, 5) + "." + u8(t, 6);
        }
    }

    /** Real speed (region caps intentionally ignored) and power. */
    private void recomputeDerived() {
        double wheel = settings.wheel;
        double v = 0;
        if (speedRaw > 0) v = 287.0 * wheel / speedRaw;
        if (speedRaw >= 3000 || v <= 0.5) v = 0;
        if (settings.isUnitMile) v = v / 1.6093439;
        realSpeed = v;

        if (doubleMotor == 1) {
            power = (rMotorCurrent + fMotorCurrent) * VolPack / 1000.0;
        } else {
            power = rMotorCurrent * VolPack / 1000.0;
        }
    }

    // ── JSON serialisation (BRIDGE.md live-data contract + dashboard aliases) ──

    synchronized String toJson() {
        JSONObject o = new JSONObject();
        try {
            // Canonical names (BRIDGE.md §2.6)
            o.put("realSpeed", round1(realSpeed));
            o.put("avgSpeed", round1(avgSpeed));
            o.put("maxSpeed", round1(maxSpeed));
            o.put("SOC", SOC);
            o.put("soh", soh);
            o.put("VolPack", round1(VolPack));
            o.put("poleVol", round1(poleVol));
            o.put("power", round2(power));
            o.put("rMotorCurrent", round1(rMotorCurrent));
            o.put("fMotorCurrent", round1(fMotorCurrent));
            o.put("rMotorTemp", rMotorTemp);
            o.put("fMotorTemp", fMotorTemp);
            o.put("singleMile", round1(singleMile));
            o.put("totalMile", round1(totalMile));
            o.put("enFeedBack", round1(enFeedBack));
            o.put("gear", gear);
            o.put("speedLimit", speedLimit);
            o.put("chargeCounter", chargeCounter);
            o.put("customKey", customKey);
            o.put("doubleMotor", doubleMotor);
            o.put("rmStatus", rmStatus);
            o.put("systemStatus", intArr(systemStatus));
            o.put("ecuStatus1", intArr(ecuStatus1));
            o.put("ecuStatus2", intArr(ecuStatus2));
            o.put("rControlStatus", intArr(rControlStatus));
            o.put("fControlStatus", intArr(fControlStatus));
            o.put("fEcuStatus1", intArr(fEcuStatus1));
            o.put("fEcuStatus2", intArr(fEcuStatus2));
            o.put("systemStatus2", intArr(systemStatus2));
            o.put("systemStatus3", intArr(systemStatus3));
            o.put("balState0", intArr(balState0));
            o.put("batteryStatus", intArr(batteryStatus));

            // Has the scooter reported its real config yet (a 55 71 frame arrived)? The R5.4.19 VCU
            // streams 55 71 periodically, so this is normally true within ~1-2 s of connecting. The UI
            // uses it to explain why a settings/gear write is not possible yet instead of failing
            // silently (a write before this would serialise SettingsState defaults, and the VCU applies
            // them unvalidated - verified in the firmware 0x18 handler).
            o.put("settingsReady", settings.received71);
            // Settings-derived scalars (from the maintained 55 71 state) - also feed the scooter
            // settings page prefill (telemetry.html prefillScooterSettings()).
            if (settings.received71) {
                o.put("wheel", round1(settings.wheel));
                o.put("packVolt", settings.packVolt);
                o.put("motorPolePairs", settings.motorPolePairs);
                o.put("sysProTemp", settings.sysProTemp);
                o.put("assistSpeedLimit", settings.assistSpeedLimit);   // 55 71 t[10] (per-gear)
                o.put("fStartLevel", settings.fStartLevel);             // 55 71 t[8] low nibble
                o.put("rStartLevel", settings.rStartLevel);             // 55 71 t[9] low nibble
                o.put("eabsLevel", settings.eabsLevel);                 // 55 71 t[9] high nibble
                o.put("fCurrentLimit", settings.fCurrent);              // 55 71 t[12] per-gear front current limit
                o.put("rCurrentLimit", settings.rCurrent);              // 55 71 t[13] per-gear rear current limit
                o.put("sleepTime", settings.sleepTime);
                o.put("prTime", settings.prTime);
                o.put("cruise", settings.cruise);
                o.put("abs", settings.abs);
                o.put("startMode", settings.startMode);
                o.put("enfEcon", settings.enfEcon);
                o.put("isUnitMile", settings.isUnitMile);
                o.put("atMode", settings.atMode);
                o.put("isSmart", settings.isSmart);
            }

            // Active errors (55 54) + ECU fault bits (BLE_PROTOCOL §2.3/§2.4) for the error-report view.
            o.put("errors", buildErrors());
            o.put("brakeFault", flag(ecuStatus1, 0));
            o.put("tailLightWarning", flag(ecuStatus1, 2));
            o.put("warning", flag(ecuStatus1, 3));
            o.put("warning2", flag(ecuStatus1, 4));
            o.put("park", flag(ecuStatus1, 7) || flag(ecuStatus2, 7));
            o.put("cruiseActive", flag(ecuStatus2, 0));

            // Short aliases consumed by telemetry.html parseBLE() (kept identical)
            o.put("speed", round1(realSpeed));
            o.put("soc", SOC);
            o.put("volt", round1(VolPack));
            o.put("poleVolt", round1(poleVol));
            o.put("current", round1(packCurrent));
            o.put("rCurrent", round1(rMotorCurrent));
            o.put("fCurrent", round1(fMotorCurrent));
            o.put("rTemp", rMotorTemp);
            o.put("fTemp", fMotorTemp);
            o.put("fb", round1(enFeedBack));
            o.put("rssi", rssi);
            o.put("btName", btName);

            // Battery detail table
            o.put("top", buildTop());
            o.put("bottom", buildBottom());

            // Identity (optional)
            if (!batCode.isEmpty()) o.put("batCode", batCode);
            if (!frameNum.isEmpty()) o.put("frameNum", frameNum);
            if (!swVer.isEmpty()) o.put("swVer", swVer);
            if (!hwVer.isEmpty()) o.put("hwVer", hwVer);
            if (!displayVer.isEmpty()) o.put("displayVer", displayVer);

            o.put("ts", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /**
     * Battery / BMS block for the dashboard's "All values" view (renderAllValues iterates top[]
     * then bottom[]). Labels mirror the original app's battery detail view (battery.* / control.* /
     * bat.* i18n, English). Values are pre-formatted strings with inline units.
     */
    private JSONArray buildTop() {
        JSONArray a = new JSONArray();
        if (have52) {
            a.put(nv("System Voltage", round1(VolPack) + " V"));   // 55 52 t[2..3]
            a.put(nv("Pole Voltage", round1(poleVol) + " V"));     // 55 52 t[4..5]
            a.put(nv("Current", round1(packCurrent) + " A"));      // 55 52 t[6..7] (<0 = regen)
            a.put(nv("SOC", SOC + " %"));                          // 55 52 t[8]
            a.put(nv("SOH", soh + " %"));                          // 55 52 t[9]
            a.put(nv("CELL MAX TEMP", maxCellTemp + " °C"));       // 55 52 t[17]
            a.put(nv("CELL MIN TEMP", minCellTemp + " °C"));       // 55 52 t[18]
        }
        if (have53) {
            a.put(nv("Cell Count", String.valueOf(volListLength)));    // 55 53 t[14]
            a.put(nv("Max Voltage", maxCellV + " mV"));                // 55 53 t[15..16]
            a.put(nv("Min Voltage", minCellV + " mV"));                // 55 53 t[17..18]
            a.put(nv("Rated Capacity", capacity + " Ah"));             // 55 53 t[8..9] / t[10..11]
            a.put(nv("Charge Counter", String.valueOf(chargeCounter)));// 55 53 t[12..13]
            a.put(nv("Charge MOS", onOff(chrMosState)));               // 55 53 t[5]
            a.put(nv("Discharge MOS", onOff(dischrMosState)));         // 55 53 t[6]
            a.put(nv("Relay 1", onOff(relay1)));                       // 55 53 t[2]
            a.put(nv("Relay 2", onOff(relay2)));                       // 55 53 t[3]
            a.put(nv("Relay M", onOff(relay3)));                       // 55 53 t[4]
            a.put(nv("Balance", activeBits(balState0)));               // 55 53 t[7]
        }
        if (have54) {
            a.put(nv("Charge Status", String.valueOf(chargeStatus))); // 55 54 t[17]
        }
        return a;
    }

    /**
     * Temperatures, controller/motor, decoded status flags and ride block. Concatenated after
     * top[] by renderAllValues, so together they form the full "all values" list.
     */
    private JSONArray buildBottom() {
        JSONArray a = new JSONArray();
        if (have52) {
            // 55 52 t[10..16] - the 7 pack temperatures with the original app's distinct labels.
            a.put(nv("Cell TEMP0", (int) batTemp[0] + " °C"));     // t[10]
            a.put(nv("Cell TEMP1", (int) batTemp[1] + " °C"));     // t[11]
            a.put(nv("Cell TEMP2", (int) batTemp[2] + " °C"));     // t[12]
            a.put(nv("MOS TEMP0", (int) batTemp[3] + " °C"));      // t[13]
            a.put(nv("MOS TEMP1", (int) batTemp[4] + " °C"));      // t[14]
            a.put(nv("BMS PCB TEMP0", (int) batTemp[5] + " °C"));  // t[15]
            a.put(nv("BMS PCB TEMP1", (int) batTemp[6] + " °C"));  // t[16]
        }
        if (have72) {
            a.put(nv("Front Motor Current", round1(fMotorCurrent) + " A"));  // 55 72 t[4..5]
            a.put(nv("Rear Motor Current", round1(rMotorCurrent) + " A"));   // 55 72 t[12..13]
            a.put(nv("Front Motor Temp", fMotorTemp + " °C"));               // 55 72 t[9]
            a.put(nv("Rear Motor Temp", rMotorTemp + " °C"));                // 55 72 t[17]
            a.put(nv("Motor Mode", motorModeText(rmStatus, doubleMotor)));   // 55 72 t[3]/t[11]
            a.put(nv("Power", round2(power) + " kW"));                       // derived
            a.put(nv("Real Speed", round1(realSpeed) + (settings.isUnitMile ? " mph" : " km/h")));
        }
        if (settings.received71) {
            a.put(nv("Reverse Gear", yesNo(flag(systemStatus, 5))));       // 55 71 t[17] bit5
            a.put(nv("Rear Control Status", activeBits(rControlStatus)));  // 55 71 t[4]
            a.put(nv("Front Control Status", activeBits(fControlStatus))); // 55 71 t[16]
        }
        if (have72) {
            // Decoded ECU status flags (55 72 t[10]=ecuStatus1, t[11]=ecuStatus2).
            a.put(nv("Brake Fault", yesNo(flag(ecuStatus1, 0))));                          // ecuStatus1[0]
            a.put(nv("Warning", yesNo(flag(ecuStatus1, 3))));                              // ecuStatus1[3]
            a.put(nv("Warning 2", yesNo(flag(ecuStatus1, 4))));                            // ecuStatus1[4]
            a.put(nv("Tail-light Warning", yesNo(flag(ecuStatus1, 2))));                   // ecuStatus1[2]
            a.put(nv("Park", yesNo(flag(ecuStatus1, 7) || flag(ecuStatus2, 7))));          // ecuStatus1[7]/ecuStatus2[7]
            a.put(nv("Cruise Active", yesNo(flag(ecuStatus2, 0))));                        // ecuStatus2[0]
            a.put(nv("Headlight", onOff(flag(ecuStatus2, 4))));                            // ecuStatus2[4]
            a.put(nv("Turn Signal L", onOff(flag(ecuStatus2, 5))));                        // ecuStatus2[5]
            a.put(nv("Turn Signal R", onOff(flag(ecuStatus2, 6))));                        // ecuStatus2[6]
            a.put(nv("Front ECU 1", activeBits(fEcuStatus1)));                             // 55 72 t[2]
            a.put(nv("Front ECU 2", activeBits(fEcuStatus2)));                             // 55 72 t[3]
            a.put(nv("System Status 3", activeBits(systemStatus3)));                       // 55 72 t[18]
        }
        if (have73) {
            a.put(nv("Battery Lock", yesNo(flag(systemStatus2, 0))));                      // 55 73 t[16][0]
            a.put(nv("GPS Lock", yesNo(flag(systemStatus2, 1))));                          // 55 73 t[16][1]
            a.put(nv("Power Mode", onOff(flag(status73, 3))));                             // 55 73 t[18][3]
            a.put(nv("Light Sensor", onOff(flag(status73, 4))));                           // 55 73 t[18][4]
            a.put(nv("Voice", onOff(flag(status73, 5))));                                  // 55 73 t[18][5]
            a.put(nv("AT Level", String.valueOf((status73[7] << 1) | status73[6])));       // 55 73 t[18] bits7,6
            a.put(nv("Custom Key", customKeyText(customKey)));                             // 55 73 t[17]
            a.put(nv("Avg Speed", round1(avgSpeed) + " km/h"));                            // 55 73 t[2..3]
            a.put(nv("Max Speed", round1(maxSpeed) + " km/h"));                            // 55 73 t[4..5]
            a.put(nv("Trip", round1(singleMile) + " km"));                                 // 55 73 t[6..7]
            a.put(nv("Odometer", round1(totalMile) + " km"));                              // 55 73 t[8..10]
            a.put(nv("Recuperation", String.valueOf(round1(enFeedBack))));    // 55 73 t[11..12]
        }
        return a;
    }

    /** Nonzero 55 54 entries as {code, severity}; code = error type index, severity = raw byte. */
    private JSONArray buildErrors() {
        JSONArray a = new JSONArray();
        for (int i = 0; i < errors.length; i++) {
            if (errors[i] > 0) {
                JSONObject e = new JSONObject();
                try {
                    e.put("code", i);
                    e.put("severity", errors[i]);
                } catch (JSONException ignored) {
                }
                a.put(e);
            }
        }
        return a;
    }

    private static boolean flag(int[] arr, int i) {
        return arr != null && i >= 0 && i < arr.length && arr[i] == 1;
    }

    private static String onOff(int v) { return v != 0 ? "On" : "Off"; }

    private static String onOff(boolean v) { return v ? "On" : "Off"; }

    private static String yesNo(boolean v) { return v ? "Yes" : "No"; }

    /** 1-based indices of the set bits in an LSB-first bit array, or "none". */
    private static String activeBits(int[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (b[i] == 1) { if (sb.length() > 0) sb.append(","); sb.append(i + 1); }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String cruiseText(int level) {
        switch (level) {
            case 1: return "Auto";
            case 2: return "Manual";
            default: return "Off";
        }
    }

    private static String motorModeText(int rm, int dm) {
        if (rm == 1 && dm == 1) return "Dual";
        if (rm == 1 && dm == 0) return "Rear only";
        if (rm == 0 && dm == 1) return "Front only";
        return "-";
    }

    /** Custom-key function names (BLE_PROTOCOL §3.5). */
    private static String customKeyText(int v) {
        switch (v) {
            case 1: return "Motor";
            case 2: return "Kick Start";
            case 3: return "Auto Cruise";
            case 4: return "Limit Speed";
            case 5: return "Lock Scooter";
            case 6: return "TCS";
            case 7: return "Led Switch";
            case 8: return "Led Mode Switch";
            case 9: return "Turbo";
            case 10: return "Manual Cruise";
            case 11: return "EABS";
            default: return String.valueOf(v);
        }
    }

    private static JSONObject nv(String name, String value) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("value", value);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static JSONArray intArr(int[] v) {
        JSONArray a = new JSONArray();
        for (int x : v) a.put(x);
        return a;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
