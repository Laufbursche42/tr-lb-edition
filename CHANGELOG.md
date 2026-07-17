# Changelog

Notable changes to Laufbursche Edition, newest first.

Versions are `1.0.<n>` where `n` is the number of commits after the initial one, so the number rises by one on every release with no manual editing. The first public release is 1.0.0.

Release notes are built automatically for each release: if this file has a section whose heading matches the released version it is used verbatim, otherwise the commit subjects since the previous release are listed. Either way a fixed Disclaimer and a "phoning home" note are appended (see `.github/release-footer.md`).

To hand-write the notes for a release, add a section headed with its version number at the top of the version list below, for example:

    ## 1.0.1
    - Fixed the light-mode toast readability
    - Corrected the anti-theft help text

If no matching section exists the notes fall back to the commit messages, so keeping this file up to date is optional.

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
