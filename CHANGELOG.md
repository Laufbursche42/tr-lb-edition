# Changelog

Notable changes to Laufbursche Edition, newest first.

The version series lives in `version.properties`. `versionName` is `<major>.<minor>.<n>` where `n` is the release count: it advances by exactly one per push/release (bumped by the CI workflow), never by individual commits. `versionCode` never goes backwards.

Release notes are built automatically for each release: if this file has a section whose heading matches the released version it is used verbatim, otherwise the commit subjects since the previous release are listed. Either way a fixed Disclaimer and a "phoning home" note are appended (see `.github/release-footer.md`).

To hand-write the notes for a release, add a section headed with its version number at the top of the version list below, for example:

    ## 1.0.1
    - Fixed the light-mode toast readability
    - Corrected the anti-theft help text

If no matching section exists the notes fall back to the commit messages, so keeping this file up to date is optional.

## 1.1.37

### Fixes

- **The "Recorded routes" button opens again.** It was opening the routes list behind the full-screen Settings page, so nothing appeared to happen - it now closes Settings first.
- **The app installs on every phone again.** Release APKs were signed with the v2 scheme only, which Xiaomi/MIUI (and some other installers) reject as "invalid". Builds now carry v1 (JAR) + v2 signatures, and the release pipeline refuses to publish an APK that is not v1-signed.

## 1.1.35

### New

- **GPS route recording survives the screen going off.**  A native foreground service now logs the ride's GPS track to disk, so the full route is recorded even with the screen locked or the app in the background. The previous WebView-only recording was throttled and stopped after a few hundred metres. The track arms automatically once the scooter has moved ~20 m and stops when it disconnects; finished tracks are imported into the routes list when the app is next in the foreground.

### Improvements

- **GPS speed reads zero at a standstill.**  The live GPS speed no longer shows a phantom crawl while the scooter is stopped; a position accuracy gate and a standstill clamp keep it at 0.
- **Settings fields scroll above the on-screen keyboard.**  When the soft keyboard opens over a settings input, the field is pulled into the visible area.
- **Style editor colour pickers seed more reliably.**  A colour variable that resolves to a computed (non-hex) value no longer blanks its picker; it falls back to the theme default instead.
- **Safer JS bridging.**  Native-to-WebView messages that carry a payload now pass it as a fully escaped argument.

## 1.1.34

### Bug fixes

- **Theme transparency works in every theme.**  The tile and button transparency sliders in the Style editor now take effect in the Default theme too, not only in custom themes.
- **Gauge-transparency control only when there is a gauge.**  The gauge-transparency slider is hidden for themes without a gauge (such as the Default theme), where it had no effect.

## 1.1.33

### Bug fixes

- **Android back button works on all modern devices.**  Replaced the deprecated `onBackPressed()` path with the Android 13+ `OnBackInvokedDispatcher` API.  Devices running Android 13-16 and Samsung Galaxy phones (One UI 6+) no longer exit the app when pressing back from a sub-page; `onBackPressed()` remains as fallback for Android 12 and below.
- **Back from sub-pages goes directly to the main screen.**  Opening Scooter Info, Battery Info or Error Reports now closes the Settings sheet first, so one back press returns to the main screen instead of leaving the Settings sheet open underneath.

### New

- **Reorder quick buttons into hidden slots.**  While dragging a quick-bar button, hidden buttons appear as faint placeholder slots so you can drop a button onto any position, including a currently hidden one. The drag grid stays four per row, so it never runs off the screen.
- **Show-all tiles button.**  The tile editor has a new button that re-shows every hidden tile in one tap.

## 1.1.32

### Bundled themes

- **Carbon and Lambo ship pre-installed.**  Both appear in the Theme Manager on first launch - seeded once at install, never overwriting user modifications.

### Style Editor

