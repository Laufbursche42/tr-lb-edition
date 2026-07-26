package com.lb.edition;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a flashable Teverun VCU firmware from a bundled stock base HEX and hands the result to
 * {@link OtaEngine} for flashing. The image produced is the R5.4.19 "TESTLOCK11" design: a direct
 * BLE speed lock on command 0x1B (the scooter boots LOCKED at 22 km/h and is unlocked / re-locked
 * live over Bluetooth), plus two optional add-ons - a blinker fix and a speedometer wheel-diameter
 * fix.
 *
 * <p>The patches are applied as an exact byte-diff table against the stock base (see the
 * {@code AWIVCU_TESTLOCK11_R5_4_19_VERIFY.txt} reference). Three groups:
 * <ul>
 *   <li>CORE  - always applied: the override clampcave + the four r7-clamp bl sites, the cmd-0x1B
 *       dispatcher hook (cmpcave), the 55 71 lock-state telemetry injection, the reset boot_cave, the
 *       DISPLAY-comms-restored boot-lock (dispboot_cave, hook 0x0800FCFA - boots LOCKED on the display
 *       cold-boot edge), the wheel display-masking (10.0" when locked) and the cruise un-gate (settable
 *       while unlocked). This is the BLE speed lock + boot-lock + wheel/cruise.</li>
 *   <li>BLINKER - the R5.4.19 indicator-blink fix at 0x08019610.</li>
 *   <li>WHEEL   - the speedometer wheel-diameter caves (0x0801DAFC region + the boot NOP) that let the
 *       app's wheel value feed the tacho calibration when unlocked while forcing 10.0" when locked.</li>
 * </ul>
 * Every overwrite is expect-checked against the stock byte first, so the wrong firmware (or an
 * already-patched one) can never be silently corrupted. After the selected groups are applied the
 * CRC-16/MODBUS over the app span is recomputed and the proprietary trailer record is rewritten.
 */
final class FirmwarePatcher {

    private static final int FLASH_BASE = 0x08007000;
    private static final int DEFAULT_START_ADDR = 0x08007149;

    /** address -> byte value (0..255), sorted. */
    private final TreeMap<Integer, Integer> mem = new TreeMap<>();
    private int startAddr = DEFAULT_START_ADDR;
    private int v0 = 0x05, v1 = 0x04, v2 = 0x0E; // :07AAA555 version marker

    /** Internal firmware build number, stamped into the BLE hwVer MAJOR byte (frame 55 43, t[6]) so the
     *  app can read over BLE which patched firmware is on the controller and offer an update. BUMP this
     *  for every new firmware we build and host. */
    static final int FW_BUILD = 43;

    /** Added to FW_BUILD for the kickstart variant, so the same build ships as a plain Vxx plus a V2xx
     *  that is told apart on the scooter (V36 -> V236). Derived, never hand-written: bumping FW_BUILD
     *  moves both stamps together. The stamp is a single byte in the 55 43 frame, so the sum must fit in
     *  0..255 - applyKickstart() refuses to build once FW_BUILD outgrows that. */
    static final int KICK_BUILD_OFFSET = 200;

    /** BLE version stamp of the kickstart variant (V236 for FW_BUILD 36). */
    static final int FW_BUILD_KICK = FW_BUILD + KICK_BUILD_OFFSET;

    private FirmwarePatcher() {}

    // ─────────────────────────── loaders ───────────────────────────

