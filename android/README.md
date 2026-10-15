# MOTHER 3 Recomp — Android

This module packages the existing MOTHER 3 recomp runtime as an Android SDL2
application. The default variant is the English fan-translation build; pass
`-Pmother3Variant=jpn` for the Japanese ROM.

A public APK does not contain a ROM or GBA BIOS. The first-launch setup Activity
imports and SHA-1 verifies the player's own files.

For a private native-recompiled build, use `../tools/build-android.sh`, which
generates the ROM/BIOS-derived C++ locally before assembling the APK.

## Device preset: Anbernic RG556

```bash
tools/build-android.sh --device rg556 \
  --rom /path/to/mother3_en.gba --bios /path/to/gba_bios.bin
```

`--device rg556` (Gradle: `-Pmother3Device=rg556`) builds for the Unisoc
T820 and its 1920x1080 AMOLED panel:

- Native code is built as ARMv8.2-A and scheduled for Cortex-A76.
- The game thread is pinned to the Cortex-A76 cluster (`GBARECOMP_BIG_CORES`).
- Audio: 512-frame callbacks, 40 ms bridge target, 120 ms boot pre-roll
  (engine defaults: 1024 / 60 / 250). If audio crackles, raise the target in
  `src/main.cpp`; the log line `host_window: audio=bridge ...` shows the
  values in use.

The engine patches in `patches/gbarecomp/` (applied by the build script) also
benefit every handheld: with a physical controller the touch pad starts
hidden, and with the pad hidden the game fills the display height (6.75x on
1080p, exact 3:2) through the sharp scaler instead of a 4x image between
empty rails. The pad toggle in the corner still shows it.

