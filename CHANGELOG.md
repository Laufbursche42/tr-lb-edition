# Changelog

Notable changes to Laufbursche Edition, newest first.

The version series lives in `version.properties`, which both the gradle build and the release workflow read. `versionName` is `<major>.<minor>.<n>` where `n` counts the commits since the series began, so the number rises by one on every release with no manual editing. `versionCode` counts straight through a series change and never goes backwards.

Release notes are built automatically for each release: if this file has a section whose heading matches the released version it is used verbatim, otherwise the commit subjects since the previous release are listed. Either way a fixed Disclaimer and a "phoning home" note are appended (see `.github/release-footer.md`).

To hand-write the notes for a release, add a section headed with its version number at the top of the version list below, for example:

    ## 1.0.1
    - Fixed the light-mode toast readability
    - Corrected the anti-theft help text

If no matching section exists the notes fall back to the commit messages, so keeping this file up to date is optional.

## 1.1.2

The main screen is now laid out for reading at speed. How it looks is yours to set.

### The main screen fits on one screen

Everything a rider reads while moving, speed, battery, gear, current, the motors and the quick switches, is scaled to fill exactly one screen. Nothing that matters at speed needs a scroll any more. The detail sections start below the bottom edge and are still reached by swiping up.

### Pack current now reads the way a rider thinks

Current drawn from the pack shows as a negative number in red, recuperation as a positive number in green. The number itself is unchanged, only the sign follows the rider's point of view instead of the controller's.

### Eight switches under the motor tiles

Front, Rear, Both and Smart moved out of the heading into their own row. A second row was added for kick start, ABS, eco and cruise control. Each one writes to the scooter the moment it is tapped, carrying only the setting that was touched. Each mirrors what the scooter actually reports. Cruise control greys out while the scooter is in its road-legal state, because the controller refuses that write and a button that silently does nothing is worse than one that says why.

### Display settings

A new entry under Display opens colour settings kept separately for dark and light mode: the tile colour, the page background and one brightness slider that dims the text and every coloured readout together. Riders on an OLED screen can set the background to pure black, which switches those pixels off instead of lighting them dimly.

### Smaller things

- A ride that is recording now shows one floating marker with distance, time and a stop button. It starts above the motor tiles and can be dragged anywhere on the screen; where it is put is remembered.
- Each motor tile carries a single value with its unit underneath. The currents moved into a second row of tiles rather than crowding the first.
- Park replaces the gear number itself instead of adding a small marker beneath it.
- The turn-signal indicators had left and right the wrong way round.

## 1.1.0

A series step rather than a patch: a second language, terms that have to be accepted before the
first ride, two repairs a rider will have noticed and the firmware patcher handed to the web.

**This release needs Android 10.** The floor moved up from Android 8 so that a recorded route can
be written into the Downloads folder without asking for a storage permission, a route Android has
been closing off for years. On Android 8 and 9 the previous version keeps working, with no further
updates.

### Repairs

- **A GPX export never arrived on the phone.** It reported a file name and stored nothing, because
  the export took a path that only exists in a different kind of app shell. The file now lands in
  the Downloads folder, where a file manager and a mail app can reach it. A second export of the
  same route gets a counter instead of overwriting the first.
- **Wheel size and cruise control overwrote your other gears.** Saving either one rewrote every
  gear from a cache the app kept, so a value that had gone stale could be put back. Both are single
  values in the controller, so one write is enough. The cache is gone. Look over your gear settings
  once if something ever seemed to have moved by itself.

### Terms before the first ride

- The liability terms open on the very first start and have to be accepted before anything else.
  Neither the backdrop nor Escape gets rid of that window while it is asking.
- They are readable again at any time from **Version Info & Disclaimer** as well as from the
  firmware page, both opening the same text with a close button instead of a tick box.
- The firmware page carries its own tick box saying they were read. Without it **Start update**
  stays dead and the hint underneath says why. The tick is asked again every time the page opens
  rather than remembered.

### The app speaks German

