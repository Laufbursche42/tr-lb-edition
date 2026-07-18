package com.lb.edition;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Native port of the Spooonky "web patcher" core (github.com/Spooonky, patcher-core), which is
 * verified on real hardware (DAP-Link, 2026-06-12). Patches Teverun VCU firmware in place and
 * rebuilds the Intel-HEX so {@link OtaEngine} can flash it. The patch addresses/bytes and the
 * combo logic are taken byte-for-byte from the patcher-core profiles ({@code r5_4_19.json},
 * {@code d5_4_14_11.json}) and {@code r5_4_19_features.py}; the ALI path mirrors {@code bin_to_hex.py}.
 *
 * Everything is expect-checked before it is written, so the wrong firmware (or an already patched
 * one) can never be silently corrupted.
 */
final class FirmwarePatcher {

    private static final int FLASH_BASE = 0x08007000;
    private static final int DEFAULT_START_ADDR = 0x08007149;

    /** address -> byte value (0..255), sorted. */
    private final TreeMap<Integer, Integer> mem = new TreeMap<>();
    private int startAddr = DEFAULT_START_ADDR;
    private int v0 = 0x05, v1 = 0x04, v2 = 0x0E; // :07AAA555 version marker

    private FirmwarePatcher() {}

    // ─────────────────────────── loaders ───────────────────────────

