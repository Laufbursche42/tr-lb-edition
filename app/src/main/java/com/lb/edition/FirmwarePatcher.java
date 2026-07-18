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
    // Clamp flag 0x2000030C = (identity=="TDE") OR (display[6]&4). It gates the speed clamp + bit5 (kick)
    // in the 4 motor builders AND whether the cruise sync-block runs. Flag=0 -> full speed + bit5=0
    // (kickstart released) + cruise. See teverun/R5419_PATCH_MATRIX.md for the full analysis.
    private static final int[] MOVS_OLD = {0x16, 0x27}, MOVS_NOP = {0x00, 0xBF};             // movs r7,#0x16 -> nop (D5 only)
    private static final int[] GATE_FLAG_OLD = {0x72, 0x48}, GATE_FLAG_NEW = {0x0E, 0xE0};   // @0x0800F99C (Gate 1 / TDE identity)
    private static final int[] GATE2_OLD = {0x01, 0x20}, GATE2_NEW = {0x00, 0x20};           // @0x0800F9C8 (Gate 2 / display bit)
    private static final int[] BLINKER_OLD = {0xFF, 0xF7, 0x90, 0xFF}, BLINKER_NEW = {0x00, 0xBF, 0x00, 0xBF};

    /**
     * "Full unlock" speed mode: pin the clamp flag 0x2000030C to 0 (Gate 1 + Gate 2 off). With the flag
     * always 0 the four motor builders take their open branch everywhere: full speed, bit5=0 (kickstart
     * released) AND the cruise sync-block runs clean. So full speed + Kickstart + Cruise all come from
     * this ONE state - the display still toggles Kickstart/Cruise on/off at runtime. There is no separate
     * ZeroStart or Cruise patch: they ARE the unlock. (= patcher-core build_zerostart_full.)
     */
    void applyR519Unlock() {
        patch(0x0800F99C, GATE_FLAG_OLD, GATE_FLAG_NEW);   // Gate 1 (TDE identity) branch away -> flag stays 0
        patch(0x0800F9C8, GATE2_OLD, GATE2_NEW);           // Gate 2 (display bit) -> 0
    }

    /** "Live toggle" speed mode: only Gate 2 (display clamp bit) removed, so the FIN identity stays
     *  the live switch (FIN=TDE -> flag=1 = 22/locked; FIN without TDE -> flag=0 = full + kickstart +
     *  cruise). Same flag=0 state as Full unlock, just switched live by the FIN instead of pinned. */
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
    private static final int WD_HOOK = 0x0800D3B0;     // inside cmd-0x18 sub-cmd-2: the app's wheel = record[4]
    private static final int[] WD_HOOK_OLD = {0x80, 0x79, 0xAE, 0x49}; // ldrb r0,[r0,#6]; ldr r1,[pc,#0x2b8]
    private static final int WD_RET = 0x0800D3B4;      // return past the 2 displaced instructions
    private static final int WD_VAR    = 0x2000029D;   // WheelDiameter RAM var (speed formula 287*WD)
    private static final int WD_MIRROR = 0x20001A28;   // EEPROM settings mirror (WD at +3)
    private static final int WD_ASSIST = 0x200002A5;   // displaced-instr target (record[6] -> here)
    private static final int WD_SAVE   = 0x0801700C;   // native save dispatcher (r0 = block idx; 1 = mirror block)
    // Boot clobber: boot-load reads the persisted wheel into the WD var, then unconditionally overwrites
    // it with 100 (10.0"). NOP that store so the persisted value survives a reboot. (Disasm-verified.)
    private static final int WD_BOOT = 0x0801730A;
    private static final int[] WD_BOOT_OLD = {0x64, 0x20, 0x44, 0x49, 0x08, 0x70}; // movs r0,#0x64; ldr r1,=WD; strb r0,[r1]
    private static final int[] WD_BOOT_NEW = {0x64, 0x20, 0x44, 0x49, 0x00, 0xBF}; // movs r0,#0x64; ldr r1,=WD; nop

    /**
     * Makes the app's "Wheel size" setting take + PERSIST on R5.4.19 (disassembly-verified). The app sends
     * the wheel as cmd 0x18 a[6] = record[4], but R5's 0x18 handler silently DROPS record[4] (it was
     * stripped vs the open ALI build, which keeps it). Two parts, both required:
     *   1. App-path cave (hook 0x0800D3B0, inside sub-cmd 2 where record[4] is available): record[4] ->
     *      WD var 0x2000029D, and when changed mirror 0x20001A28+3 + native save 0x0801700C(1) - commits
     *      to I2C EEPROM with a correct CRC. Cleaner than a display-frame cave: it writes ONLY on the
     *      user's 0x18 command, so it never fights the display's periodic re-broadcast of its own default.
     *   2. Boot-clobber NOP (0x0801730A = WD_BOOT, NOPs the strb at +4): the boot-load reads the persisted wheel from the mirror, then
     *      unconditionally overwrites the WD var with 100 (10.0"). NOP that store so the value survives a
     *      reboot.
     * Cave sits at the app end; the 8-byte end marker is carried along. a[6] and the WD var are the same
     * unit (wheel*10; boot default 100 = 10.0"), so record[4] is copied straight - no scaling.
     */
    void applyWheelDiameter() {
        for (int i = 0; i < WD_HOOK_OLD.length; i++) {
            if (rd(WD_HOOK + i) != WD_HOOK_OLD[i])
                throw new RuntimeException("WheelDiameter: hook mismatch (only R5.4.19).");
        }
        // Part 2: neutralize the boot clobber that resets WD to 100, so the persisted value survives.
        patch(WD_BOOT, WD_BOOT_OLD, WD_BOOT_NEW);
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

    /** 0x30-byte app-path cave: record[4] (the app's cmd-0x18 wheel) -> WD var; if changed, write the
     *  EEPROM mirror (+3) and call the native save (r0=1). Then redo the 2 displaced instructions
     *  (ldrb r0,[r0,#6]; ldr r1,=0x200002A5) and return. Byte layout disassembly-verified. */
    private int[] buildWdCave(int cave) {
        int[] head = {                                            // +0x00..0x13
            0x02, 0x79, 0x08, 0x49, 0x0b, 0x78, 0x0a, 0x70,       // ldrb r2,[r0,#4]; ldr WD_VAR; ldrb r3,[r1]; strb r2,[r1]
            0x9a, 0x42, 0x06, 0xd0, 0x06, 0x49, 0xca, 0x70,       // cmp r2,r3; beq redo; ldr WD_MIRROR; strb r2,[r1,#3]
            0x01, 0xb5, 0x01, 0x20,                               // push {r0,lr}; movs r0,#1
        };
        int[] mid = {                                             // +0x18..0x1D
            0x03, 0xbc,                                           // pop {r0,r1}
            0x80, 0x79, 0x03, 0x49,                               // ldrb r0,[r0,#6]; ldr WD_ASSIST  (redo displaced)
        };
        int[] out = new int[0x30];
        int p = put(out, 0, head);                                // +0x00
        p = put(out, p, encBranch(cave + 0x14, WD_SAVE, true));   // +0x14 bl WD_SAVE
        p = put(out, p, mid);                                     // +0x18
        p = put(out, p, encBranch(cave + 0x1E, WD_RET, false));   // +0x1E b.w WD_RET
        p = put(out, p, new int[]{0x00, 0xBF});                   // +0x22 nop (align literals)
        p = put(out, p, le32(WD_VAR));                            // +0x24 literal
        p = put(out, p, le32(WD_MIRROR));                         // +0x28 literal
        p = put(out, p, le32(WD_ASSIST));                         // +0x2C literal
        if (p != 0x30) throw new RuntimeException("cave size " + p);
        return out;
    }

    private static int[] le32(int v) {
        return new int[]{v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF};
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