- The switch sits in the display settings and a first start follows the phone. English stays the
  base language, so a string nobody has translated yet shows English rather than a raw key.
- German covers the dashboard. The navigation screen, the map download screen and a handful of
  Android messages are still English.

### Recorded routes

- **Saved routes** is **Recorded routes** everywhere: on the settings button, the page title, the
  heading and the empty message. The button also left the **Scooter** group for **GPS recording**,
  next to the switches that produce the routes.
- An export now reports what the app really did, either the file name that was written or a red
  message with the reason it failed.

### Firmware

- Building a firmware moved to
  [laufbursche42.github.io/tr-fw](https://laufbursche42.github.io/tr-fw/), which asks which build
  fits a given scooter. The same patcher had to be kept in step in three places at once, here, in
  the iOS app and on the web page. Building now needs a browser and the stock image of your own
  scooter, because no firmware image ships inside the APK any more. Flashing still happens in the
  app.
- The warning naming the board plus the models these firmwares must not be flashed on moved from
  the patcher screen to the page that does the flashing.
- The wheel-size help no longer points at the in-app patcher.

### A what-is-new window

- After an update it names in a few points what a rider notices. Closing it counts as read and it
  reopens at any time from the settings.

Under the surface the styling left the page for its own stylesheet, with a policy that forbids a
style from being injected into the markup. The version series is defined in one file that both the
build and the release workflow read. The README handed everything about the scooter's firmware to
the patcher's own repository.

---

## 1.0.7

- Firmware update: fixed the flash always aborting around the 4th or 5th packet with "No response to the update request". The internal start-retry loop was not stopped once the controller accepted the start, so it kept counting in the background and aborted the flash after about ten seconds. The flash now runs to the end.

## 1.0.6

- Firmware update: reworked the flash to the pacing the bootloader sustains, which fixes it stalling after a few packets on some controllers. Data packets now go out fire-and-forget at a fixed pace (no waiting on write acknowledgements) and the app no longer requests a fast connection interval.
- Firmware update log: OTA lines are now written to the debug log too (when Debug mode is on), so a flash done away from the computer can be reviewed afterwards.

## 1.0.5

- Firmware update: fixed the flash stalling after a few packets. It now writes to the controller without response, with a self-healing per-packet watchdog, so a full flash runs to the end.
- VCU speed tile: triple-tap it to toggle the speed lock. This removes or restores the "DE" in the FIN over the identity command (Gate 1). The speed number is red when the FIN has no "TDE" (unlocked) and green when it does (locked). On firmware where the display clamp is patched out this is a live lock/unlock.
- Scooter settings: the per-gear and main "speed limit" are power limits in percent, not km/h. Relabeled to "Power limit" (%) with a 0-100 range and corrected help.
- Firmware update page: the Choose file, Start and Cancel buttons now match the app's button style.

## 1.0.4

- Tidied the "Firmware update" menu entry: it now matches the other menu buttons and sits at the bottom of the settings sheet, just above Version Info & Disclaimer.

## 1.0.3

Firmware update over Bluetooth - flash controller firmware straight from the app.

- New "Firmware update" entry under Settings -> Scooter opens a dedicated page: pick a controller `.hex`, review the pre-flight checks then flash. A progress bar, a live log plus a Cancel button run throughout.
- Speaks the update protocol of the controller's own bootloader (VCU/BMS) natively, so a `.hex` already on the phone can be flashed with no cloud account.
- Safety checks before anything is written: file integrity (CRC16), that the file is a controller app image, the controller-versus-battery target plus a firmware-generation match against the installed version. A checklist shows what passed and Start stays disabled until the critical checks pass. An informed override is available for edge cases, but a corrupt file can never be flashed.
- The screen stays on for the whole ~13-minute flash. An interrupted flash leaves the controller in update mode so it can simply be flashed again - it is not bricked.

## 1.0.0

First official public release. All details are shown in the [README](https://github.com/Laufbursche42/tr-lb-edition/blob/main/README.md).