- **Live colour and size editing.**  All 24 CSS custom properties - dark-mode and light-mode colours, accent colours, font sizes and tile heights - are directly editable per theme and survive APK updates.
- **Three transparency controls.**  Tile transparency (`--tile-alpha`), Button transparency (`--btn-alpha`) and Gauge transparency (`--gauge-alpha`) range sliders. 0 % = solid (default), 100 % = invisible.
- **Full reset.**  One button clears all per-theme overrides (colours, tile order, button row) and restores the theme's built-in defaults.

### Theme system

- **Full-scope themes**  -  set `"scope":"full"` in `theme.json` to replace the entire dashboard (fold AND scroll tiles). Default `"fold"` scope is unchanged.
- **Theme ZIP bundles**  -  themes are shared as a single `.zip` (theme.html + theme.css + theme.json). Compatible across all Laufbursche apps.
- **Per-tile and per-field show/hide.**  Every dashboard element is independently shown or hidden: each speed drum (VCU / GPS), each hero cell, each motor row, and every data-grid row.
- **Quick-button row overhaul.**  Each slot uses a dropdown to select its function (all model functions, no cap). More than 4 visible buttons wrap onto a second row (max 4 per row). Drag to reorder; row alignment (Fill / Center / Edges) configurable.
- **Tile drag-to-reorder**  -  long-press any tile for 500 ms, drag to a new position. Order saved per theme; cross-parent moves supported.
- **Custom i18n strings**  -  themes can ship translated strings in `theme.json` under a `strings` block (DE + EN).
- **Sanitizer hardening**  -  `<iframe>` and `data:` URIs stripped at import and load.
- **Dashboard lock**  -  a toggle in Settings prevents long-press tile drag while riding.

### Theme creation guide

- **Complete SVG speed gauge technique.**  Arc geometry (r = 58, 273 ° arc), CSS animation with `--cur-speed`, unit-label toggle and inline SVG icons - documented step by step.
- **Orbit worked example.**  A full-scope example theme with SVG gauge, data grid, icon button row and all three transparency vars, with line-by-line comments.
- **Back-to-top button.**  A floating scroll-to-top button in the guide appears after scrolling down.
- **AI-ready raw link.**  Both the in-app guide and THEME-GUIDE.md now show the raw GitHub URL so an AI assistant can read the full spec directly.

### Bug fixes and i18n

- **Theme flash fix.**  Active theme and saved style overrides are applied before the first paint. No flash of the default layout on startup or when switching themes.
- **i18n: all theme-editor strings are now bilingual.**  Every label in the Style Editor, Tile Manager and Button Row Editor is translated in both English and German. Custom theme `cssVars` labels accept an i18n key, a `{"en": …, "de": …}` object or a plain string.
- **Import theme: fixed crash after file selection.**  Null-dereference in the speed-unit DOM update fixed with null guards.
- **CSP compliance: inline styles removed.**  Five `style="display:none"` attributes moved to `dashboard.css`; runtime show/hide via CSSOM is unaffected.
- **Button row: drag into hidden slots now works.**  Hidden slots appear as ghost placeholders during drag.
- **Security: WebView debugging**  -  remote debugging enabled only in debug builds.
- **Security: removed disclaimer auto-accept**  -  development shortcut removed from release builds.

### Documentation

- **[THEME-GUIDE.md](THEME-GUIDE.md)**  -  complete guide: bundle format, live-value hooks, tile registration, CSS variables, SVG gauge technique, Orbit full-scope example, label localization for theme authors.

## 1.1.31
- Theme Manager Phase 1: import an HTML + CSS file pair from local storage; the imported theme is stored in app-private storage and applied as a dashboard overlay; live values are bound to `data-lb` hooks in the theme HTML every 500 ms tick. Themes can be activated, exported (Android share intent) and deleted from a new Theme manager screen in Display settings. The built-in default layout is always available and reachable via Activate. Security: script content (script tags, inline event handlers, javascript: URLs, external http/https URLs) is stripped from imported HTML before storage; theme code never runs.