The engine is [mstan/gbarecomp](https://github.com/mstan/gbarecomp), pinned
in `tools/build-android.sh`. Patch 0006 is an engine bug fix needed on every
device (proposed upstream as mstan/gbarecomp#44): it stops an occasional abort
(Thumb code entered in ARM state) and the misplaced pixels after IRQs taken
inside BIOS calls. See `docs/re/android-thumb-return-crash.md`.

**Integer scaling** (in-game menu > Graphics) switches to the largest whole
multiple instead: 6x = 1440x960 on the RG556, letterboxed on all sides. The
choice is kept in `config.ini` `[Display]`; `[video] integer_scale = true` in
`game_android_*.toml` makes it the default for new installs.

`--generated-opt -O2` compiles the generated corpus at -O2 instead of -O1;
expect a much longer native build.

## RetroAchievements

[MOTHER 3 on RetroAchievements](https://retroachievements.org/game/677)
works in **softcore** mode. RetroAchievements accepts hardcore unlocks only
from approved emulators, and its standalone policy excludes recompilations,
so this client never requests hardcore. Save states, suspend/resume and
fast-forward stay available.

1. Open the app. An APK with the ROM/BIOS embedded (the default of
   `tools/build-android.sh`) shows a launcher; a `--no-embed` build shows
   the setup screen, where the ROM and BIOS are imported once.
2. Tap **RetroAchievements: log in**. The password is exchanged once for an API
   token; only the user name and token are stored, in app-private
   `retroachievements.ini`. The same button logs out.
3. Press PLAY. A banner shows the set and your progress; unlocks appear as
   banners (or Android toasts in builds without the overlay font stack).

The game is identified by the MD5 of the whole ROM file, the RetroAchievements
GBA method. If the banner says *ROM not recognized*, that MD5 is not linked
to game 677: compare it with the game's supported hashes on the site.
Achievements that read cartridge save memory are not supported (MOTHER 3
uses flash, whose reads go through a command state machine). If the set has
any, the start banner says "(N unsupported)" and `android-runtime.log` lists
them as `ra: unsupported achievement <id>: <title>`.

Partial progress (hit counts, a challenge in progress) is stored beside each
save state and the suspend state as `<state>.ra`, so it survives the app
being closed in the background and follows manual state loads. Loading a
state without that file resets partial progress instead of mixing moments.
Rewind keeps the live progress (its in-memory states carry none).

The build script clones rcheevos `v12.5.0` into `third_party/rcheevos`.
Without it the build continues with RetroAchievements disabled.

## Cloud saves (RomM / Hoard)

The **Cloud saves** button (launcher or setup screen) keeps the save file
(`files/roms/<rom>.sav`) in sync with a self-hosted server.

- **[RomM](https://github.com/rommapp/romm)**: enter the server address, then
  approve the shown code on RomM's `/pair/device` page (phone or PC). The
  game must be in your RomM library: the save attaches to the ROM with the
  same SHA-1 as the one imported on the device. Uploads are filed under the
  emulator folder `mother3recomp`; saves from other emulators for that ROM
  (e.g. mGBA, 64 KiB with or without its 16-byte RTC footer) are picked up.
- **[Hoard](https://github.com/rleeon/hoard)** (self-hosted server): sign in
  with an admin account, which mints a device token (the password is not
  stored), or paste a device token from the web panel (*Users > New token*).
  The save is the Hoard game `mother-3`, label `default`; every upload is a
  new version. Hoard Cloud (hoard.services) is not supported.

Sync runs when PLAY is pressed and when the game exits back to the setup
screen. It compares the device's save, the cloud's newest save and the
version both last agreed on: the side that changed is copied over the other,
and if both changed the player chooses. A replaced local save, and a cloud
save the player chose to overwrite with this device's, is first copied to
`files/save-backups/` (10 kept). A failed sync never blocks playing. One sync
runs at a time: PLAY pressed during the after-game sync waits for it. Nothing
is synced while the game is running. Server redirects are not followed (the
token stays with the address you entered); the error names the address to use
instead. Signing in to Hoard with a password over plain `http` asks for a
second tap first. After a download the
game starts from the new save rather than resuming a suspended session
(a suspend state carries the old cartridge save). Loading an older manual
save state also restores that state's save, as in any emulator.

Plain `http://` addresses are allowed for home servers; tokens then travel
unencrypted on that network.

## Launcher

An APK with the ROM and BIOS embedded opens a small launcher instead of
starting the game at once: **PLAY**, **RetroAchievements** and **Cloud
saves**. The D-pad moves between them (the focused one has a yellow border),
A selects, START plays from anywhere. To quit the game, open the runtime
menu (Guide button, or a long press on the screen) and choose **System >
Quit game** twice (patch 0007; Back belongs to the game). That returns to the
launcher, which uploads the session's save when cloud saves are on. If the
system closes the game screen while the engine is still writing, the launcher
waits up to 10 s for it before syncing; otherwise the next PLAY syncs. After a
crash it shows the tail of `android-runtime.log`; a normal quit shows nothing.

Home (or a frontend such as Daijishou) leaves the game suspended; opening the
app again returns to it, not to the launcher. If the system closes the
suspended game instead (memory, or swiped away from recents), the next PLAY
resumes where it was (patch 0010).

## Session diagnostics

Problems that do not crash the game are recorded at almost no cost, so they
can be found after playing:

- Each session ends with a coverage line (`self_heal_coverage=FULLY_STATIC`
  or the interpreted PCs) and a health line (engine patch 0008):
  `session_health ... late_frames hitches max_gap_ms audio_dry_ms
  audio_dropped_ms`. A frame is late after 25 ms, a hitch is a gap over
  100 ms; the audio figures cover steady play only. Each hitch gets its own
  line. The stall right before the app leaves the foreground (about 525 ms
  when Home is pressed) is Android's transition, logged as "not counted".
- Before each PLAY the launcher copies the previous session's log, coverage
  report and self-heal proposal to `files/sessions/<end time>/` (last 20).
- `tools/rg556-health.sh` (adb + root) pulls them, plus Android's crash
  buffer, into `health/` and prints one line per session.

For a specific bug, `files/debug-env.txt` (created with `su`) sets engine
variables such as `GBARECOMP_TRACE_ON_DISPATCH_MISS`; tracing slows the game,
so delete the file afterwards.

`GBARECOMP_AUDIO_SHADOW=1` in the same file turns on the engine's MP2K
shadow mixer (float re-render without the 8-bit hiss; it reverts on
divergence). Background, and why it is preferred to the MOTHER 3 HQ Audio
ROM patch: `docs/re/hq-audio-patch.md`.