    /** Parse an Intel-HEX (04 ELA / 00 data / 05 start / :07AAA555 trailer). */
    static FirmwarePatcher fromHex(String text) {
        FirmwarePatcher f = new FirmwarePatcher();
        int curHi = 0;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            // Need at least ':' + 2 (byte count) + 4 (address) + 2 (type) = 9 chars before indexing.
            if (line.length() < 9 || line.charAt(0) != ':') continue;
            int ll = h(line, 1, 3), addr = h(line, 3, 7), tt = h(line, 7, 9);
            if (tt == 0x04) {
                if (line.length() >= 13) curHi = h(line, 9, 13);
            } else if (tt == 0x00) {
                if (line.length() < 9 + 2 * ll) continue;   // truncated data record - skip, do not over-read
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

    // ─────────────────────────── patch groups ───────────────────────────
    // Exact byte-diff table (stock R5.4.19 base -> TESTLOCK9). A row is {addr, old, new}: for an
    // in-place overwrite `old` is the stock bytes (expect-checked before writing); for an APPENDED
    // region (address past the stock image end 0x0801DAFB) `old` is null and the bytes are just
    // written. Groups are additive and address-fixed, so any subset combination is self-consistent:
    // the CORE caves always land at their fixed addresses and the fixed hook branches reach them
    // whether or not WHEEL/BLINKER are present.

    private static final class P {
        final int addr; final int[] oldB; final int[] newB;
        P(int addr, int[] oldB, int[] newB) { this.addr = addr; this.oldB = oldB; this.newB = newB; }
    }
    private static P p(int addr, int[] oldB, int[] newB) { return new P(addr, oldB, newB); }
    private static int[] b(int... v) { return v; }

    // CORE - the direct BLE speed lock (cmd 0x1B). Always applied.
    private static final P[] CORE = {
        // reset hook -> b.w boot_cave (override 0x200002A0=0 on a true reset, then the displaced trampoline)
        p(0x08007150, b(0x00,0x48,0x00,0x47), b(0x16,0xF0,0x10,0xBD)),
        // 55 71 telemetry: inject unlockFlag 0x200002A0 into on-wire byte t[2] so the app reads the
        // lock state live (0 = LOCKED, 1 = UNLOCKED). Inline over a proven-dead fill loop, no size change.
        p(0x0800BF5E, b(0x10,0x24,0x06,0xE0,0xFF,0x22,0x0D,0xF1,0x06,0x00,0x61,0x1C,0x42,0x54,0x60,0x1C,0xC4,0xB2,0x10,0x2C,0xF6,0xD3),
                      b(0x41,0xF6,0x40,0x30,0xC2,0xF2,0x00,0x00,0x00,0x78,0x0D,0xF1,0x06,0x01,0x08,0x70,0x00,0xBF,0x00,0xBF,0x00,0xBF)),
        // BLE 0x1B dispatcher hook -> cmpcave (reads the command's value byte into unlockFlag)
        p(0x0800D9DE, b(0x1F,0x28,0x40,0xD1), b(0x10,0xF0,0xB9,0xB8)),
        // Version stamp: BLE 55 43 version frame builds hwVer from the fill at 0x0800C5DE (buffer[4] <-
        // 0x20000019). Replace that copy with movs r7,#FW_BUILD + nop so hwVer MAJOR = our build number.
        // 0x20000019 itself is untouched (still used by the boot/EEPROM code), only the BLE report changes.
        p(0x0800C5DE, b(0x61,0x4F,0x3F,0x78), b(FW_BUILD,0x27,0x00,0xBF)),
        // Boot-lock via DISPLAY-comms-restored edge. The VCU tracks display presence in 0x20000306:
        // set to 0 on every received 0x4c display frame (0x0800FCFA), set to 1 when the frame counter
        // exceeds 600 (display absent). This byte drives the power sequence and is maintained even while
        // the scooter is off (the display cold-boots on power-on, so 0x4c frames stop = 0x20000306 -> 1).
        // Hook the 0x4c handler: if 0x20000306 was 1 (display was gone = we just powered on), force
        // override 0x200002A0 = 0 (LOCK). During a ride 0x4c frames stream continuously so 0x20000306
        // stays 0 -> fires exactly once per power-on, never mid-ride. dispboot_cave keeps r0=0 for the
        // displaced 0x20000306=0 write that the following 0x0800FD00 code needs.
        p(0x0800FCFA, b(0x00,0x20,0xA3,0x49,0x08,0x70), b(0x0D,0xF0,0x45,0xBF,0x00,0xBF)),
        // WHEEL display masking: the VCU->display frame carries the wheel byte (0x2000029D) at frame[0x1e]
        // in two builders (0x0801083C and twin 0x08010B64). Redirect each to wheelcave_A/B, which sends
        // 100 (10.0") when LOCKED (override 0x200002A0 == 0) and the real 0x2000029D when unlocked. This
        // masks only what the display SEES - the stored wheel value is never touched, so the calibrated
        // value returns instantly on unlock. Shows 10.0" continuously while locked, however it was locked.
        p(0x0801083C, b(0x39,0x48,0x00,0x78), b(0x0D,0xF0,0xB2,0xB9)),
        p(0x08010B64, b(0x8C,0x48,0x00,0x78), b(0x0D,0xF0,0x2C,0xB8)),
        // CRUISE fix: the cmd-0x18 cruise-write is gated at 0x0800D2C4 on the display clamp flag
        // 0x2000030C (cbz r0 -> accept only when 0). On an eKFV the display asserts that bit continuously
        // (== 1), so the app's Off/Auto/Manual is ALWAYS discarded (frozen) - cruise is dead in every app.
        // Re-gate on OUR override 0x200002A0: unlocked (override != 0) -> accept the app cruise verbatim
        // (0x0800D2DA); locked -> keep the stock frozen branch (0x0800D2CA). Makes cruise settable while
        // unlocked. Cruise is GLOBAL (0x200002D1), so one write covers all gears.
        p(0x0800D2C4, b(0xDC,0x48,0x00,0x78,0x38,0xB1), b(0x10,0xF0,0x8A,0xBC,0x00,0xBF)),
        // CRUISE engagement gate: the runtime cruise-engage block at 0x0800F724 is skipped entirely when
        // 0x20000301 != 0 (cmp/bne at 0x0800F720). V32 assumed 0x20000301 mirrors an eKFV bit the display
        // asserts, so that locked units were already blocked. Measured on the device that is WRONG:
        // 0x20000301 is 0 here (the display never writes byte[6] bit0), so the gate stands open in BOTH
        // lock states and the V32 cave protected nothing. Polarity is inverted for V33 - UNLOCKED returns
        // the real 0x20000301 (0, so the gate passes and cruise can engage), LOCKED returns 1 so the whole
        // engage block including the set-only 0x20000302=3 at 0x0800F7A6 is skipped.
        p(0x0800F71C, b(0xFE,0x48,0x00,0x78), b(0x0E,0xF0,0x68,0xBA)),
        // CRUISE locked-state leak (V33): the cmd-0x18 tail at 0x0800D2E2..0x0800D318 is completely
        // ungated - it copies 0x200002D1 into the ESC flag bytes 0x2000029A/0x2000029B and writes the
        // engage byte, no matter what the gate at 0x0800D2C4 decided. The stock frozen branch masks the
        // app value with 0xF9 (which keeps the engage bit 0) and then ORs the STORED mode back in, so a
        // locked unit could still be switched cruise-ON from the app (genuinely active, not a UI artifact)
        // while cruise-OFF was impossible. Hook the tail -> cruisetail_cave, which reproduces those stores
        // but masks the value with 0xF8 while locked (engage plus both mode bits cleared, ABS/startMode/
        // rmStatus untouched). 0x200002D1 is deliberately NOT modified so unlock still restores the mode.
        p(0x0800D2E2, b(0xD4,0x48,0x00,0x78), b(0x10,0xF0,0xD3,0xBC)),
        // CRUISE display path (V33): the display sends its cruise setting as record 5, frame byte[5] bits
        // 1,2 - but the VCU discards it at 0x0800F8B4, a second gate on the very same clamp flag
        // 0x2000030C that we re-pointed for the app at 0x0800D2C4. V32 never patched this twin, so cruise
        // was unsettable from the display menu even while unlocked. Hook it -> cruisedisp_cave: unlocked
        // folds the display value into the stored mode 0x200002D1 (otherwise the next lock/unlock, the
        // next power-on or any app write wipes it again) and runs the stock apply block at 0x0800F8B6;
        // locked skips the whole block at 0x0800F8EE.
        p(0x0800F8B0, b(0xA1,0x48,0x00,0x78,0xD8,0xB9), b(0x0E,0xF0,0x10,0xBA,0x00,0xBF)),
        // NOTE - do NOT clear bit5 of the motor-frame flag byte r6 (the `orr r6,r6,#0x20` at 0x08010054
        // plus its three twins). It looks like a ZeroStart release, but a V34 test build that cleared it
        // while unlocked made the ESC stutter plus dropped the reachable top speed: the same setpoint
        // number produced far less speed, so 75 km/h became unreachable even after raising the per-gear
        // value. On this hardware bit5 selects how the ESC INTERPRETS the setpoint scale, it does not
        // just gate ZeroStart. That also explains why the spooonky patcher has to halve the speed value
        // (lsrs r7,#1) whenever it clears the bit. Reverted in V33; find another route for ZeroStart.
        // four r7-clamp bl sites -> newclampcave. The clamp is TWO-VALUE again, selected by the
        // everUnlocked latch: 22 while the scooter has not been unlocked since power-on, 20 once it has.
        // Reason: after a high-speed excursion the ESC speed loop latches roughly 2 km/h high, so a plain
        // 22 clamp lets a re-locked scooter run ~24 until the next power cycle. Clamping one step lower
        // after the first unlock pulls it back to about 22. Measured on the device: clamp 22 gave 23.7 km/h
        // in the app telemetry, clamp 21 gave 22.8, so roughly 0.9 km/h per step - 20 lands just under 22.
        // The app number is used because the DISPLAY cannot be trusted here: its firmware shows a hard 22.0
        // for anything between 22.0 and 23.0 (plus briefly above), so it hides exactly this range. A power-on clears the latch (bootclear_cave),
        // so a scooter that was never unlocked keeps the exact stock 22. This existed up to V13 plus was
        // dropped by mistake in the V32 cave rewrite, where it was misread as dead code.
        p(0x08010058, b(0x16,0x2F,0x03,0xDD,0x16,0x27,0x01,0xE0), b(0x0D,0xF0,0x56,0xFE,0x02,0xE0,0x00,0xBF)),
        p(0x08010208, b(0x16,0x2F,0x03,0xDD,0x16,0x27,0x01,0xE0), b(0x0D,0xF0,0x7E,0xFD,0x02,0xE0,0x00,0xBF)),
        p(0x080103B0, b(0x16,0x2F,0x03,0xDD,0x16,0x27,0x01,0xE0), b(0x0D,0xF0,0xAA,0xFC,0x02,0xE0,0x00,0xBF)),
        p(0x080105D0, b(0x16,0x2F,0x03,0xDD,0x16,0x27,0x01,0xE0), b(0x0D,0xF0,0x9A,0xFB,0x02,0xE0,0x00,0xBF)),
        // factory-default gear table: new default per-gear speeds 20/22/40/65/100 (was 20/40/60/80/100).
        // Baked into the defaults that are reloaded from EEPROM; the firmware recomputes the table CRC at
        // runtime on write, so no in-table CRC fixup is needed here.
        p(0x0801D7A2, b(0x28), b(0x16)),   // gear2 default speed 40 -> 22
        p(0x0801D7A8, b(0x3C), b(0x2D)),   // gear3 default speed 60 -> 45
        p(0x0801D7AE, b(0x50), b(0x41)),   // gear4 default speed 80 -> 65
        // EEPROM save fix: mirror byte 0 is written FROM 0x2000029B but restored INTO 0x200002D1
        // (save 0x08017C64, restore 0x08017294). That aliasing is what turns our locked-state mask of
        // 0x2000029B into permanent loss of the owner's cruise mode: the masked value is committed to
        // EEPROM plus loaded back as the stored mode on the next boot. The hook makes the save read the
        // stored mode itself, so the round trip is lossless plus nothing we do to the ESC flag byte can
        // reach the persisted setting. This also covers the identical mask inside bootcruise_cave.
        p(0x08017C64, b(0x21,0x48,0x00,0x78), b(0x06,0xF0,0x7A,0xB8)),
        // appended caves @0x0801DD5C..0x0801DD87, past every earlier block so no offset moves.
        //   eepromfix_cave @0x0801DD5C: r0 = stored mode 0x200002D1, back to 0x08017C68.
        //   oncelock_cave  @0x0801DD68: the power-on lock must run ONCE per power cycle. The stock flag
        //     0x20000306 is not a power-on flag, it is re-armed at 0x0800FE52 after 600 handler
        //     iterations without a valid display frame, so a display link that drops plus recovers would
        //     re-lock mid-ride. Our own marker 0x20003B62 is zeroed by boot_cave plus set here, so the
        //     lock branch runs on the first display frame after a real power-on plus never again.
        p(0x0801DD5C, null, b(0x01,0x48,0x00,0x78,0xF9,0xF7,0x82,0xBF,0xD1,0x02,0x00,0x20,
                              0x05,0x49,0x08,0x78,0x18,0xB9,0x01,0x20,0x08,0x70,0xFF,0xF7,
                              0x6D,0xBF,0x00,0x20,0x02,0x49,0x08,0x70,0xF1,0xF7,0xC0,0xBF,
                              0x42,0x1B,0x00,0x20,0x06,0x03,0x00,0x20)),
        // appended cave block @0x0801DB40..0x0801DC13 - ONLY the live caves. The dead V21/V22/V23 boot-lock
        // caves (disp_reset/disp counter, esclock, poweron) and the old newclampcave/arm_cave/nfc_cave and
        // the everUnlocked flag were all removed; the working boot-lock is dispboot_cave alone.
        p(0x0801DB40, null, b(
                              // clampcave @0x0801DB40 (4 r7-clamp sites): override 0x200002A0==0 (locked) ->
                              // clamp r7 to 0x16 (22), else pass.
                              0x03,0x48,0x00,0x78,0x10,0xB9,0x16,0x2F,0x00,0xDD,0x16,0x27,0x70,0x47,0x00,0xBF,
                              0x40,0x1B,0x00,0x20,
                              // cmpcave @0x0801DB54 (BLE dispatcher hook 0x0800D9DE): cmd 0x1B -> override
                              // 0x200002A0 = value byte, then b.w cruise_lock_cave (cruise off on lock /
                              // restore on unlock); cmd 0x1F -> displaced identity handler; else dispatch.
                              0x1F,0x28,0x09,0xD0,0x1B,0x28,0x01,0xD0,0xEF,0xF7,0x82,0xBF,
                              0x9D,0xF8,0x06,0x10,0x02,0x48,0x01,0x70,0x00,0xF0,0x50,0xB8,0xEF,0xF7,0x6C,0xBF,
                              0x40,0x1B,0x00,0x20,
                              // boot_cave @0x0801DB74 (reset hook 0x08007150): override 0x200002A0=0, then
                              // the displaced trampoline ldr r0,=0x0800B87D; bx r0. Only on a true reset.
                              0x00,0x20,0x02,0x49,0x08,0x60,0x02,0x48,0x00,0x47,0x00,0xBF,
                              0x40,0x1B,0x00,0x20,0x7D,0xB8,0x00,0x08,
                              // dispboot_cave @0x0801DB88 (boot-lock, hook 0x0800FCFA): if 0x20000306 was 1
                              // (display was absent = power-on edge) run bootcruise_cave (override 0x200002A0=0
                              // AND cruise off, because the VCU keeps its RAM across a soft power-cycle so a
                              // previously-set cruise mode would otherwise survive the boot-lock); else the
                              // cbz skips to the 0x0800FD00 return. The lock branch now b.w's to bootcruise_cave
                              // which also does the displaced 0x20000306=0 write and returns to 0x0800FD00.
                              0x04,0x49,0x08,0x78,0x20,0xB1,0x00,0xF0,0xEB,0xB8,0x00,0xBF,0x01,0x49,0x08,0x70,
                              0xF2,0xF7,0xB2,0xB8,0x06,0x03,0x00,0x20,0x40,0x1B,0x00,0x20,
                              // wheelcave_A @0x0801DBA4 (hook 0x0801083C): locked -> r0=100 (10.0"), else
                              // r0=real 0x2000029D; b.w back to the strb at 0x08010840.
                              0x04,0x48,0x00,0x78,0x08,0xB9,0x64,0x20,0x01,0xE0,0x03,0x48,0x00,0x78,0xF2,0xF7,
                              0x45,0xBE,0x00,0xBF,0x40,0x1B,0x00,0x20,0x9D,0x02,0x00,0x20,
                              // wheelcave_B @0x0801DBC0 (twin, hook 0x08010B64 -> back to 0x08010B68).
                              0x04,0x48,0x00,0x78,0x08,0xB9,0x64,0x20,0x01,0xE0,0x03,0x48,0x00,0x78,0xF2,0xF7,
                              0xCB,0xBF,0x00,0xBF,0x40,0x1B,0x00,0x20,0x9D,0x02,0x00,0x20,
                              // cruisegate_cave @0x0801DBDC (hook 0x0800D2C4): unlocked -> accept branch
                              // 0x0800D2DA, locked -> stock frozen branch 0x0800D2CA.
                              0x03,0x48,0x00,0x78,0x08,0xB9,0xEF,0xF7,0x72,0xBB,0xEF,0xF7,0x78,0xBB,0x00,0xBF,
                              0x40,0x1B,0x00,0x20,
                              // cruiseengage_cave @0x0801DBF0 (hook 0x0800F71C): UNLOCKED -> cbnz takes the
                              // real 0x20000301 (measured 0 on this unit, so the gate passes and cruise can
                              // engage); LOCKED -> falls through to r0=1 so 0x0800F720 skips the whole engage
                              // block. V32 had this polarity inverted, which left the gate open when locked.
                              0x04,0x48,0x00,0x78,0x08,0xB9,0x01,0x20,0x01,0xE0,0x03,0x48,0x00,0x78,0xF1,0xF7,
                              0x8F,0xBD,0x00,0xBF,0x40,0x1B,0x00,0x20,0x01,0x03,0x00,0x20,
                              // cruise_lock_cave @0x0801DC0C (from the cmpcave 0x1B tail): LOCK (value=0) ->
                              // engage 0x20000302=0 and clear the cruise mode bits (1,2) of 0x2000029B and
                              // 0x2000029A (cruise OFF); UNLOCK (value=1) -> restore those bits from the
                              // stored mode 0x200002D1 & 6. Stored mode is written by the app's 0x18 while
                              // unlocked and is never cleared here, so unlock brings the set value back.
                              // The runtime keeps 0x2000029A/B bits 1,2 (its display-block is clamp-skipped
                              // on an eKFV), so this sticks. b.w back to the dispatcher 0x0800DA66.
                              0x0C,0xB4,0x19,0xB9,0x00,0x22,0x0D,0x48,0x02,0x70,0x03,0xE0,0x09,0x48,0x02,0x78,
                              0x02,0xF0,0x06,0x02,0x08,0x48,0x03,0x78,0x03,0xF0,0xF8,0x03,0x13,0x43,0x03,0x70,
                              0x07,0x48,0x03,0x78,0x03,0xF0,0xF8,0x03,0x13,0x43,0x03,0x70,0x0C,0xBC,0xEF,0xF7,
                              0x14,0xBF,0x00,0xBF,0xD1,0x02,0x00,0x20,0x9B,0x02,0x00,0x20,0x02,0x03,0x00,0x20,
                              0x9A,0x02,0x00,0x20,
                              // bootcruise_cave @0x0801DC50 (from the dispboot_cave power-on lock branch):
                              // override 0x200002A0=0 (LOCK), engage 0x20000302=0, clear cruise mode bits (1,2)
                              // of 0x2000029B and 0x2000029A (cruise OFF on every power-on), reset the power-on
                              // flag 0x20000306=0 (so it fires once, not every 0x4c frame), then b.w 0x0800FD00.
                              0x00,0x20,0x09,0x49,0x08,0x70,0x09,0x49,0x08,0x70,0x09,0x49,0x08,0x70,0x09,0x49,
                              0x0A,0x78,0x02,0xF0,0xF8,0x02,0x0A,0x70,0x07,0x49,0x0A,0x78,0x02,0xF0,0xF8,0x02,
                              0x0A,0x70,0x00,0xF0,0x61,0xB8,0x00,0xBF,0x40,0x1B,0x00,0x20,0x02,0x03,0x00,0x20,
                              0x06,0x03,0x00,0x20,0x9B,0x02,0x00,0x20,0x9A,0x02,0x00,0x20,
                              // cruisetail_cave @0x0801DC8C (hook 0x0800D2E2, rejoins 0x0800D31A): reproduces
                              // the ungated cmd-0x18 tail but sanitises the value with 0xF8 while LOCKED, so
                              // neither the engage bit nor the mode bits can reach the ESC flag bytes. Uses
                              // r0/r1/r2 only - r2 is dead here (re-loaded at 0x0800D324) - and never moves sp,
                              // so the [sp,#0xf] read of payload[0xF] stays valid.
                              0x0C,0x48,0x02,0x78,0x0C,0x48,0x00,0x78,0x08,0xB9,0x02,0xF0,0xF8,0x02,0x90,0x08,
                              0x01,0x21,0x08,0x40,0x09,0x49,0x08,0x70,0x02,0xF0,0x7F,0x00,0x9D,0xF8,0x0F,0x10,
                              0x01,0xF0,0x80,0x01,0x08,0x43,0x06,0x49,0x08,0x70,0x06,0x49,0x0A,0x70,0xEF,0xF7,
                              0x2E,0xBB,0x00,0xBF,0xD1,0x02,0x00,0x20,0x40,0x1B,0x00,0x20,0x02,0x03,0x00,0x20,
                              0x9A,0x02,0x00,0x20,0x9B,0x02,0x00,0x20,
                              // cruisedisp_cave @0x0801DCD4 (hook 0x0800F8B0): UNLOCKED folds the display's
                              // cruise bits (0x2000134D[5] & 6) into the stored mode 0x200002D1 so the value
                              // survives lock/unlock, power-on and app writes, then runs the stock apply block
                              // at 0x0800F8B6; LOCKED skips the block at 0x0800F8EE. r2 is preserved because
                              // this code is reached through the display-frame tbb dispatch.
                              0x09,0x48,0x00,0x78,0x68,0xB1,0x04,0xB4,0x08,0x48,0x40,0x79,0x00,0xF0,0x06,0x00,
                              0x07,0x49,0x0A,0x78,0x02,0xF0,0xF9,0x02,0x02,0x43,0x0A,0x70,0x04,0xBC,0xF1,0xF7,
                              0xE0,0xBD,0xF1,0xF7,0xFA,0xBD,0x00,0xBF,0x40,0x1B,0x00,0x20,0x4D,0x13,0x00,0x20,
                              0xD1,0x02,0x00,0x20,
                              // newclampcave @0x0801DD08 (bl from the four r7-clamp sites). UNLOCKED:
                              // latch everUnlocked = 1 plus leave r7 alone. LOCKED: clamp to 21 when the
                              // latch is set, else to 22. r1 is pushed/popped so the builders keep it.
                              //   ldr r0,=0x200002A0 ; ldrb ; cbz -> locked
                              //   push {r1} ; movs r0,#1 ; ldr r1,=0x200003D0 ; strb ; pop {r1} ; bx lr
                              //   locked: ldr r0,=0x200003D0 ; ldrb ; cbz -> use22
                              //           movs r0,#0x14 ; b apply ; use22: movs r0,#0x16
                              //           apply: cmp r7,r0 ; ble out ; mov r7,r0 ; out: bx lr
                              0x09,0x48,0x00,0x78,0x28,0xB1,0x02,0xB4,0x01,0x20,0x08,0x49,0x08,0x70,0x02,0xBC,
                              0x70,0x47,0x06,0x48,0x00,0x78,0x08,0xB1,0x14,0x20,0x00,0xE0,0x16,0x20,0x87,0x42,
                              0x00,0xDD,0x07,0x46,0x70,0x47,0x00,0xBF,0x40,0x1B,0x00,0x20,0x41,0x1B,0x00,0x20,
                              // bootclear_cave @0x0801DD38 (bootcruise_cave now exits through here):
                              // clears the everUnlocked latch on the power-on edge, then continues to the
                              // stock 0x0800FD00 return. Without it the latch would survive a soft power
                              // cycle - the VCU keeps its RAM - plus the scooter would stay on 21 forever.
                              0x00,0x20,0x02,0x49,0x08,0x70,0xF1,0xF7,0xDF,0xBF,0x00,0xBF,0x41,0x1B,0x00,0x20,
                              // relocated 8-byte end marker (must stay at the image end)
                              0xA5,0x01,0x19,0x02,0x57,0x19,0x00,0x00)),
    };

    // BLINKER - the R5.4.19 indicator-blink fix (extra PB5 reset -> NOP).
    private static final P[] BLINKER = {
        p(0x08019610, b(0xFF,0xF7,0x90,0xFF), b(0x00,0xBF,0x00,0xBF)),
    };

    // KICKSTART - force ZeroStart (no kick needed to start) permanently, for older scooters only.
    //
    // Why this exists: the kick-start requirement travels to the motor controller as bit6 (0x40) of ESC
    // frame byte[5]. The whole chain app -> 0x200002D1 -> 0x2000029A/0x2000029B -> frame[5] is ungated in
    // stock R5.4.19 plus our CORE masking preserves bit6, so on most hardware the Start mode setting
    // simply works and this group is not needed. On some older controllers it does not work at all,
    // whatever app or display menu is used - for those units this group takes the decision away from
    // every writer and forces the bit at the wire. Start mode is the Launch / kick start row in the
    // settings; it is not Smart mode, which is the traction control and unrelated to this.
    //
    // Polarity: bit6 = 1 means "kick required". Derived from the original app, which defaults an eKFV
    // device to startMode 1 plus forces startMode 1 in its road-legal bundle - and the eKFV does not
    // permit starting from standstill under motor power. So clearing bit6 disables the kick requirement.
    //
    // Hook: the contiguous pair `strb r5,[base,#4]; strb r4,[base,#5]` in each of the four controller
    // frame builders - the single point every r4 bit edit has already passed. The rear builders do
    // `mov r0,r1` immediately before it, so r1 is the frame base in all four; r2 is dead there (each
    // builder reloads it with movs r2,#0x10/0x14 shortly after). lr is free because the builders save it
    // on entry, which the CORE clampcave bl already relies on.
    //
    // Not gated on the lock state: the bit is cleared on every frame in both states, because a controller
    // that ignores the switch would otherwise be back to a kick requirement the owner cannot clear. The
    // user cannot switch it back on either - that is the point. Newer controllers honour the normal
    // Start mode setting, so they must be flashed without this group.
    private static final P[] KICKSTART = {
        // ZeroStart for controllers that ignore the app's start-mode value.
        //
        // The earlier version of this group cleared bit 6 of ESC frame byte 5, which is the
        // startMode the app sends. On the controller this was written for that does nothing, plus it
        // was never confirmed to work on any other. The web patcher at fw/spooonky solves it at a
        // different bit plus its author measured the result on a scooter with a DAP-Link:
        //
        //   the controller honours zero start only when frame byte 0xF bit 5 is 0, plus
        //   bit 5 at 0 needs the clamp removed OR halved, otherwise the controller goes into limp.
        //
        // Both halves are required together. Bit 5 stays cleared in BOTH lock states, because
        // restoring it on lock would take kickstart away again the moment the rider locks. And with
        // bit 5 cleared the locked state must halve instead of clamping to 22, which is exactly the
        // pairing this project got wrong once before plus wrote off as a dead end.
        //
        //   unlocked   bit 5 cleared, no clamp at all      full speed, kickstart works
        //   locked     bit 5 cleared, r7 halved            about 22, kickstart still works
        //
        // The unlocked branch of newclampcave already returns without touching r7, so only the
        // locked branch changes.
        p(0x08010054, b(0x46,0xF0,0x20,0x06), b(0x26,0xF0,0x20,0x06)),
        p(0x08010204, b(0x46,0xF0,0x20,0x06), b(0x26,0xF0,0x20,0x06)),
        p(0x080103AC, b(0x46,0xF0,0x20,0x06), b(0x26,0xF0,0x20,0x06)),
        p(0x080105CC, b(0x46,0xF0,0x20,0x06), b(0x26,0xF0,0x20,0x06)),
        // newclampcave locked branch at 0x0801DD1A: the 20 bytes that pick 20 or 22 plus clamp r7
        // become 'lsrs r7,r7,#1' plus 'bx lr', padded with nops so nothing after it moves.
        p(0x0801DD1A, b(0x06,0x48,0x00,0x78,0x08,0xB1,0x14,0x20,0x00,0xE0,0x16,0x20,0x87,0x42,
                        0x00,0xDD,0x07,0x46,0x70,0x47),
                      b(0x7F,0x08,0x70,0x47,0x00,0xBF,0x00,0xBF,0x00,0xBF,0x00,0xBF,
                        0x00,0xBF,0x00,0xBF,0x00,0xBF,0x00,0xBF)),
        // Version stamp so the two builds are told apart on the scooter.
        p(0x0800C5DE, b(FW_BUILD), b(FW_BUILD_KICK)),
    };

    // WHEEL - speedometer wheel-diameter fix: UNLOCKED the app's cmd-0x18 wheel value feeds the tacho
    // WD var (accurate speed); LOCKED the display is forced to 10.0" so a roadside check reads stock.
    private static final P[] WHEEL = {
        // 0x18 sub-cmd-2 hook -> WD cave (record[4] -> WD var, mirror + native save)
        p(0x0800D3B0, b(0x80,0x79,0xAE,0x49), b(0x10,0xF0,0xA4,0xBB)),
        // boot NOP: stop the boot-load overwriting the persisted wheel with 100 (10.0")
        p(0x0801730E, b(0x08,0x70), b(0x00,0xBF)),
        // appended WD cave @0x0801DAFC..0x0801DB3F: cave code + literals + carried end marker. Sits
        // before the CORE cave block. It takes the wheel value out of the cmd-0x18 frame, stores it in
        // the WD variable plus persists it (mirror + native EEPROM save at 0x0801700C).
        //
        // The former "force 10.0 while locked" at 0x0801DB04 is NOPed out since V36. It was destructive:
        // every settings write from the app while the scooter was locked overwrote the owner's calibrated
        // wheel diameter with 100 (10.0") plus saved that to EEPROM, so the calibration was permanently
        // lost, not merely hidden. It was also redundant - the CORE patches at 0x0801083C plus 0x08010B64
        // already show the display a fixed 10.0" while locked WITHOUT touching the stored value, which is
        // all the roadside-stock appearance needs. The lock-flag read plus cbnz above it are left in place
        // (harmless, they now branch to the same instruction either way).
        p(0x0801DAFC, null, b(0x02,0x79,0x0A,0x49,0x09,0x78,0x01,0xB9,0x00,0xBF,0x09,0x49,0x0B,0x78,0x0A,0x70,
                              0x9A,0x42,0x06,0xD0,0x07,0x49,0xCA,0x70,0x01,0xB5,0x01,0x20,0xF9,0xF7,0x78,0xFA,
                              0x03,0xBC,0x80,0x79,0x04,0x49,0xEF,0xF7,0x47,0xBC,0x00,0xBF,0x40,0x1B,0x00,0x20,
                              0x9D,0x02,0x00,0x20,0x28,0x1A,0x00,0x20,0xA5,0x02,0x00,0x20,0xA5,0x01,0x19,0x02,
                              0x57,0x19,0x00,0x00)),
    };

    /** Apply one group: expect-check the stock bytes (for in-place rows) then write the new bytes. */
    private void applyGroup(P[] group, String what) {
        for (P pr : group) {
            if (pr.oldB != null) {
                for (int i = 0; i < pr.oldB.length; i++) {
                    int cur = rd(pr.addr + i);
                    if (cur != pr.oldB[i]) {
                        throw new RuntimeException(String.format(
                                "%s @0x%08X expected 0x%02X but found 0x%02X (wrong firmware or already patched)",
                                what, pr.addr + i, pr.oldB[i], cur));
                    }
                }
            }
            for (int i = 0; i < pr.newB.length; i++) wr(pr.addr + i, pr.newB[i]);
        }
    }

    /** CORE - the direct BLE speed lock (cmd 0x1B). Always applied for R5.4.19. */
    void applyCore() { applyGroup(CORE, "core"); }

    /** BLINKER fix (R5.4.19). */
    void applyBlinker() { applyGroup(BLINKER, "blinker"); }

    /** WHEEL-diameter (tacho) fix (R5.4.19). */
    void applyWheel() { applyGroup(WHEEL, "wheel"); }

    /** KICKSTART - force ZeroStart permanently (only for controllers where the normal switch is dead).
     *  Stamps the BLE version as FW_BUILD_KICK, so this build reports V2xx against the plain Vxx. */
    void applyKickstart() {
        if (FW_BUILD_KICK > 0xFF) {
            throw new RuntimeException("kickstart version stamp " + FW_BUILD_KICK
                    + " does not fit in the single BLE version byte - lower KICK_BUILD_OFFSET");
        }
        applyGroup(KICKSTART, "kickstart");
    }

    // ─────────────────────────── HEX output ───────────────────────────

    /** Fill any hole between the first and last address with 0xFF so the app image is one contiguous
     *  span (matches erased flash). No-op when nothing is missing (e.g. the full blinker+wheel build). */
    private void seal() {
        if (mem.isEmpty()) return;
        int lo = mem.firstKey(), hi = mem.lastKey();
        for (int a = lo; a <= hi; a++) if (!mem.containsKey(a)) mem.put(a, 0xFF);
    }

    /** Rebuild the flashable Intel-HEX (04 ELA per 64K, 00 data 16B, 05 start, EOF, :07AAA555 CRC16). */
    String buildHex() {
        seal();
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
        seal();
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
}
