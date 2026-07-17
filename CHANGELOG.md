# Changelog

Notable changes to Laufbursche Edition, newest first.

Versions are `1.0.<n>` where `n` is the number of commits after the initial one, so the number rises by one on every release with no manual editing. The first public release is 1.0.0.

Release notes are built automatically for each release: if this file has a section whose heading matches the released version it is used verbatim, otherwise the commit subjects since the previous release are listed. Either way a fixed Disclaimer and a "phoning home" note are appended (see `.github/release-footer.md`).

To hand-write the notes for a release, add a section headed with its version number at the top of the version list below, for example:

    ## 1.0.1
    - Fixed the light-mode toast readability
    - Corrected the anti-theft help text

If no matching section exists the notes fall back to the commit messages, so keeping this file up to date is optional.

## 1.0.7

- Firmware update: fixed the flash always aborting around the 4th or 5th packet with "No response to the update request". The internal start-retry loop was not stopped once the controller accepted the start, so it kept counting in the background and aborted the flash after about ten seconds. The flash now runs to the end.

## 1.0.6

- Firmware update: reworked the flash to match the original app exactly, which fixes it stalling after a few packets on some controllers. Data packets now go out fire-and-forget at a fixed pace (no waiting on write acknowledgements) and the app no longer requests a fast connection interval.
- Firmware update log: OTA lines are now written to the debug log too (when Debug mode is on), so a flash done away from the computer can be reviewed afterwards.

## 1.0.5

- Firmware update: fixed the flash stalling after a few packets. It now writes to the controller without response (like the original app) with a self-healing per-packet watchdog, so a full flash runs to the end.
- VCU speed tile: triple-tap it to toggle the speed lock. This removes or restores the "DE" in the FIN over the identity command (Gate 1). The speed number is red when the FIN has no "TDE" (unlocked) and green when it does (locked). On firmware where the display clamp is patched out this is a live lock/unlock.
- Scooter settings: the per-gear and main "speed limit" are power limits in percent, not km/h. Relabeled to "Power limit" (%) with a 0-100 range and corrected help.
- Firmware update page: the Choose file, Start and Cancel buttons now match the app's button style.

## 1.0.4

- Tidied the "Firmware update" menu entry: it now matches the other menu buttons and sits at the bottom of the settings sheet, just above Version Info & Disclaimer.

## 1.0.3

Firmware update over Bluetooth - flash controller firmware straight from the app.

- New "Firmware update" entry under Settings -> Scooter opens a dedicated page: pick a controller `.hex`, review the pre-flight checks then flash. A progress bar, a live log plus a Cancel button run throughout.
- Byte-for-byte reimplementation of the original app's local-file flasher (VCU/BMS) so the exact same update protocol runs natively, no cloud account needed.
- Safety checks before anything is written: file integrity (CRC16), that the file is a controller app image, the controller-versus-battery target plus a firmware-generation match against the installed version. A checklist shows what passed and Start stays disabled until the critical checks pass. An informed override is available for edge cases, but a corrupt file can never be flashed.
- The screen stays on for the whole ~13-minute flash. An interrupted flash leaves the controller in update mode so it can simply be flashed again - it is not bricked.

## 1.0.0

First official public release. All details are shown in the [README](https://github.com/Laufbursche42/tr-lb-edition/blob/main/README.md).