## 1.1.30
- Firmware patcher: new "Permanently open" build that removes the speed limiter for good (Spooonky-style - the four 22 km/h clamps in the controller frame), so the scooter runs open after every restart with no switchable lock. Private property only, not eKFV-legal on public roads.

## 1.1.29

- Display settings now has a Font sizes section: set the speed value and its label, the upper tiles and each lower tile row (Tile row 1 and Tile row 2) independently, each with its own range. Reset puts them all back to the built-in sizes.
- Report issue: the Version / disclaimer dialog has a new "Report issue" button that opens a pre-filled GitHub issue with the app version, Android version, phone model and your (already masked) debug log attached. No personal data is collected.

## 1.1.28

### Hardening: log injection in the patcher bridge

The native LB.otaStagePatched bridge logged the JS-provided file name verbatim; a name containing CR/LF could forge extra log lines (CodeQL java/log-injection). The name is now stripped of line breaks before logging. No behaviour change.

## 1.1.27

### What is new text updated for the guided patcher

The in-app "What is new" now describes the guided firmware patcher - the same build, speed-clamp and blinker options as the web patcher - instead of the older compact wording. No functional change; the patcher itself shipped in 1.1.26.

## 1.1.26

### Guided firmware patcher in the app

The in-app firmware patcher now walks you through the build the same way the web patcher does, without leaving the app. Once a picked file is recognised as a stock image (R5.4.19 / R5.4.21), the Patch options block shows the build selector with the version each build reports over Bluetooth right in the dropdown (Standard - V48, Kickstart - V248, EEPROM reset - no reported version) and a short note under it explaining what the chosen build actually does. The two speed clamps (never unlocked since power on / after unlocking again) and the blinker fix appear only for a build that carries the lock; EEPROM reset shows a short "not applicable" note in their place. Pressing Build patches the image, stages it for the flasher so Start update flashes it, and offers Save to Downloads - and now also prints a build summary (based-on file, stock version, reported version, both clamps, app bytes, CRC and the patch groups included). Any file that is not a recognised stock image is still left untouched and flashes as-is.

## 1.1.25

### Firmware page now opens on every Fighter Mini

The firmware update and patcher page is gated to the Fighter Mini family, but the model auto-detection read the FIN at fixed character positions and could miss the model code on some names (e.g. the eKFV format), classifying a real Fighter Mini as "another model" and hiding the firmware page. Detection now matches the FM-family code (FMA / FME / FMO / FMP) anywhere in the FIN, so a Fighter Mini (incl. Pro / eKFV) is recognised regardless of the exact name length and the firmware page opens as it should. If auto-detection ever still misses, you can pick the model by hand under Settings - Model.

## 1.1.24

### Firmware patcher back in the app

The firmware page can now patch a stock image in the app, so the whole flow stays native and no browser or Web Bluetooth is needed - which keeps the flash reliable on phones (notably Samsung) where the browser Bluetooth link drops mid-flash. Pick a stock firmware file and, when it is recognised as a stock image (R5.4.19 / R5.4.21), a Patch options block appears: choose the build (standard, kickstart or EEPROM reset), the turn-signal fix and the locked / after-unlock speed caps, then press Build. The app patches the image, lets you save the result to Downloads and stages it for the flasher, so Start update flashes the patched firmware over the app's own stable Bluetooth. Any file that is not a recognised stock image is left untouched and still flashes as-is, exactly as before.

## 1.1.23

### Versioning: no more gaps in the changelog

The patch number is now derived from the count of commits that actually change the app (code and assets), not the raw commit count. Documentation, CI and git-hook commits no longer advance the version, so from here on every released version is consecutive and carries exactly one changelog entry. (The one-time jump from 1.1.16 to 1.1.22 predates this change and stays as it is.) `versionCode` still counts every commit, so an update always installs over the previous one.

## 1.1.22

### Warning banners no longer cover the header

