// Laufbursche Edition - an app for Teverun e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Firmware update over the BLE-UART link: the bootloader takes an Intel-HEX image in 12-byte
 * frames. Frame layout, ids and pacing are the ones the bootloader accepts, established by watching
 * a working update on the wire.
 *
 * <p>The START id picks the target. The legacy path (Fighter Mini, {@code ver2 == false}) addresses
 * the controller node (07 10) or the battery node (07 00) directly. A ver2 device takes every node
 * through the display node (07 80) instead, selected by the 06 e2 handshake below.
 *
 * <p>Wire framing (all update frames): {@code 0xbb [idHi idLo b0..b7] crc8} = 12 bytes, crc8 over the
 * 10 middle bytes. Responses: {@code 0xcc [idHi idLo status..] crc8}. CRC8 = poly 0x07 (shared with
 * {@link CommandBuilder#crc8}); firmware integrity = CRC16/MODBUS ({@link CommandBuilder#crc16Modbus}).
 *
 * <p>State machine (each response is a valid {@code 0xcc} frame): prepare/erase (0xaa 04 03) ->
 * 1500 ms -> START (07 10) with 1 s x10 retry -> START resp (07 50 55) -> 200 ms -> INFO (07 12) ->
 * INFO resp (07 52) -> per packet: PACKINFO (07 13) -> PACKINFO resp (07 53) -> PACKDATA burst
 * (07 14, 7 data bytes/frame) -> PACKDATA resp (07 54 aa) -> next packet -> FINISH (07 11) ->
 * FINISH resp (07 51 aa) = success. A 30-minute global watchdog is armed on the START response.
 *
 * <p>Threading: {@link #onNotify}/{@link #onWriteComplete} are called from the GATT binder thread and
 * immediately marshalled onto the main looper, so the whole state machine runs single-threaded.
 * All BLE writes are issued from the main looper via {@link Host#writeFrame}.
 */
final class OtaEngine {

    private static final String TAG = "lbota";

    /** BLE + UI callbacks, implemented by {@link BleManager}. Every call happens on the main looper. */
    interface Host {
        /** Write one already-framed OTA frame to the write characteristic. @return false if it could not start. */
        boolean writeFrame(byte[] frame);
        /** Request a fast / balanced BLE connection interval for the duration of the flash. */
        void setHighPriority(boolean high);
        /** Progress update: percent 0..100, current 1-based packet, total packets, short phase label. */
        void progress(int percent, int packet, int count, String phase);
        /** Append one human-readable line to the flash log shown on the update page. */
        void log(String line);
        /** Terminal state: one of "running", "success", "failed", "cancelled" + a message. */
        void state(String state, String message);
        /** Called once when the flash ends (success or not) so the host can resume keep-alive / push. */
        void finished(boolean success);
    }

    // ── Frame ids per target node (controller 07 10, battery 07 00, display 07 80) ──
    private static final class Config {
        final int[] START, START_RESP, INFO, INFO_RESP, PACKINFO, PACKINFO_RESP,
                PACKDATA, PACKDATA_RESP, FINISH, FINISH_RESP;
        final int[] HANDSHAKE, HANDSHAKE_RESP;   // ver2 node-select handshake; null on the legacy path
        final int packLen, lineLen;
        Config(int idHi, int startLo, int packLen, int[] handshake, int[] handshakeResp) {
            // The id blocks are perfectly regular around the START low byte:
            //   START s, START_RESP s+0x40, FINISH s+1, FINISH_RESP s+0x41,
            //   INFO s+2, INFO_RESP s+0x42, PACKINFO s+3, PACKINFO_RESP s+0x43,
            //   PACKDATA s+4, PACKDATA_RESP s+0x44.
            //   controller s=0x10, battery s=0x00, display s=0x80 (resp block = 0xc0..).
            START = new int[]{idHi, startLo};
            START_RESP = new int[]{idHi, startLo + 0x40};
            FINISH = new int[]{idHi, startLo + 0x01};
            FINISH_RESP = new int[]{idHi, startLo + 0x41};
            INFO = new int[]{idHi, startLo + 0x02};
            INFO_RESP = new int[]{idHi, startLo + 0x42};
            PACKINFO = new int[]{idHi, startLo + 0x03};
            PACKINFO_RESP = new int[]{idHi, startLo + 0x43};
            PACKDATA = new int[]{idHi, startLo + 0x04};
            PACKDATA_RESP = new int[]{idHi, startLo + 0x44};
            HANDSHAKE = handshake;
            HANDSHAKE_RESP = handshakeResp;
            this.packLen = packLen;
            this.lineLen = 16;   // 16 data bytes per Intel-HEX record for every target
        }
        static Config vcu() { return new Config(0x07, 0x10, 512, null, null); }   // controller node
        static Config bms() { return new Config(0x07, 0x00, 1024, null, null); }  // battery node
        // Display node: ver2 devices flash EVERY node through this block, the node being selected by
        // the 06 e2 handshake (nodeType/projectCode) rather than by a separate id block.
        static Config dis() { return new Config(0x07, 0x80, 1024, new int[]{0x06, 0xe2}, new int[]{0x06, 0xea}); }
    }

    /** One flash packet: 3-byte target address (bank byte + 16-bit offset) and its hex data. */
    private static final class Packet {
        String bank = "00";      // low byte of the enclosing type-04 extended-address record
        String offset = "0000";  // 16-bit address field of the packet's first data record
        String data = "";        // concatenated hex data, up to packLen bytes
    }

    // ── Timers / pacing ──
    private static final long PREPARE_TO_START_MS = 1500;
    private static final long START_RETRY_MS = 1000;      // resend START every 1 s
    private static final int  START_MAX_RETRIES = 9;      // retryCount < 9 -> up to 10 sends total
    private static final long STEP_RESEND_MS = 3000;      // single resend of INFO / PACKINFO / FINISH
    private static final long GLOBAL_WATCHDOG_MS = 1_800_000; // 30 min armed on START response
    private static final long START_TIMEOUT_MS = 3000;    // START never answered -> give up
    private static final long STUCK_RECOVERY_MS = 5000;   // re-drive a stuck packet
    private static final long FINISH_CHECK_MS = 10_000;   // final success check
    private static final long CORRUPT_NUDGE_MS = 100;     // bad-crc cc frame -> re-drive
    private static final long PACKDATA_ERR_RETRY_MS = 200;// PACKDATA error -> retry packet
    // Inter-frame delay for the write pump, 30 ms, the value a working update runs at. The writes
    // are no-response (fire-and-forget at the ATT layer), so this floor prevents overrunning the
    // bootloader; the RF connection interval caps the real rate anyway (~60 ms/frame on hardware).
    private static final long INTERFRAME_MS = 30;
    // Per-packet stall watchdog: if a packet gets no PACKDATA response within this many 5 s ticks,
    // re-drive it (resend PACKINFO). MAX packet re-drives before giving up.
    private static final int PACKET_STALL_TICKS = 2;      // ~10 s of no response -> re-drive
    private static final int PACKET_MAX_RETRIES = 5;
    // Safety cap on the auto-restart-from-START recovery so a persistently failing flash cannot loop
    // until the 30-min watchdog fires.
    private static final int MAX_RESTARTS = 2;

    // VCU app base offset from 0x08000000 (app starts at 0x08007000). Anything below this is the
    // protected bootloader region and must never be a flash target.
    private static final int APP_BASE_OFFSET = 0x7000;

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;

    private Config config;
    private boolean isVcu = true;
    private boolean ver2 = false;              // flash through the display node with a node handshake
    private int nodeType = -1, projectCode = -1;  // node select, read from the file trailer
    private final List<Packet> packets = new ArrayList<>();
    private byte[] allData = new byte[0];     // decoded firmware bytes (for CRC16 + INFO length)
    private String fileCrc = "";              // trailer CRC16 (4 hex, uppercase)
    private String fileVer = "";              // trailer sw version "a.b.c"

    // engine state
    private boolean startSent = false;        // START has been sent for this attempt
    private int flashState = 0;               // 0 idle, 1 success, 2 no-START, 3 flashing
    private int packetIndex = 0;              // current packet
    private int retryCount = 0;               // START resend counter
    private int restarts = 0;
    private int packetRetries = 0;            // re-drives of the current packet (stall recovery)
    private boolean packetFailed = false;     // a PACKDATA error stalls the current burst

    // outbound: scheduled PACKDATA frame runnables (so a re-drive can cancel them)
    private final List<Runnable> pendingSends = new ArrayList<>();

    // timers (kept so they can be cancelled)
    private Runnable startRetry, stepResend, stuckTimer, watchdog, startTimeout, finishCheck;

    OtaEngine(Host host) {
        this.host = host;
    }

    boolean isRunning() {
        return running;
    }

    // ── Public entry points ──

    /**
     * Parse {@code hexText} (raw Intel-HEX file text) and, if it is valid, begin flashing. Runs the
     * gate on {@code fileName} only to pick the VCU/BMS target; the hard integrity gate is the CRC16
     * check against the file trailer. Must be called on the main looper.
     */
    void start(String hexText, String fileName) { start(hexText, fileName, false); }

    /**
     * ver2 (T2/tetra) devices flash every node (display, light module, controllers, battery) through
     * the display block (START id 07 80), selecting the target node with a 06 e2 handshake whose
     * node type and project code come from the file's :07AAA555 trailer. Framing and state machine
     * are otherwise those of the legacy path.
     */
    void start(String hexText, String fileName, boolean ver2) {
        if (running) return;
        running = true;
        resetState();
        host.state("running", "Preparing");
        host.log("Preparing the scooter for the update");

        // Target select from the filename prefix: an AWE... name is a battery image, everything else
        // is a controller image. This picks the frame-id config AND the packet grouping
        // (controller 32 lines/packet, battery 64), so it must happen before grouping.
        String name = fileName == null ? "" : fileName.trim();
        this.ver2 = ver2;
        if (ver2) {
            isVcu = false;                 // the display block takes any node, so no target split here
            config = Config.dis();
        } else {
            isVcu = !name.toUpperCase(Locale.US).startsWith("AWE");
            config = isVcu ? Config.vcu() : Config.bms();
        }

        try {
            rawLines = (hexText == null ? "" : hexText).split("\r?\n");
            prepareGroups(config.packLen / config.lineLen);
        } catch (Throwable t) {
            Log.e(TAG, "parse failed", t);
            fail("Could not read this firmware file");
            return;
        }

        if (packets.isEmpty() || fileCrc.isEmpty()) {
            fail("This file is not a firmware image the scooter can take");
            return;
        }

        // Hard integrity gate: CRC16 over all packet data must equal the trailer CRC (uppercase).
        String calc = String.format(Locale.US, "%04X", CommandBuilder.crc16Modbus(allData, allData.length));
        if (!calc.equalsIgnoreCase(fileCrc)) {
            Log.w(TAG, "crc mismatch calc=" + calc + " file=" + fileCrc);
            fail("This file is damaged: its contents do not match its own checksum");
            return;
        }

        // Model-independent safety net: a VCU app image starts at flash 0x08007000 (offset 0x7000).
        // Refuse anything whose first packet targets below that - a full-flash or bootloader image
        // would otherwise aim at the protected bootloader region. The bootloader also clamps writes,
        // but this fails fast before erasing anything.
        if (isVcu) {
            Packet p0 = packets.get(0);
            int[] a = addr3(p0.bank, p0.offset);
            int firstAddr = (a[0] << 16) | (a[1] << 8) | a[2];
            if (firstAddr < APP_BASE_OFFSET) {
                Log.w(TAG, "refusing flash: first offset 0x" + Integer.toHexString(firstAddr) + " < app base");
                fail("This file does not look like controller app firmware (targets the bootloader region)");
                return;
            }
        }

        host.log((ver2 ? "ver2 node " + nodeType + "/" + projectCode : (isVcu ? "controller" : "battery"))
                + " image, " + packets.size() + " packets, " + allData.length + " bytes, CRC " + calc);
        host.progress(0, 0, packets.size(), "Preparing");

        // NOTE: do NOT request a HIGH connection priority. A faster interval pushes frames at the
        // controller quicker than its BLE-module UART forwards them, which overran it after a few
        // packets (the VCU then stopped answering).

        if (ver2) {
            // ver2: no bootloader-prepare; the 06 e2 handshake selects the node and its 06 ea "01 aa"
            // ack drives START. No 1500 ms wait.
            if (nodeType < 0 || projectCode < 0) { fail("This file carries no node header (07AAA555)"); return; }
            sendHandshake();
        } else {
            // Step 0: prepare / erase, then START after 1500 ms.
            sendPrepare();
            host.log("Waiting for the controller to erase its app flash");
            main.postDelayed(() -> { if (running) sendStart(); }, PREPARE_TO_START_MS);
        }
    }

    /** User-initiated abort. The VCU stays in bootloader receive-mode, so the flash can be retried. */
    void cancel() {
        if (!running) return;
        host.log("Cancelled by the user. Flash again before riding: the scooter is in update mode");
        finish(false, "cancelled", "Cancelled");
    }

    /** GATT notification (binder thread) -> marshal to the main looper. */
    void onNotify(final byte[] value) {
        if (!running || value == null) return;
        final byte[] copy = value.clone();
        main.post(() -> {
            if (!running) return;
            try {
                handleNotify(copy);
            } catch (Throwable t) {
                // A malformed or hostile OTA response must never crash the app mid-flash. Swallow it;
                // the per-packet stall watchdog and step-resend keep the flow alive or fail cleanly.
                Log.e(TAG, "OTA notify handling failed", t);
            }
        });
    }

    /** Kept for the BleManager routing; the fire-and-forget writer does not use write completions. */
    void onWriteComplete() {
        // no-op
    }

    // ── File parse ──

    private String[] rawLines = new String[0];

    /** Result of parsing an Intel-HEX file into flash packets + trailer info. */
    private static final class ParseResult {
        final List<Packet> packets = new ArrayList<>();
        byte[] allData = new byte[0];
        String fileCrc = "";
        String fileVer = "";
        int nodeType = -1, projectCode = -1;   // trailer bytes selecting the ver2 target node
    }

    /**
     * Parse Intel-HEX lines into flash packets and read the ":07AAA555" trailer. Stateless so both a
     * live flash and the pre-flight {@link #inspect} can share it. Grouping = linesPerPacket lines
     * (packet length / record length); {@code bank} = the enclosing type-04 record's low address
     * byte, {@code offset} = the 16-bit address of the packet's first data record.
     */
    private static ParseResult parse(String[] rawLines, int linesPerPacket) {
        ParseResult res = new ParseResult();
        StringBuilder allHex = new StringBuilder();
        String currentBank = "00";
        Packet cur = new Packet();
        StringBuilder data = new StringBuilder();
        int count = 0;
        boolean sawData = false;

        for (String raw : rawLines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.length() < 9 || line.charAt(0) != ':') continue;
            String tt = line.substring(7, 9);

            if ("04".equals(tt)) {
                if (data.length() > 0) {
                    cur.data = data.toString();
                    res.packets.add(cur);
                    allHex.append(cur.data);
                    cur = new Packet();
                    cur.bank = currentBank;   // carry the current bank into the next packet
                    data.setLength(0);
                    count = 0;
                }
                if (line.length() >= 13 && isHex(line.substring(11, 13))) { currentBank = line.substring(11, 13); cur.bank = currentBank; }
            } else if ("00".equals(tt)) {
                if (!isHex(line.substring(1, 3))) continue;           // corrupt byte-count field
                int ll = Integer.parseInt(line.substring(1, 3), 16);
                int end = 9 + 2 * ll;
                // Reject a truncated or non-hex data record. This keeps every stored bank/offset hex,
                // so the later addr3() parse (also run mid-flash) cannot throw on a corrupt file.
                if (line.length() < end || !isHex(line.substring(1, end))) continue;
                if (count == 0) cur.offset = line.substring(3, 7);
                data.append(line.substring(9, end));
                count++;
                sawData = true;
                if (count >= linesPerPacket) {
                    cur.data = data.toString();
                    res.packets.add(cur);
                    allHex.append(cur.data);
                    Packet next = new Packet();
                    next.bank = cur.bank;
                    cur = next;
                    data.setLength(0);
                    count = 0;
                }
            }

            // Trailer record ":07AAA555..": node type, project code, sw version, crc16, checksum.
            if (line.length() >= 25 && "07AAA555".equals(line.substring(1, 9)) && isHex(line.substring(9, 23))) {
                res.nodeType = Integer.parseInt(line.substring(9, 11), 16);      // ver2 node type
                res.projectCode = Integer.parseInt(line.substring(11, 13), 16);  // ver2 project code
                res.fileVer = Integer.parseInt(line.substring(13, 15), 16) + "."
                        + Integer.parseInt(line.substring(15, 17), 16) + "."
                        + Integer.parseInt(line.substring(17, 19), 16);
                res.fileCrc = line.substring(19, 23);
            }
        }
        if (data.length() > 0) {
            cur.data = data.toString();
            res.packets.add(cur);
            allHex.append(cur.data);
        }
        if (!sawData) res.packets.clear();

        res.allData = hexToBytes(allHex.toString());
        return res;
    }

    /** Build the instance packet/trailer state for the given lines-per-packet. */
    private void prepareGroups(int linesPerPacket) {
        ParseResult r = parse(rawLines, linesPerPacket);
        packets.clear();
        packets.addAll(r.packets);
        allData = r.allData;
        fileCrc = r.fileCrc;
        fileVer = r.fileVer;
        nodeType = r.nodeType;
        projectCode = r.projectCode;
    }

    /**
     * Pre-flight inspection for the UI (no BLE, no side effects): parse the file, run the integrity
     * CRC16 and read the trailer and report metadata as JSON {@code {ok,name,sizeBytes,packets,
     * fileVer,fileVerMajor,firstAddr,fileCrc,calcCrc,crcOk,targetIsVcu}}. The dashboard combines this
     * with the connected device's state for the compatibility gate. The filename target rule matches
     * the flasher (AWE* = battery, else controller); {@code firstAddr} is the 24-bit flash offset (from
     * {@code 0x08000000}) of the first packet, so the UI can confirm it is a VCU app image
     * (offset >= 0x7000, i.e. above the protected bootloader) and not a bootloader / display image.
     */
    static String inspect(String hexText, String fileName) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            String name = fileName == null ? "" : fileName.trim();
            boolean vcu = !name.toUpperCase(Locale.US).startsWith("AWE");
            int linesPerPacket = (vcu ? 512 : 1024) / 16;
            String[] lines = (hexText == null ? "" : hexText).split("\r?\n");
            ParseResult r = parse(lines, linesPerPacket);
            boolean parsed = !r.packets.isEmpty() && !r.fileCrc.isEmpty();
            String calc = String.format(Locale.US, "%04X", CommandBuilder.crc16Modbus(r.allData, r.allData.length));
            // First-packet flash offset (bank byte + 16-bit offset), the address the bootloader
            // maps to 0x08000000 + offset. A VCU app starts at 0x08007000 -> offset 0x7000.
            int firstAddr = -1;
            if (!r.packets.isEmpty()) {
                int[] a = addr3(r.packets.get(0).bank, r.packets.get(0).offset);
                firstAddr = (a[0] << 16) | (a[1] << 8) | a[2];
            }
            // Trailer major version (e.g. "5.4.19" -> 5), the generation signal used by the gate.
            int major = -1;
            try { if (!r.fileVer.isEmpty()) major = Integer.parseInt(r.fileVer.split("\\.")[0]); } catch (Throwable ignored) {}
            o.put("ok", parsed);
            o.put("name", name);
            o.put("sizeBytes", r.allData.length);
            o.put("packets", r.packets.size());
            o.put("fileVer", r.fileVer);
            o.put("fileVerMajor", major);
            o.put("firstAddr", firstAddr);
            o.put("fileCrc", r.fileCrc);
            o.put("calcCrc", calc);
            o.put("crcOk", parsed && calc.equalsIgnoreCase(r.fileCrc));
            o.put("targetIsVcu", vcu);
        } catch (Throwable t) {
            try { o.put("ok", false); o.put("error", String.valueOf(t)); } catch (Throwable ignored) {}
        }
        return o.toString();
    }

    // ── prepare / START ──

    private void sendPrepare() {
        // Controller: crc over [aa 04 03 ff*16] (19 bytes), no prefix. Battery: crc over
        // [04 03 ff*16] (18 bytes), then prepend 0xaa. The nodes differ in whether 0xaa is inside
        // the crc, so keep both exact.
        byte[] out = new byte[20];
        if (isVcu) {
            int[] p = new int[19];
            p[0] = 0xaa; p[1] = 0x04; p[2] = 0x03;
            for (int i = 3; i < 19; i++) p[i] = 0xff;
            for (int i = 0; i < 19; i++) out[i] = (byte) p[i];
            out[19] = (byte) CommandBuilder.crc8(p, 19);
        } else {
            int[] q = new int[18];
            q[0] = 0x04; q[1] = 0x03;
            for (int i = 2; i < 18; i++) q[i] = 0xff;
            out[0] = (byte) 0xaa;
            for (int i = 0; i < 18; i++) out[1 + i] = (byte) q[i];
            out[19] = (byte) CommandBuilder.crc8(q, 18);
        }
        otaWrite(out);
    }

    private void sendStart() {
        cancel(stepResend);
        retryCount = 0;
        if (!startSent) {
            startSent = true;
            startRetry = new Runnable() {
                @Override public void run() {
                    if (!running) return;
                    // frame [START_ID, aa*8]
                    otaWrite(wire(frame(config.START, fill(8, 0xaa))));
                    if (retryCount < START_MAX_RETRIES) {
                        retryCount++;
                        main.postDelayed(this, START_RETRY_MS);
                    } else {
                        flashState = 2;
                        retryCount = 0;
                        armStartTimeout();
                        fail("The scooter is not answering. Is it switched on and in range?");
                    }
                }
            };
            main.post(startRetry);
        }
    }

    private void armStartTimeout() {
        startTimeout = () -> {
            if (running && flashState == 2) fail("The scooter did not enter update mode");
        };
        main.postDelayed(startTimeout, START_TIMEOUT_MS);
    }

    // ── ver2 node-select handshake (06 e2 / 06 ea) ──

    /** Send the 06 e2 request that selects the target node (type/project code) before START. */
    private void sendHandshake() {
        int sum7 = (0x01 + (nodeType & 0xFF) + (projectCode & 0xFF)) & 0xFF;
        int[] payload = new int[]{0x01, nodeType & 0xFF, projectCode & 0xFF, 0, 0, 0, 0, sum7};
        byte[] hs = wire(frame(config.HANDSHAKE, payload));
        host.log("Asking the scooter for update mode (node " + nodeType + "/" + projectCode + ")");
        otaWrite(hs);
        armStepResend(() -> otaWrite(hs));   // one 3 s resend covers a dropped request
    }

    /** Reply to a controller confirmation request (06 ea 02 a5): proceed (aa) or cancel (55). */
    private void sendConfirmReply(boolean confirm) {
        int b = confirm ? 0xaa : 0x55;
        int sum7 = (0x02 + b) & 0xFF;
        int[] payload = new int[]{0x02, b, 0, 0, 0, 0, 0, sum7};
        byte[] out = wire(frame(config.HANDSHAKE, payload));
        otaWrite(out);
        armStepResend(() -> otaWrite(out));
    }

    // ── Notification handling ──

    private void handleNotify(byte[] v) {
        if (v.length < 12) return;
        int header = v[0] & 0xFF;
        // crc8 over the 10 bytes v[1..10]
        int[] mid = new int[10];
        for (int i = 0; i < 10; i++) mid[i] = v[1 + i] & 0xFF;
        boolean crcOk = (CommandBuilder.crc8(mid, 10) == (v[11] & 0xFF));

        if (header == 0xcc && crcOk) {
            handleResponse(mid);
            return;
        }
        // Corrupted cc frame during an active flash -> re-drive the current packet (100 ms).
        if (startSent && header == 0xcc && !crcOk) {
            main.postDelayed(this::sendNextPacket, CORRUPT_NUDGE_MS);
        }
    }

    private void handleResponse(int[] n) {
        int idHi = n[0], idLo = n[1], status = n[2], reason = n[3];

        // ver2 node-select handshake (06 ea): accept -> START, refuse -> fail, confirm-request -> auto-confirm.
        if (config.HANDSHAKE_RESP != null && match(idHi, idLo, config.HANDSHAKE_RESP)) {
            cancel(stepResend);
            if (status == 0x01) {                     // request ack
                if (reason == 0xaa) {                  // accepted -> proceed to START
                    host.log("The scooter accepted the update request");
                    sendStart();
                } else if (reason == 0x55) {           // refused
                    String why;
                    switch (n[4]) {
                        case 0x01: why = "no such node"; break;
                        case 0x02: why = "this node cannot be updated"; break;
                        case 0x03: why = "project code does not match"; break;
                        case 0x04: why = "controller not in idle state"; break;
                        default:   why = "reason 0x" + Integer.toHexString(n[4]);
                    }
                    fail("The scooter refused the update request (" + why + ")");
                }
            } else if (status == 0x02 && reason == 0xa5) {   // controller asks to confirm -> confirm
                host.log("The controller wants a confirmation. Confirming");
                sendConfirmReply(true);
            } else if (status == 0x03) {               // node progress stream
                host.log("Node progress " + Integer.toHexString(reason) + " " + Integer.toHexString(n[4]));
            }
            return;
        }

        if (match(idHi, idLo, config.START_RESP) && status == 0x55) {
            // START accepted, so stop the START-retry loop. Left running it keeps re-sending START
            // every 1 s in the background and after 10 tries (~10 s = about packet 4-5) aborts the
            // flash for no answer. THIS was the deterministic packet-4/5 abort, independent of the
            // write pacing.
            cancel(startRetry);
            host.log("The scooter is in update mode");
            flashState = 3;
            armGlobalWatchdog();
            cancel(stepResend);
            main.postDelayed(() -> { if (running) sendInfo(); }, 200);
            return;
        }
        if (match(idHi, idLo, config.INFO_RESP)) {
            host.log("Image header acknowledged. Sending " + packets.size() + " packets");
            cancel(stepResend);
            packetIndex = 0;
            packetRetries = 0;
            reportProgress("Sending");
            sendPackInfo();
            return;
        }
        if (match(idHi, idLo, config.PACKINFO_RESP)) {
            cancel(stepResend);
            host.log("Packet " + (packetIndex + 1) + " header acknowledged");
            pumpPackData();
            return;
        }
        if (match(idHi, idLo, config.PACKDATA_RESP)) {
            if (status == 0xaa) {
                packetIndex++;
                packetRetries = 0;
                sendNextPacket();
            } else {
                // reason: 01 = receive timeout, 02 = frame loss, 03 = flash write fail
                host.log("Packet " + (packetIndex + 1) + " error (" + Integer.toHexString(status) + "/"
                        + Integer.toHexString(reason) + ")");
                packetFailed = true;
                if (packetRetries >= PACKET_MAX_RETRIES) {
                    fail("The scooter kept rejecting packet " + (packetIndex + 1) + "/" + packets.size());
                    return;
                }
                packetRetries++;
                main.postDelayed(this::sendNextPacket, PACKDATA_ERR_RETRY_MS);
            }
            return;
        }
        if (match(idHi, idLo, config.FINISH_RESP)) {
            cancel(stepResend);
            if (status == 0xaa) {
                host.log("The scooter verified the image checksum");
                flashState = 1;
                succeed();
            } else {
                if (status == 0x55) armFinishCheck("The scooter found a checksum error in the image");
                else if (status == 0xa5) armFinishCheck("The scooter stopped answering at the end");
                else armFinishCheck("");
            }
        }
    }

    // ── INFO / PACKINFO / PACKDATA / FINISH senders ──

    private void sendInfo() {
        Packet p0 = packets.get(0);
        int[] payload = new int[8];
        // [0..2] start address = bank + offset (3 bytes)
        int[] addr = addr3(p0.bank, p0.offset);
        payload[0] = addr[0]; payload[1] = addr[1]; payload[2] = addr[2];
        // [3..5] total firmware byte length (3 bytes, big-endian)
        int total = allData.length;
        payload[3] = (total >> 16) & 0xFF; payload[4] = (total >> 8) & 0xFF; payload[5] = total & 0xFF;
        // [6..7] CRC16 hi/lo
        int crc = CommandBuilder.crc16Modbus(allData, allData.length);
        payload[6] = (crc >> 8) & 0xFF; payload[7] = crc & 0xFF;

        host.log("Sending the image header (size and checksum)");
        otaWrite(wire(frame(config.INFO, payload)));
        armStepResend(() -> otaWrite(wire(frame(config.INFO, payload))));
    }

    private void sendPackInfo() {
        if (packetIndex < 0 || packetIndex >= packets.size()) return;   // out-of-state response guard
        Packet p = packets.get(packetIndex);
        int[] payload = new int[8];
        int[] addr = addr3(p.bank, p.offset);
        payload[0] = addr[0]; payload[1] = addr[1]; payload[2] = addr[2];
        int plen = p.data.length() / 2;
        payload[3] = (plen >> 16) & 0xFF; payload[4] = (plen >> 8) & 0xFF; payload[5] = plen & 0xFF;
        payload[6] = packetIndex & 0xFF;
        payload[7] = packets.size() & 0xFF;

        host.log("Sending packet " + (packetIndex + 1) + "/" + packets.size());
        byte[] frame = wire(frame(config.PACKINFO, payload));
        otaWrite(frame);
        armStepResend(() -> otaWrite(frame));
    }

    private void pumpPackData() {
        if (packetIndex < 0 || packetIndex >= packets.size()) return;   // stale PACKINFO response
        packetFailed = false;
        clearPendingSends();
        byte[] data = hexToBytes(packets.get(packetIndex).data);
        int frameCount = data.length / 7 + 1;
        host.log("Packet " + (packetIndex + 1) + ": sending " + frameCount + " data frames");
        int pos = 0;
        for (int m = 0; m < frameCount; m++) {
            int[] payload = new int[8];
            payload[0] = m & 0xFF;                            // dataIndex
            for (int k = 1; k < 8; k++) payload[k] = 0xFF;   // default 0xff for the 7 data slots
            for (int k = 0; k < 7 && pos < data.length; k++) payload[1 + k] = data[pos++] & 0xFF;
            final byte[] out = wire(frame(config.PACKDATA, payload));
            final Runnable r = () -> { if (running && !packetFailed) otaWrite(out); };
            pendingSends.add(r);
            main.postDelayed(r, (long) (m + 1) * INTERFRAME_MS);   // one inter-frame delay apart
        }
        // The VCU answers with one PACKDATA response (07 54) once it has received the whole packet.
    }

    private void sendNextPacket() {
        if (!running) return;
        cancel(stuckTimer);
        clearPendingSends();   // cancel any scheduled PACKDATA from a stalled packet before re-driving
        reportProgress("Sending");
        if (packetIndex < packets.size()) {
            sendPackInfo();
            // Per-packet stall watchdog. Ticks every 5 s and re-drives this packet if it errored
            // (packetFailed) or got no PACKDATA response within PACKET_STALL_TICKS. A stale tick (the
            // packet already advanced) no-ops. After PACKET_MAX_RETRIES on one packet, give up.
            final int pkt = packetIndex;
            stuckTimer = new Runnable() {
                int ticks = 0;
                @Override public void run() {
                    if (!running || packetIndex != pkt) return;   // packet advanced -> stale timer
                    boolean stalled = packetFailed || (++ticks >= PACKET_STALL_TICKS);
                    if (!stalled) { main.postDelayed(this, STUCK_RECOVERY_MS); return; }
                    if (packetRetries >= PACKET_MAX_RETRIES) {
                        fail("No answer to packet " + (pkt + 1) + "/" + packets.size()
                                + ". Is the scooter switched on and in range?");
                        return;
                    }
                    packetRetries++;
                    host.log("Packet " + (pkt + 1) + " stuck. Re-sending (attempt " + (packetRetries + 1) + ")");
                    sendNextPacket();   // re-drive the same packet (packetIndex unchanged)
                }
            };
            main.postDelayed(stuckTimer, STUCK_RECOVERY_MS);
        } else {
            // all packets done -> FINISH
            host.log("All packets sent. Asking the scooter to verify the image");
            byte[] finish = wire(frame(config.FINISH, fill(8, 0xaa)));
            otaWrite(finish);
            armStepResend(() -> { otaWrite(finish); armFinishCheck(""); });
        }
    }

    // ── success / failure / recovery ──

    private void armFinishCheck(String msg) {
        if (!msg.isEmpty()) host.log(msg);
        cancel(finishCheck);
        finishCheck = () -> {
            if (!running || flashState == 1) return;
            // The controller stops streaming telemetry in the bootloader, so there is no version to
            // read back here. Fall through to a capped restart-from-START recovery instead.
            if (restarts >= MAX_RESTARTS) {
                fail(msg.isEmpty() ? "The firmware update did not complete" : msg);
                return;
            }
            restarts++;
            host.log("Starting over (attempt " + (restarts + 1) + ")");
            main.postDelayed(() -> {
                if (!running) return;
                startSent = false;
                sendStart();
            }, 1000);
        };
        main.postDelayed(finishCheck, FINISH_CHECK_MS);
    }

    private void succeed() {
        host.log("The scooter took the firmware and is restarting");
        host.progress(100, packets.size(), packets.size(), "Done");
        finish(true, "success", "Firmware updated");
    }

    private void fail(String message) {
        host.log(message);
        finish(false, "failed", message);
    }

    private void finish(boolean success, String state, String message) {
        running = false;
        clearAllTimers();
        clearPendingSends();
        try { host.setHighPriority(false); } catch (Throwable ignored) {}
        host.state(state, message);
        try { host.finished(success); } catch (Throwable ignored) {}
    }

    // ── writer (fire-and-forget, which is what the bootloader sustains) ──
    // No-response writes are NOT chained on the write-completion callback: the VCU bootloader does
    // not reliably deliver that callback for no-response writes across a whole flash, which stalled
    // the old pump after a few packets. Instead control frames are written immediately (a lost one
    // is recovered by the 3 s step-resend) while PACKDATA frames go out one INTERFRAME_MS apart.

    /** Write one frame fire-and-forget; one quick retry if the stack was momentarily busy. */
    private void otaWrite(byte[] frame) {
        if (frame == null) return;
        boolean ok = false;
        try { ok = host.writeFrame(frame); } catch (Throwable t) { Log.e(TAG, "writeFrame failed", t); }
        if (!ok) main.postDelayed(() -> {
            if (running) { try { host.writeFrame(frame); } catch (Throwable ignored) {} }
        }, 25);
    }

    /** Cancel any scheduled-but-unsent PACKDATA frames (on packet advance, re-drive or finish). */
    private void clearPendingSends() {
        for (Runnable r : pendingSends) main.removeCallbacks(r);
        pendingSends.clear();
    }

    // ── timers ──

    private void armStepResend(Runnable action) {
        cancel(stepResend);
        stepResend = () -> { if (running) action.run(); };
        main.postDelayed(stepResend, STEP_RESEND_MS);
    }

    private void armGlobalWatchdog() {
        cancel(watchdog);
        watchdog = () -> { if (running && flashState == 3) fail("The update ran out of time (30 min)"); };
        main.postDelayed(watchdog, GLOBAL_WATCHDOG_MS);
    }

    private void cancel(Runnable r) {
        if (r != null) main.removeCallbacks(r);
    }

    private void clearAllTimers() {
        cancel(startRetry); cancel(stepResend); cancel(stuckTimer);
        cancel(watchdog); cancel(startTimeout); cancel(finishCheck);
        main.removeCallbacksAndMessages(null);
    }

    private void resetState() {
        startSent = false; flashState = 0; packetIndex = 0; retryCount = 0;
        restarts = 0; packetFailed = false;
        clearPendingSends();
    }

    private void reportProgress(String phase) {
        int count = packets.size();
        int percent = count > 0 ? (int) ((long) (packetIndex + 1) * 100 / count) : 0;
        if (percent > 100) percent = 100;
        host.progress(percent, Math.min(packetIndex + 1, count), count, phase);
    }

    // ── framing helpers ──

    /** Build a 10-int OTA frame: {@code [idHi, idLo, payload0..payload7]}. */
    private static int[] frame(int[] id, int[] payload8) {
        int[] f = new int[10];
        f[0] = id[0]; f[1] = id[1];
        for (int i = 0; i < 8; i++) f[2 + i] = payload8[i] & 0xFF;
        return f;
    }

    /** Wrap a 10-int frame into the 12-byte wire form {@code 0xbb [frame10] crc8}. */
    private static byte[] wire(int[] frame10) {
        byte[] out = new byte[12];
        out[0] = (byte) 0xbb;
        for (int i = 0; i < 10; i++) out[1 + i] = (byte) (frame10[i] & 0xFF);
        out[11] = (byte) CommandBuilder.crc8(frame10, 10);
        return out;
    }

    private static int[] fill(int len, int value) {
        int[] a = new int[len];
        for (int i = 0; i < len; i++) a[i] = value & 0xFF;
        return a;
    }

    private static boolean match(int idHi, int idLo, int[] id) {
        return idHi == (id[0] & 0xFF) && idLo == (id[1] & 0xFF);
    }

    /** 3 address bytes from bank (2 hex) + offset (4 hex): [bank, offset hi, offset lo]. */
    private static int[] addr3(String bank, String offset) {
        String s = (bank == null || bank.isEmpty()) ? "00" : bank;
        String b = (offset == null || offset.isEmpty()) ? "0000" : offset;
        String r = (s + b);
        while (r.length() < 6) r = "0" + r;
        return new int[]{
                Integer.parseInt(r.substring(0, 2), 16),
                Integer.parseInt(r.substring(2, 4), 16),
                Integer.parseInt(r.substring(4, 6), 16)
        };
    }

    private static boolean isHex(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))) return false;
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
