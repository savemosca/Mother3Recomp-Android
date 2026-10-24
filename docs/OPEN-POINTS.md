# Open points

Snapshot of unfinished work, 2026-10-25. Update or strike items as they close.

## 1. Frame-pacing regression since the engine update (priority)

- Symptom: occasional micro-stutter and small crackles on the RG556 after the
  2026-10-24 update; the player reports the app was perfect before.
- Evidence (`files/sessions/*`, `session_health` late_frames = present gaps
  > 25 ms): builds on mstan 5e89943 (2026-10-15) had 0-0.3 % late frames
  (e.g. 2 of 15778); builds on mstan 7bb70ec (2026-10-24) have about 1 %
  (e.g. 36 of 3108). The first 7bb70ec session had widescreen off and was
  already at 1.15 %, so widescreen is not the cause. Audio never ran dry
  (`audio_dry_ms=0`).
- Profile (simpleperf, field scene, 15 s): game thread busy ~75 %;
  `render_scanline_wide` 18 %, `bus_read_u16` 15 %, `GbaBus::read16` 7 %,
  `m3widescreen::void_cell` 3 %.
- Suspects among the 45 mstan commits 5e89943..7bb70ec: 6076e7b always-on
  diagnostic replay (per-frame state fingerprints, periodic checkpoints;
  disable with config.ini `[Diagnostics] replay_capture = 0`); a01d93a,
  e9826f6, bb70a43 byte-verified native RAM templates (verified on every
  dispatch, 1059 templates for MOTHER 3); 60892e4 / 304b4c8 scanline changes;
  db89cf2 present-in-place stack budget (`GBARECOMP_PRESENT_IN_PLACE=0`).