The wrong-firmware banner (and the debug-mode banner) used to be pinned over the very top of the screen, hiding the title and the connect / theme / settings buttons underneath it. Banners now sit as a thin line in the normal layout flow directly under the topbar, so they can never overlap the header again. The Blade-on-wrong-firmware warning ("Firmware 3.4.6 required for Blade Mini Ultra") is now a slim strip beneath the icons instead of a full-width block covering the controls.

## 1.1.16

### Blade Mini comments corrected - no functional change

A maintenance follow-up to 1.1.15 that only rewrites the in-code comments around the Blade Mini de-restrict tile: the red state is sticky while a Blade Mini stays connected, and a Blade Mini's FIN does not change when it is de-restricted (it de-restricts through its per-gear speed, not a rename like the Fighter Mini). No rider-facing behaviour changed.

## 1.1.15

### Blade Mini: the speed number goes red in every tuned gear, not just the first

The red "de-restricted" colour used a threshold of 30, so a gear tuned to a value between the eKFV-legal cap and 30 (a moderate 25-28, say) stayed green while a higher first gear went red. The tile now turns red whenever the active gear's per-gear speed is above the eKFV cap of 22 - so every gear set above legal shows red, whatever value you pick. The same threshold now decides lock/unlock for the triple-tap.

## 1.1.14

### Blade Mini unlock-speed field moved into Scooter Settings, and the red stays red

- The "Blade Mini unlock speed" fields now live inside Scooter Settings (the gear menu -> Scooter settings), next to the other scooter parameters, instead of in the top-level settings list where they were easy to miss. They are still Blade-Mini-only and are saved instantly (not sent with the Save button).
- Model detection is now sticky: once a Blade Mini is connected it stays recognised through the shorter unlocked (tuning) FIN, so the speed number stays red while de-restricted, the settings block stays visible, and the triple-tap keeps writing the Blade values instead of falling back to the "Fighter only" path.

## 1.1.13

### Correct Blade Mini pack voltage

