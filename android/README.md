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