- Set-up for the A/B, ready on the device (not the player's app):
  - `com.ylports.mother3recomp.en.bold` = fork 0d9dfff (engine 5e89943),
  - `com.ylports.mother3recomp.en.bnew` = fork 092217a (engine 7bb70ec),
  both built from scratch worktrees with only the application id and title
  changed. Run `tools/rg556-idle-bench.sh <package>` on each (screen
  unlocked), then repeat on `bnew` with one suspect disabled at a time.
- Uninstall both when done (`adb uninstall <package>`).

## 1b. Design review findings (2026-10-25)

Ordered by impact on the player.

1. **The suspend state is the de facto primary save, and it is fragile.**
   On the device `mother3_en.sav` dates from 2026-10-15; everything since
   lives only in `mother3_en.suspend.state`. That state is tied to the
   engine build and the exact ROM bytes (josiah's app rejects it because HQ
   audio changes the ROM SHA-1), it is not part of the cloud sync, and a
   cloud download deletes the resume marker (`CloudSaves.runSync`), so the
   game silently boots from the older `.sav` (the state file stays on disk).
   Ideas: keep the last N suspend states, sync the state as a second file,
   warn when the `.sav` is much older than the state, verify the state loads
   before shipping an engine update.
2. **`m3widescreen` reads the map through the emulated bus per margin
   pixel.** `void_width` → `in_room` → `void_cell` (up to 36 `bus_read_*`
   per 16 px cell) runs for each of the 2x22 margin pixels on 160 lines; the
   cache (`black_width`) resets whenever the camera moves, i.e. every frame
   while walking. Map data does not change while walking: cache `void_cell`
   per (map, cell) and read EWRAM through `ewram_ptr()` instead of the bus.
   Profile: `bus_read_u16` 15 %, `GbaBus::read16` 7 % of the game thread.
3. **`mother3_ram_dispatch` re-reads constant ROM on every call.** For each
   dispatch into RAM code it compares the opcode with ~60 ROM halfwords read
   through the bus. Cache the four routines' ROM bytes once; only the opcode
   at `pc` needs a bus read. The 7bb70ec engine also verifies its own RAM
   templates first (`lookup_native_ram`), so the per-dispatch cost grew.
4. **The engine patch stack is the weak point of every engine update.** 18
   patches; 7 touch `runtime.cpp`, 7 `host_window.cpp`, 5 `gba_audio.cpp`.
   The 2026-10-24 merge put the pacer deadline update in two places (game at
   30 fps). Mitigations: get #44-#50 merged upstream, and gate each engine
   bump on `ctest` plus `tools/rg556-idle-bench.sh` against the previous
   build.
5. **Always-on diagnostic replay (upstream 6076e7b)** fingerprints state
   every frame and checkpoints periodically; measured ~0.4 points of late
   frames in the test app. Consider defaulting it off on Android.
6. Minor: `tools/build-android-ci.yml` (inherited, inactive) carries a
   keystore password in plain text (the key itself was never committed;
   delete the file); `build-android.sh` embeds the ROM by default (output
   named `*-PRIVATE-rom-included.apk`); RetroAchievements unlocks earned
   offline are lost if the game is quit before reconnecting.

Sound parts (keep): RetroAchievements threading (network worker, callbacks
on the game thread, atomic sidecar), the three-way cloud sync with backups
and checksums, atomic `config.ini` writes that keep unknown keys, engine
patches free of MOTHER 3 special cases, build script guards against
edited engine files.

## 2. Strategy C: josiahg2502-glitch/Mother3Recomp on mstan's engine

Phase 1 (engine), done on desktop, not pushed:
- `gbarecomp/` branch `m3recomp-engine`: 952b002 three-way merge of their
  engine patch (base af47d28) onto mstan-update-20261024 (mstan 7bb70ec +
  0001-0018); e496af6 fixes a merge error that advanced the pacer deadline
  twice per frame (game at 30 fps). ctest 56/56.
- Taken from them: smooth-motion interpolator, PPU motion tags, Fill layout.
  Everything else in conflict kept ours; their side can be regenerated by
  re-running the merge.
- `~/Develope/Mother3Recomp-josiah` local branches (not pushed):
  `fix/macos-link` 7b2279a (ld64 rejects GNU link groups; PR candidate),
  `feat/rg556-preset` b44521e (`-Pm3Device=rg556`: OpenSL ES, 512/40/120
  audio, big cores). Their HQ-toggle PR branch is `feat/hq-audio-toggle`.
- Test app `com.ylports.mother3recomp.en.m3rtest` on the device (built with
  a local applicationId edit). Findings there:
  - micro-stutter and crackles as in item 1 (same engine), plus audio less
    clean than ours: their app has no Enhanced audio (MP2K shadow mixer), so
    the PSG 8 kHz low-pass (0016) is not applied; their HQ patch only covers
    DirectSound;
  - suspend states from our app are rejected (ROM SHA-1 differs once HQ
    audio is patched in); the battery save carries over
    (`roms/mother3_en.sav` -> `roms/mother3_en_v13.sav`);
  - integer scaling exists in the runtime menu (Graphics) but is off by
    default; image is 1669x940 with system bars hidden;
  - diagnostic replay recording on by default (256 MiB budget);
  - smooth motion only engages on displays >= 100 Hz (RG556 is 60 Hz).

Phase 2 (port our app features onto their Android app): RetroAchievements,
cloud saves (RomM / Hoard), Enhanced audio fixes (check against their HQ
mixer first: it moves the sound buffers), session diagnostics
(SessionArchive, `tools/rg556-health.sh`, `debug-env.txt`), launcher
options, RG556 preset (started), integer scale default.

Phase 3: migrate the player's save; keep this fork working until then.

## 3. Audio follow-ups (this fork)

- Startup overflow still drops ~60-80 ms of audio (bigger ring cap or
  preroll inside the cap on Android).
- Add `audio_stretched_ms` to the `session_health` line.
- Optional: audio target 50 ms (latency vs rhythm combos).

## 4. Upstream

- mstan/gbarecomp #44-#50 open, no reviews. Patch 0018 (pacer debt
  repayment) not yet proposed; mind that wait_for_next_frame() owns the
  deadline update (see e496af6 on `m3recomp-engine`).
- josiahg2502-glitch/Mother3Recomp #31 (engine bridge iret + tests), #32 (HQ
  audio toggle) open. Candidates: macOS link fix, RG556 preset, the engine
  move to mstan.

## 5. Housekeeping

- The player must run (the agent may not delete repositories):
  `gh repo delete savemosca/Mother3Recomp --yes` and
  `gh repo delete savemosca/Mother3Recomp-Android-standalone --yes`;
  optionally rename savemosca/Mother3Recomp-1 to Mother3Recomp afterwards.
- Device backups in `/data/local/tmp`: `m3-pre-perf-20261025`,
  `m3-pre-update-20261024` (last state saved by the old engine; used by the
  bench), `m3-probe-20261024`, `m3-suspend-20261015`.
- Never publish the `*-PRIVATE-rom-included.apk` outputs.
