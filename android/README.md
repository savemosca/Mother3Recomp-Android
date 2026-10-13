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

The engine patch in `patches/gbarecomp/` (applied by the build script) also
benefits every handheld: with a physical controller the touch pad starts
hidden, and with the pad hidden the game fills the display height (6.75x on
1080p) through the sharp scaler instead of a 4x image between empty rails.
The pad toggle in the corner still shows it.

`--generated-opt -O2` compiles the generated corpus at -O2 instead of -O1;
expect a much longer native build.

## RetroAchievements

[MOTHER 3 on RetroAchievements](https://retroachievements.org/game/677)
works in **softcore** mode. RetroAchievements accepts hardcore unlocks only
from approved emulators, and its standalone policy excludes recompilations,
so this client never requests hardcore. Save states, suspend/resume and
fast-forward stay available.

1. Build with `--no-embed` so the app keeps its setup screen (an APK with an
   embedded ROM/BIOS starts the game directly and has no login screen):

   ```bash
   tools/build-android.sh --device rg556 --no-embed \
     --rom /path/to/mother3_en.gba --bios /path/to/gba_bios.bin
   ```

2. On the device, import the ROM and BIOS once, then tap
   **RetroAchievements: log in**. The password is exchanged once for an API
   token; only the user name and token are stored, in app-private
   `retroachievements.ini`. The same button logs out.
3. Press PLAY. A banner shows the set and your progress; unlocks appear as
   banners (or Android toasts in builds without the overlay font stack).

The game is identified by the MD5 of the whole ROM file, the RetroAchievements
GBA method. If the banner says *ROM not recognized*, that MD5 is not linked
to game 677: compare it with the game's supported hashes on the site.
Achievements that read cartridge save memory are not supported (MOTHER 3
uses flash, whose reads go through a command state machine).

The build script clones rcheevos `v12.5.0` into `third_party/rcheevos`.
Without it the build continues with RetroAchievements disabled.