The Blade Mini voltage tile under-read (for example 46 V while the scooter's own display showed 56 V). The app had taken the voltage from a battery-frame field that is not the true pack voltage on the Blade. It now reads the whole-volt pack voltage the controller reports in its config frame (55 71) - the same source the scooter display and the web unlock tool use - so the tile matches the display. The Fighter Mini keeps its precise smart-BMS reading unchanged.

## 1.1.12

### The Blade Mini unlock-speed block now appears reliably

The "Blade Mini unlock speed" settings block is now revealed the moment the model is detected or picked, and it no longer disappears in the unlocked (tuning) state, whose shorter FIN can otherwise miss the model detection.

## 1.1.11

### A settable Blade Mini unlock speed, and the speed tile goes red when de-restricted

- The Blade Mini triple-tap no longer always opens to the top speed. A "Blade Mini unlock speed" block in the settings (shown only for that model) sets the per-gear speed the de-restrict writes to German gears 2/3/4, defaulting to the full 45/60/80. Lower the values to cap the top speed; the numbers are the same ones the web unlock tool uses. Locking still restores the eKFV-legal 10/15/21 and is not user-editable. Only firmware 3.4.6 accepts these writes.
- The speed number now turns red while a Blade Mini is de-restricted, driven by the Blade's own state (per-gear speed high), not the Fighter's lock byte. The Fighter Mini keeps its existing red-when-unlocked colour unchanged.

## 1.1.10

### Model-aware unlock, and a tidier Blade Mini dashboard

- The speed-tile triple-tap now switches on the connected scooter, decided at connect: a Fighter Mini uses its direct (0x1B) unlock, a Blade Mini on firmware 3.4.6 de-restricts through its per-gear speed (German gears 2/3/4), and a Blade Mini on any other firmware shows the "Firmware 3.4.6 required for Blade Mini Ultra" message instead of the misleading "Fighter Mini only" toast.
- With the current tile hidden on the Blade Mini, the two remaining hero tiles now fill the row instead of leaving a gap.
- The in-app "What is new" now describes these Blade Mini changes.

## 1.1.9

### Blade Mini: a clear message when firmware 3.4.6 is needed

A Blade Mini only unlocks (via per-gear speed) on firmware 3.4.6; 3.4.8 ignores every setting write. The app now recognises a Blade Mini running any firmware other than 3.4.6 and shows a prominent banner, "Firmware 3.4.6 required for Blade Mini Ultra". A de-restrict attempt is then refused with the same message instead of silently doing nothing. A Fighter (which can also run 3.4.x) is unaffected, because the check gates on the model as well. The current tile, which the Blade Mini does not report, is hidden for that model instead of showing a placeholder.

## 1.1.8

### Blade Mini current tile no longer shows a bogus value

The current tile read +1000 A in green on the Blade Mini. That model does not report a pack current in its battery frame, so the app read the empty field as a huge regeneration figure. The tile now shows "-" when the scooter reports no current, instead of a made-up number.

## 1.1.7

### Correct battery voltage on Blade Mini and Fighter Mini

The voltage tile sat at 0.0 V on the Blade Mini because it read a field that model leaves empty. The app now takes the live pack voltage from the byte each model actually uses:

- Blade Mini reports its live pack voltage in whole volts in a different field than the Fighter Mini. The app reads that field now, so the tile shows the real voltage (around 52 V, dropping under load) instead of 0.0 V.
- Fighter Mini keeps its smart-BMS voltage. If neither field is filled the app falls back to the nominal pack voltage, so the tile is never stuck at zero.
- The pole-voltage tile no longer shows a bogus reading on the Blade Mini; it shows "-" when the scooter reports no separate pole voltage.

## 1.1.6

### Security hardening, continued

The 1.1.5 mitigations are rewritten into the exact shapes the static analyser recognises as safe, so the remaining findings close and the scan reports clean. How the app is used does not change:

- Log lines strip carriage return and line feed with an explicit replace, the form the log-injection check reads as safe against a forged or split line.
- Ride-log file access resolves a normalised absolute path and verifies it stays inside the app's own folder before the file is opened, which closes the path-traversal findings.
- The debug-log capture starts logcat by its absolute path.

## 1.1.5

### Security hardening

A pass over the CodeQL security findings, with no change to how the app is used:

- Log entries built from BLE names, URLs or other outside input have their control characters stripped, so a crafted value cannot forge or split a log line.
- The dashboard WebView no longer has file-system or content-provider access; it only ever loads the bundled local page.
- The in-app update is accepted only over HTTPS from the official GitHub release, downloaded into app-private storage and installed from there so no other app can swap it in between.
- Ride-log file handling stays inside its own folder via a digits-only id check and a path-traversal guard.

## 1.1.4

### The exports now open in LEAT

The ride exports match what the desktop tool [LEAT](https://github.com/Laufbursche42/leat) expects, without LEAT needing to guess:

- Every logged sample additionally carries the canonical names LEAT's KPI bar is wired to: `realSpeed`, `VolPack`, `fMotorTemp` and `rMotorTemp`. `realSpeed` stays km/h even while the app displays mph. The existing keys are unchanged.
- The JSON export is a bare array of samples. The wrapper object with `meta` and `samples` is gone, because LEAT refuses it.
- The CSV export no longer starts with a UTF-8 BOM, which hid the `ts` column from LEAT and bent the time axis, and no longer carries a `tsISO` column, which LEAT would read as a meaningless constant series.
- The GPX export writes `<speed>` in metres per second as the GPX convention wants, instead of km/h.

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

## 1.1.1

### The 1.1 series, now in German

The first public build of the 1.1 series: the app now speaks German, asks for a safety confirmation before the first ride, and leaves firmware flashing to the web patcher, so this app focuses on the live dashboard and tuning over Bluetooth. The app bundle is smaller, and the "1.1" version series is now defined in one place so the version number and the build can never drift apart.

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