    /** Parse an Intel-HEX (04 ELA / 00 data / 05 start / :07AAA555 trailer). */
    static FirmwarePatcher fromHex(String text) {
        FirmwarePatcher f = new FirmwarePatcher();
        int curHi = 0;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) != ':') continue;
            int ll = h(line, 1, 3), addr = h(line, 3, 7), tt = h(line, 7, 9);
            if (tt == 0x04) {
                curHi = h(line, 9, 13);
            } else if (tt == 0x00) {
                int base = (curHi << 16) | addr;
                for (int i = 0; i < ll; i++) f.mem.put(base + i, h(line, 9 + 2 * i, 11 + 2 * i));
            } else if (tt == 0x05 && line.length() >= 17) {
                f.startAddr = (int) Long.parseLong(line.substring(9, 17), 16);
            }
            if (line.length() >= 25 && "07AAA555".equalsIgnoreCase(line.substring(1, 9))) {
                f.v0 = h(line, 13, 15);
                f.v1 = h(line, 15, 17);
                f.v2 = h(line, 17, 19);
            }
        }
        if (f.mem.isEmpty()) throw new RuntimeException("empty / invalid hex");
        return f;
    }

    /**
     * ALI path: a raw chip dump (bootloader + app). Strips the 0x7000 bootloader, trims the
     * trailing 0xFF padding (isolated sector marker behind a >=0x400 gap is dropped) and keeps the
     * app from 0x08007000. No patches - just format conversion (mirrors bin_to_hex.extract_app).
     */
    static FirmwarePatcher fromAliDump(byte[] dump) {
        final int BOOT = 0x7000, GAP = 0x400;
        if (dump.length <= BOOT) throw new RuntimeException("dump too small - no app region");
        int n = dump.length - BOOT;
        int end = 0, i = 0;
        while (i < n) {
            if ((dump[BOOT + i] & 0xFF) != 0xFF) { end = i + 1; i++; continue; }
            int j = i;
            while (j < n && (dump[BOOT + j] & 0xFF) == 0xFF) j++;
            if (j - i >= GAP) break;
            i = j;
        }
        FirmwarePatcher f = new FirmwarePatcher();
        f.startAddr = DEFAULT_START_ADDR;
        for (int k = 0; k < end; k++) f.mem.put(FLASH_BASE + k, dump[BOOT + k] & 0xFF);
        return f;
    }

    // ─────────────────────────── byte access ───────────────────────────

    private int rd(int addr) {
        Integer v = mem.get(addr);
        if (v == null) throw new RuntimeException(String.format("addr 0x%08X not present", addr));
        return v;
    }

    private void wr(int addr, int val) { mem.put(addr, val & 0xFF); }

    /** expect-checked multi-byte patch (mirrors patcher-core _wr / the profile 'expected' check). */
    private void patch(int addr, int[] oldB, int[] newB) {
        for (int i = 0; i < oldB.length; i++) {
            int cur = rd(addr + i);
            if (cur != oldB[i]) {
                throw new RuntimeException(String.format(
                        "@0x%08X expected 0x%02X but found 0x%02X (wrong firmware or already patched)",
                        addr + i, oldB[i], cur));
            }
        }
        for (int i = 0; i < newB.length; i++) wr(addr + i, newB[i]);
    }

    // ─────────────────────────── R5.4.19 patches ───────────────────────────
    // Per builder: {cbz, bit5_orr, clamp_region, movs}.  (r5_4_19_features._BUILDERS)
    private static final int[][] R5_BUILDERS = {
            {0x08010052, 0x08010054, 0x08010058, 0x0801005C},
            {0x08010202, 0x08010204, 0x08010208, 0x0801020C},
            {0x080103AA, 0x080103AC, 0x080103B0, 0x080103B4},
            {0x080105CA, 0x080105CC, 0x080105D0, 0x080105D4},
    };
    private static final int[] CBZ_OLD  = {0x28, 0xB1}, CBZ_NOP  = {0x00, 0xBF};
    private static final int[] BIT5_OLD = {0x46},       BIT5_NEW = {0x26};
    private static final int[] CLAMP_OLD  = {0x16, 0x2F, 0x03, 0xDD, 0x16, 0x27};
    private static final int[] CLAMP_HALF = {0x7F, 0x08, 0x00, 0xBF, 0x00, 0xBF};
    private static final int[] MOVS_OLD = {0x16, 0x27}, MOVS_NOP = {0x00, 0xBF};
    private static final int[] GATE_FLAG_OLD = {0x72, 0x48}, GATE_FLAG_NEW = {0x0E, 0xE0}; // @0x0800F99C
    private static final int[] GATE2_OLD = {0x01, 0x20}, GATE2_NEW = {0x00, 0x20};         // @0x0800F9C8
    private static final int[] BLINKER_OLD = {0xFF, 0xF7, 0x90, 0xFF}, BLINKER_NEW = {0x00, 0xBF, 0x00, 0xBF};

    /**
     * R5.4.19 driving-feature combo (Speed / ZeroStart / Cruise), byte-exact from
     * r5_4_19_features.apply_features. The three share the builder byte region, so the result
     * depends on the combination.
     */
    void applyR519Features(boolean speedRemove, boolean zerostart, boolean cruise) {
        if (cruise) {
            // Flag 0x2000030C -> 0 (skip TDE gate + display-bit branch writes 0): sync block clean.
            patch(0x0800F99C, GATE_FLAG_OLD, GATE_FLAG_NEW);
            patch(0x0800F9C8, GATE2_OLD, GATE2_NEW);
            if (!speedRemove) {
                for (int[] b : R5_BUILDERS) {
                    patch(b[0], CBZ_OLD, CBZ_NOP);
                    patch(b[1], BIT5_OLD, BIT5_NEW);
                    patch(b[2], CLAMP_OLD, CLAMP_HALF);
                }
            }
        } else if (zerostart) {
            for (int[] b : R5_BUILDERS) {
                patch(b[1], BIT5_OLD, BIT5_NEW);
                if (speedRemove) patch(b[3], MOVS_OLD, MOVS_NOP);
                else patch(b[2], CLAMP_OLD, CLAMP_HALF);
            }
        } else if (speedRemove) {
            for (int[] b : R5_BUILDERS) patch(b[3], MOVS_OLD, MOVS_NOP);
        }
    }

    /** "Live toggle" speed mode: only Gate 2 (display clamp bit) removed, so the FIN identity stays
     *  the live switch (FIN=TDE -> 22, FIN without TDE -> open). This is our existing patched hex. */
    void applyR519LiveToggle() { patch(0x0800F9C8, GATE2_OLD, GATE2_NEW); }

    /** Blinker-Fix (R5.4.19 only): the extra PB5_RESET @0x08019610 that kills the blink -> NOP. */
    void applyR519Blinker() { patch(0x08019610, BLINKER_OLD, BLINKER_NEW); }

    // ─────────────────────────── D5.4.14 patches ───────────────────────────
    /** D5.4.14 speed: NOP the 4 output-clamps so the natural (ALI-like) value is sent. */
    void applyD5Speed() {
        patch(0x0800FEEA, new int[]{0x4F, 0xF0, 0x16, 0x08}, new int[]{0xAF, 0xF3, 0x00, 0x80}); // MOV.W -> NOP.W
        patch(0x0801011A, new int[]{0x4F, 0xF0, 0x16, 0x08}, new int[]{0xAF, 0xF3, 0x00, 0x80});
        patch(0x080102B0, MOVS_OLD, MOVS_NOP); // movs r7,#0x16 -> nop
        patch(0x080104B6, MOVS_OLD, MOVS_NOP);
    }

    // ─────────────────────── WheelDiameter (R5.4.19, code cave) ───────────────────────
    private static final int WD_HOOK = 0x0800F8EE;
    private static final int[] WD_HOOK_OLD = {0x8C, 0x48, 0x40, 0x7A}; // ldr r0,[pc,#0x230]; ldrb r0,[r0,#9]
    private static final int WD_RET = 0x0800F8F2, WD_SAVE = 0x0801700C;
    private static final int[] WD_PREFIX = u("09490a7a09490b780a709a4208d00849ca702de90c500120");
    private static final int[] WD_MID    = u("bde80c500149487a");
    private static final int[] WD_LITERALS = u("4d1300209d020020281a0020"); // 0x2000134D, 0x2000029D, 0x20001A28

    /** Makes the display WheelDiameter persist (R5.4.19). Appends a code cave at the app end and
     *  hooks the sync-block convergence point. Byte-exact port of wheel_diameter.apply. */
    void applyWheelDiameter() {
        for (int i = 0; i < WD_HOOK_OLD.length; i++) {
            if (rd(WD_HOOK + i) != WD_HOOK_OLD[i])
                throw new RuntimeException("WheelDiameter: hook mismatch (only R5.4.19).");
        }
        int preMax = mem.lastKey();
        int[] footer = new int[8];
        for (int i = 0; i < 8; i++) footer[i] = rd(preMax - 7 + i);

        int appEnd = preMax + 1;
        int cave = (appEnd + 3) & ~3;
        for (int a = appEnd; a < cave; a++) wr(a, 0xFF);
        int[] caveBytes = buildWdCave(cave);
        for (int i = 0; i < caveBytes.length; i++) wr(cave + i, caveBytes[i]);

        int[] hookBranch = encBranch(WD_HOOK, cave, false);
        for (int i = 0; i < hookBranch.length; i++) wr(WD_HOOK + i, hookBranch[i]);

        int tail = mem.lastKey() + 1;
        int pad = (4 - (tail + 8 - FLASH_BASE) % 4) % 4;
        for (int i = 0; i < pad; i++) wr(tail + i, 0xFF);
        int footerAddr = tail + pad;
        for (int i = 0; i < footer.length; i++) wr(footerAddr + i, footer[i]);
    }

    private int[] buildWdCave(int cave) {
        int[] out = new int[0x34];
        int p = 0;
        p = put(out, p, WD_PREFIX);                       // +0x00
        p = put(out, p, encBranch(cave + 0x18, WD_SAVE, true)); // +0x18 bl
        p = put(out, p, WD_MID);                          // +0x1C
        p = put(out, p, encBranch(cave + 0x24, WD_RET, false)); // +0x24 b.w
        p = put(out, p, WD_LITERALS);                     // +0x28
        if (p != 0x34) throw new RuntimeException("cave size " + p);
        return out;
    }

    /** Thumb-2 B.W (link=false) / BL (link=true), 4 bytes little-endian. */
    private static int[] encBranch(int pc, int target, boolean link) {
        int imm = target - (pc + 4);
        int s = (imm >> 24) & 1, i1 = (imm >> 23) & 1, i2 = (imm >> 22) & 1;
        int imm10 = (imm >> 12) & 0x3FF, imm11 = (imm >> 1) & 0x7FF;
        int j1 = 1 - (i1 ^ s), j2 = 1 - (i2 ^ s);
        int hw1 = 0xF000 | (s << 10) | imm10;
        int hw2 = (link ? 0xD000 : 0x9000) | (j1 << 13) | (j2 << 11) | imm11;
        return new int[]{hw1 & 0xFF, (hw1 >> 8) & 0xFF, hw2 & 0xFF, (hw2 >> 8) & 0xFF};
    }

    // ─────────────────────────── HEX output ───────────────────────────

    /** Rebuild the flashable Intel-HEX (04 ELA per 64K, 00 data 16B, 05 start, EOF, :07AAA555 CRC16). */
    String buildHex() {
        StringBuilder sb = new StringBuilder();
        ByteArrayOutputStream app = new ByteArrayOutputStream();
        Integer[] addrs = mem.keySet().toArray(new Integer[0]); // sorted
        int curHi = -1, i = 0;
        while (i < addrs.length) {
            int start = addrs[i], end = start, j = i;
            while (j + 1 < addrs.length && addrs[j + 1] == addrs[j] + 1) { j++; end = addrs[j]; }
            int a = start;
            while (a <= end) {
                int hi = (a >>> 16) & 0xFFFF;
                if (hi != curHi) { sb.append(rec(0x04, 0, new int[]{(hi >> 8) & 0xFF, hi & 0xFF})); curHi = hi; }
                int len = Math.min(16, end - a + 1);
                int lineHi = ((a + len - 1) >>> 16) & 0xFFFF;
                if (lineHi != hi) len = ((hi + 1) << 16) - a; // never let a record cross a 64K boundary
                int[] data = new int[len];
                for (int k = 0; k < len; k++) { data[k] = mem.get(a + k); app.write(data[k]); }
                sb.append(rec(0x00, a & 0xFFFF, data));
                a += len;
            }
            i = j + 1;
        }
        sb.append(rec(0x05, 0, new int[]{(startAddr >>> 24) & 0xFF, (startAddr >>> 16) & 0xFF,
                (startAddr >>> 8) & 0xFF, startAddr & 0xFF}));
        sb.append(":00000001FF\n");
        byte[] appBytes = app.toByteArray();
        int crc = CommandBuilder.crc16Modbus(appBytes, appBytes.length);
        sb.append(propRecord(crc)).append('\n');
        return sb.toString();
    }

    int crc16OfApp() {
        ByteArrayOutputStream app = new ByteArrayOutputStream();
        for (Map.Entry<Integer, Integer> e : mem.entrySet()) app.write(e.getValue());
        byte[] b = app.toByteArray();
        return CommandBuilder.crc16Modbus(b, b.length);
    }

    private String propRecord(int crc) {
        int[] rb = {0x07, 0xAA, 0xA5, 0x55, 0x00, 0x00, v0, v1, v2, (crc >> 8) & 0xFF, crc & 0xFF};
        int sum = 0;
        for (int b : rb) sum += b;
        int chk = sum & 0xFF; // proprietary record uses a plain sum%256 checksum (not two's complement)
        StringBuilder s = new StringBuilder(":");
        for (int b : rb) s.append(String.format("%02X", b));
        return s.append(String.format("%02X", chk)).toString();
    }

    /** Standard Intel-HEX record with the usual two's-complement line checksum. */
    private static String rec(int type, int addr16, int[] data) {
        int[] rb = new int[4 + data.length];
        rb[0] = data.length;
        rb[1] = (addr16 >> 8) & 0xFF;
        rb[2] = addr16 & 0xFF;
        rb[3] = type;
        System.arraycopy(data, 0, rb, 4, data.length);
        int sum = 0;
        for (int b : rb) sum += b;
        int chk = (-sum) & 0xFF;
        StringBuilder s = new StringBuilder(":");
        for (int b : rb) s.append(String.format("%02X", b));
        return s.append(String.format("%02X", chk)).append('\n').toString();
    }

    // ─────────────────────────── small helpers ───────────────────────────
    private static int h(String s, int a, int b) { return Integer.parseInt(s.substring(a, b), 16); }

    private static int put(int[] dst, int pos, int[] src) {
        System.arraycopy(src, 0, dst, pos, src.length);
        return pos + src.length;
    }

    /** hex string -> int[] of bytes. */
    private static int[] u(String hex) {
        int[] out = new int[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
