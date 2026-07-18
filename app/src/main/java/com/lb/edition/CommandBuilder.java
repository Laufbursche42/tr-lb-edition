// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

/**
 * Builds the 20-byte outgoing command frames (phone -> VCU) for the Teverun UART-over-BLE
 * protocol, exactly as the original uni-app bundle does (see BLE_PROTOCOL.md sections 3 & 4).
 *
 * Frame layout: [0]=0xAA header, [1]=cmdId, [2..18]=17 payload bytes (default 0xFF), [19]=CRC-8.
 *
 * CRC-8: polynomial 0x07, init 0x00, MSB-first, no reflection, no final XOR, computed over the
 * first 19 bytes. IMPORTANT: the original masks the accumulator with 0xFF only ONCE per input
 * byte (after the inner 8-shift loop), NOT on every shift. That is faithfully reproduced here
 * with 32-bit int math; a textbook per-iteration mask would produce different CRCs and every
 * frame would be rejected by the controller.
 */
final class CommandBuilder {

    private CommandBuilder() {}

    // ── CRC-8 (poly 0x07) - replicates getCrcCode() from the bundle exactly ──

    static int crc8(int[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int n = 8; n > 0; n--) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x07) : (crc << 1);
            }
            crc &= 0xFF;
        }
        return crc & 0xFF;
    }

    static int crc8(byte[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int n = 8; n > 0; n--) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x07) : (crc << 1);
            }
            crc &= 0xFF;
        }
        return crc & 0xFF;
    }

    // ── CRC-16 / MODBUS (poly 0xA001, init 0xFFFF, reflected) - firmware-image integrity ──

    /**
     * CRC-16/MODBUS over {@code data[0..len-1]}: polynomial 0xA001 (reflected 0x8005), init 0xFFFF,
     * refin/refout true, no final XOR. Replicates the original app's getCrc()/CRC16() from the
     * OTA bundle EXACTLY (utils/upgrade.js). Returned as a 0..0xFFFF int; the OTA INFO frame and the
     * file-trailer integrity check transmit it big-endian (hi = (crc>>8)&0xFF, lo = crc&0xFF).
     */
    static int crc16Modbus(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int n = 0; n < 8; n++) {
                crc = ((crc & 1) != 0) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1);
            }
        }
        return crc & 0xFFFF;
    }

    // ── Bit helpers (as documented, BLE_PROTOCOL §3.4 / §5) ──

    /** LSB-first: index 0 = bit0. value = sum(bits[i] << i). */
    static int bytesToInt(int[] bits) {
        int v = 0;
        for (int i = 0; i < bits.length; i++) {
            if ((bits[i] & 1) != 0) v |= (1 << i);
        }
        return v & 0xFF;
    }

    /** MSB-first: index 0 = most-significant bit. value = sum(bits[i] << (len-1-i)). */
    static int bytesToInt2(int[] bits) {
        int v = 0;
        int n = bits.length;
        for (int i = 0; i < n; i++) {
            if ((bits[i] & 1) != 0) v |= (1 << (n - 1 - i));
        }
        return v & 0xFF;
    }

    // ── Frame assembly ──

    /** Copy the 19 payload/header ints into a 20-byte frame and append the CRC-8 at [19]. */
    private static byte[] finalizeFrame(int[] a19) {
        byte[] out = new byte[20];
        for (int i = 0; i < 19; i++) out[i] = (byte) (a19[i] & 0xFF);
        out[19] = (byte) crc8(a19, 19);
        return out;
    }

    /** Base frame [170, cmdId, 0xFF x17]. */
    private static int[] base(int cmdId) {
        int[] a = new int[19];
        a[0] = 170;
        a[1] = cmdId & 0xFF;
        for (int i = 2; i < 19; i++) a[i] = 0xFF;
        return a;
    }

    // ── setControlCode(cmdId, overrides) - generic control builder (§3.2) ──

    /** overrides: pairs {index, value}. */
    static byte[] setControlCode(int cmdId, int[][] overrides) {
        int[] a = base(cmdId);
        if (overrides != null) {
            for (int[] o : overrides) {
                if (o != null && o.length >= 2 && o[0] >= 0 && o[0] < 19) {
                    a[o[0]] = o[1] & 0xFF;
                }
            }
        }
        return finalizeFrame(a);
    }

    // ── sendConnectCode(e) - handshake / keep-alive (§3.3): AA 01 10 <e> FF..FF CRC ──

    static byte[] sendConnectCode(int e) {
        int[] a = base(1);   // cmdId 0x01
        a[2] = 0x10;         // 16
        a[3] = e & 0xFF;
        // a[4..7] stay 0xFF (params)
        return finalizeFrame(a);
    }

    // ── setCustomKey - setControlCode(0x1A, [{6, value}]) (§3.5) ──

    static byte[] setCustomKey(int value) {
        return setControlCode(0x1A, new int[][]{{6, value & 0xFF}});
    }

    // ── setDeviceName - cmd 0x1f: set the VCU identity / BLE advertised name (AT+LENAME) ──

    /**
     * Build a cmd-0x1f frame that sets the VCU device-identity string (a.k.a. the BLE advertised
     * name / FIN). The VCU handler reads 16 name bytes from the payload (a[2..17]), copies them to
     * the identity in RAM, persists them to the EEPROM and pushes them to the BLE module via
     * AT+LENAME. The identity's first three characters gate the eKFV speed clamp ("TDE" = limited),
     * so setting an identity whose first three chars are not "TDE" turns that gate off. Reversible.
     *
     * <p>The name is written as ASCII into a[2..17]; unused trailing name bytes are space-padded
     * (the firmware space-pads the identity to 19 bytes). Non-ASCII input is dropped. The first
     * character must be printable (VCU handler validates a[2] in 0x30..0x7A).
     *
     * @param name up to 16 ASCII chars; longer input is truncated to 16.
     */
    static byte[] setDeviceName(String name) {
        int[] a = base(0x1F);
        byte[] ascii;
        try {
            ascii = (name == null ? "" : name).getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            ascii = new byte[0];
        }
        for (int i = 0; i < 16; i++) {
            a[2 + i] = (i < ascii.length) ? (ascii[i] & 0xFF) : 0x20;   // space-pad the rest
        }
        // a[18] stays 0xFF (base default): the VCU reads only the 16 name bytes a[2..17].
        return finalizeFrame(a);
    }

    // ── Voltage code a[14] derived from packVolt (§3.4) ──

    static int voltCode(int packVolt) {
        switch (packVolt) {
            case 36: return 30;
            case 48: return 39;
            case 52: return 42;
            case 60: return 48;
            case 72: return 60;
            case 84: return 69;
            default: return packVolt & 0xFF; // fallback: pass through
        }
    }

    // ── sendSettingCode - full settings write, cmd 0x18 (§3.4) ──

    /**
     * @param s current maintained settings state (never null)
     * @param n write mode: 0 = normal, 2 = immediate (motor toggle / charge)
     * @param r when n==2, a[3] is overwritten with this assist-level index (1 for a single frame)
     */
    static byte[] sendSettingCode(SettingsState s, int n, int r) {
        int gearByte = (n == 2) ? (r & 0xFF) : (s.gear & 0xFF);
        // Normal full write carries the CURRENT gear's maintained per-gear/assist values.
        return buildSettingFrame(s, n, gearByte,
                s.eabsLevel, s.fStartLevel, s.rStartLevel,
                s.assistSpeedLimit, s.fCurrent, s.rCurrent);
    }

    /**
     * sendGearSetting - write ONE gear/assist profile (cmd 0x18, BLE_PROTOCOL §3.4).
     *
     * <p>Mirrors the original app's per-gear write: {@code a[3]} = the gear/assist index,
     * {@code a[10]} = that gear's speed limit, {@code a[8]}/{@code a[9]} = its assist nibbles
     * (high = eabsLevel, low = fStartLevel / rStartLevel), {@code a[12]}/{@code a[13]} = its
     * front/rear current limits. Every OTHER config byte (control flags, wheel, voltage, timers,
     * main speed limit, motor mode …) is taken from the maintained current-state {@code s} so the
     * frame stays valid. Write mode {@code n = 0} (normal).
     *
     * @param s            maintained settings state for the unchanged config bytes (never null)
     * @param gear         gear / assist index written into a[3]
     * @param perGearSpeed a[10] per-gear speed limit
     * @param eabsLevel    a[8]/a[9] high nibble (EABS / recuperation level)
     * @param fStartLevel  a[8] low nibble (front start level)
     * @param rStartLevel  a[9] low nibble (rear start level)
     * @param fCurrent     a[12] front current limit
     * @param rCurrent     a[13] rear current limit
     */
    static byte[] sendGearSetting(SettingsState s, int gear, int perGearSpeed, int eabsLevel,
                                  int fStartLevel, int rStartLevel, int fCurrent, int rCurrent) {
        // Per-gear write uses mode a[2]=2 with the target gear in a[3] (r=gear), matching the
        // original app's per-gear save (sendSettingCode(state, 2, gear)).
        return buildSettingFrame(s, 2, gear & 0xFF,
                eabsLevel, fStartLevel, rStartLevel, perGearSpeed, fCurrent, rCurrent);
    }

    /**
     * Assemble a full 0x18 settings frame. All the shared/global config bytes come from {@code s};
     * the per-gear/assist bytes (a[3], a[8], a[9], a[10], a[12], a[13]) come from the explicit
     * arguments so both the full write (current gear) and the per-gear write can reuse this.
     */
    private static byte[] buildSettingFrame(SettingsState s, int n, int gearByte,
                                            int eabsLevel, int fStartLevel, int rStartLevel,
                                            int perGearSpeed, int fCurrent, int rCurrent) {
        int[] a = new int[19];
        a[0] = 170;
        a[1] = 24;               // 0x18
        a[2] = n & 0xFF;
        a[3] = gearByte & 0xFF;

        // a[4] rControlStatus byte (bytesToInt, LSB-first); bit7 = rmStatus
        int[] s4 = new int[8];
        applyCruise(s4, s.cruise);
        s4[3] = s.abs ? 1 : 0;
        s4[6] = s.startMode ? 1 : 0;
        s4[7] = s.rmStatus & 1;
        a[4] = bytesToInt(s4);

        a[5] = s.motorPolePairs & 0xFF;
        a[6] = (int) Math.round(s.wheel * 10.0) & 0xFF;   // wheel * 10
        a[7] = s.sysProTemp & 0xFF;

        // a[8]/a[9] assist nibble bytes (bytesToInt2, MSB-first): high nibble = eabsLevel,
        // low nibble = fStartLevel / rStartLevel.
        a[8] = bytesToInt2(nibbles(eabsLevel, fStartLevel));
        a[9] = bytesToInt2(nibbles(eabsLevel, rStartLevel));

        a[10] = perGearSpeed & 0xFF;         // per-gear / assist speed limit
        a[11] = s.speedLimit & 0xFF;         // main speed limit
        a[12] = fCurrent & 0xFF;
        a[13] = rCurrent & 0xFF;
        a[14] = voltCode(s.packVolt);
        a[15] = s.packVolt & 0xFF;

        // a[16] flag byte (bytesToInt, LSB-first)
        int[] d = new int[8];
        d[0] = s.enfEcon ? 1 : 0;
        d[1] = s.isUnitMile ? 1 : 0;
        d[2] = s.atMode ? 1 : 0;
        d[4] = s.isSmart ? 1 : 0;
        a[16] = bytesToInt(d);

        // a[17] fControlStatus byte (bytesToInt, LSB-first); bit7 = doubleMotor
        int[] s17 = new int[8];
        applyCruise(s17, s.cruise);
        s17[3] = s.abs ? 1 : 0;
        s17[6] = s.startMode ? 1 : 0;
        s17[7] = s.doubleMotor & 1;
        a[17] = bytesToInt(s17);

        a[18] = ((s.prTime & 0x1F) << 3) | (s.sleepTime & 0x07);

        return finalizeFrame(a);
    }

    /** cruise==2 (manual) -> bit2; cruise==1 (auto) -> bit0 & bit1; else none. (matches original app) */
    private static void applyCruise(int[] bits, int cruise) {
        if (cruise == 2) {
            bits[2] = 1;
        } else if (cruise == 1) {
            bits[0] = 1;
            bits[1] = 1;
        }
    }

    /** Build an 8-bit MSB-first array whose high nibble = high, low nibble = low. */
    private static int[] nibbles(int high, int low) {
        int[] bits = new int[8];
        for (int k = 0; k < 4; k++) bits[k] = (high >> (3 - k)) & 1;
        for (int k = 0; k < 4; k++) bits[4 + k] = (low >> (3 - k)) & 1;
        return bits;
    }
}
