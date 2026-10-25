# Open points

Snapshot of unfinished work, 2026-10-25. Update or strike items as they close.

## 1. Frame-pacing regression since the 2026-10-24 update: solved

- Cause: widescreen, not the engine. Idle A/B on the RG556 (120 s, same
  suspend state, `tools/rg556-idle-bench.sh`; `WIDE=1` turns widescreen on):
  old engine 5e89943 0.46 % late frames, new engine 7bb70ec 0.50 % (both
  widescreen off); 7bb70ec with widescreen 2.3-3.8 %, max gap 43 ms, and
  below 60 fps (6536-7086 frames instead of ~7240).
- `m3_widescreen.cpp` read the field through the `bus_read_*` bridge for
  every margin pixel and layer, and `in_room()` had no cache. Where a scene
  is "sane" but never validates (bench state, map 12), `tick()` reran the
  full width search every 50 ms, also through the bus.
- Fix (uncommitted, in `src/m3_widescreen.cpp`): EWRAM is read directly
  (`ewram_ptr()`, same bytes as `GbaBus::read16`), and `void_cell()` is
  memoized per rendered frame (32x32 direct-mapped, keyed by PPU
  `frame_count()` and a field generation). Checked with a temporary build:
  163 M cached answers compared with uncached ones, 0 differences; sampled
  direct reads equal to the bus, 0 differences. Bench with widescreen: 0 and
  0 late frames, 23 ms max gap, 7241 frames.
- Installed in the player's app on 2026-10-25 19:45 (with `SuspendBackups`,
  below). Player's first session: 14652 frames, 5 late (0.03 %), audio clean.
- The test apps (`.bold`, `.bnew`, `.m3rtest`) were uninstalled.
- Lesson: the bench drives the device (launch, taps, HOME); never run it
  while the player plays, and never leave a game process in the background
  after restoring files: on 2026-10-25 00:11 a leftover profiling process
  flushed its own state over the restored one (put back from
  `/data/local/tmp/m3-pre-perf-20261025`, md5 2b1dab83...).

## 1a. Widescreen follow-ups

Done and installed in the player's app (2026-10-25 20:27; state backups in
`/data/local/tmp/m3-pre-cull-20261025` and `m3-pre-sky-20261025`):
- Weather on BG1 (rain in map 11, drifting clouds in map 12) was cut at the
  native edge. A first fix (installed 2026-10-25 20:08) repeated the 256 px
  BG ring; the player saw clouds in the wrong place, because the clouds are
  map layer 0 with its own size (768x768, looping horizontally) and its own
  scroll, streamed into the ring 16 px at a time like the scenery
  (`sub_0801124C`), so the ring is a window, not a repeating pattern.
  Fix (installed 2026-10-25 20:27, clouds confirmed by the player): the model reads layer 0's geometry from
  MapLayerInfo (gGame+0x1C) and its column/row from the per-layer scroll
  block (gGame+0x58B0 + 0x38*layer), snapped to BG1's scroll registers;
  a "detached" layer 0 wraps when it loops and is left out of the void test
  and of the width validation. Layers exactly 256 px wide (the rain) keep the
  ring wrap. Checked headless: clouds continuous across the old edge over 90
  frames; maps 11 and 12 validate at width 48 as before.
- Actors vanished at the native edge: the field draw code
  (0x08030810, 0x08030A00, 0x08030B70, 0x0802D6A0, 0x0803092C) builds a
  `{camera, 240x160}` rect and culls actors with the overlap tests
  0x080357D0 and 0x08035820 (rect, pos, size; addresses equal in the JP
  decomp and EN v1.3). Two `[[mod_function_hook]]` entries in
  `variants/mother3en/game.toml` hand those to `overlap_view()`, which answers
  against the widened rect, only when the rect is exactly 240x160 and the
  field is validated; the hooks are enabled only at widths above 240.
  Headless check (map 11 state): an NPC 20-36 px past the old right edge,
  previously invisible, is drawn; 3600-frame runs from two states are clean.
- Desktop test bench on the Mac: `CMakeLists.txt` skips GNU link groups on
  Apple; build with `cmake -S <tree> -B <dir> -G Ninja
  -DCMAKE_BUILD_TYPE=Release` and run `Mother3RecompEN --rom --bios
  --load-state <state> --frames N --dump-png <file>` (muted:
  `SDL_AUDIODRIVER=dummy`; config.ini `[Launcher] m3_widescreen = 1`).
  Suspend states parse with the `BUS0` (EWRAM, IWRAM, palette, VRAM, OAM)
  and `IO_0` sections.

To do:
- Looping maps (gGame+0x11 bits 4/5): done (installed 2026-10-25 20:40).
  `overlap_view()` reproduces 0x08035820's wrap path with the game's 16-bit
  arithmetic and the widened width instead of its built-in 240. Checked
  headless with the loop bits forced on in two states and the widening set
  to zero: after 600 frames (4551 calls on the looping path) EWRAM, IWRAM,
  VRAM, OAM and palette equal the original function's run byte for byte,
  except dead stack bytes below SP; only timing counters differ, because a
  hooked call skips the original body's cycles. No real looping map tested
  yet.
- Other overlays (fog, darkness) on layers other than a blended BG1 are not
  handled; capture a suspend state where one is cut (HOME, then copy
  `roms/mother3_en.suspend.state`) and inspect it the same way.
- Widening the cull also changes the actor "off screen" flag (bit 7 of
  +0xC8) while widescreen is on; no gameplay effect seen so far, keep an eye
  on events that start when an actor comes on screen.
- Engine update (2026-10-25): mstan merged #44-#50 (reviewed) on 10-24, so
  the build now pins mstan e353848 with 7 local patches instead of 18
  (`gbarecomp/` branch `mstan-update-20261025`): handheld tuning, vblank
  hook, integer scale, savestate hooks, embedded-assets launcher hook, pacer
  debt repayment, and new 0007 that loads version-2 snapshots again
  (upstream d4c88e6 moved to v4 and rejected v2, which would have discarded
  every player's suspend state; v2 lacks only the SWI continuation tail).
  Checked: ctest 63/63 with a new bus test for v2; the player's real state
  and two others load and run 3600 frames; frames after 600 are
  pixel-identical to the 7bb70ec build; FULLY_STATIC, 0 dispatch misses.
  The new engine also refuses frame-only suspend markers (d4bea3c: the
  marker carries the battery save's SHA-1); the launcher upgrades an old
  marker before PLAY (`SuspendBackups.upgradeMarker`), found by the bench
  booting a test app fresh. Idle bench on the RG556, 120 s each, old vs
  new: battle widescreen off 0/0 late frames, on 0/1, the player's field
  0/0, max gap 22.7-25.4 ms. Installed in the player's app 2026-10-25 21:36
  (backup `/data/local/tmp/m3-pre-engine-20261025`). The strategy C branch
  `m3recomp-engine` is still on 7bb70ec and needs the same rebase.

## 1b. Design review findings (2026-10-25)

Ordered by impact on the player.

1. **The suspend state is the de facto primary save, and it is fragile.**
   On the device `mother3_en.sav` dates from 2026-10-15; everything since
   lives only in `mother3_en.suspend.state`. That state is tied to the
   engine build and the exact ROM bytes (josiah's app rejects it because HQ
   audio changes the ROM SHA-1), it is not part of the cloud sync, and a
   cloud download deletes the resume marker (`CloudSaves.runSync`), so the
   game silently boots from the older `.sav` (the state file stays on disk).
   Done: `SuspendBackups` (installed 2026-10-25) copies the state and its
   `.ra` sidecar to `files/suspend-backups/<stem>.<state time>.state` before
   every PLAY, after the cloud sync, keeping 8; first copy verified on the
   device. Since 2026-10-25 20:40 the launcher also warns under PLAY when the
   `.sav` is a day or more older than the state PLAY resumes (or missing),
   and "Suspend backups" puts a chosen copy back (backing up the current
   state first). Still open: syncing the state, checking that the state
   loads before an engine update. Note: copying a `.sav` with plain `cp`
   resets its date and silences the warning; use `cp -p` (the player's was
   reset to its true 2026-10-15 date with `touch -r`). The
   player's `.sav` still dates from 2026-10-15: save in game.
2. **`m3widescreen` read the map through the emulated bus per margin
   pixel.** Fixed, see item 1.
3. **`mother3_ram_dispatch` re-reads constant ROM on every call.** For each
   dispatch into RAM code it compares the opcode with ~60 ROM halfwords read
   through the bus. Probably negligible: it runs only for THUMB RAM PCs the
   engine's own templates miss, and it does not show in the 2026-10-25
   profile (the 15 % `bus_read_u16` was widescreen). Confirm with a profile
   of the fixed build before touching it.
4. **The engine patch stack is the weak point of every engine update.** 18
   patches; 7 touch `runtime.cpp`, 7 `host_window.cpp`, 5 `gba_audio.cpp`.
   The 2026-10-24 merge put the pacer deadline update in two places (game at
   30 fps). Mitigations: get #44-#50 merged upstream, and gate each engine
   bump on `ctest` plus `tools/rg556-idle-bench.sh` against the previous
   build.
5. **Always-on diagnostic replay (upstream 6076e7b)**: off by default on
   Android since 2026-10-25 (`diagnostic_replay_default`); config.ini
   `[Diagnostics] replay_capture = 1` turns it on to record a bug.
6. Minor: `tools/build-android-ci.yml` (inherited, inactive, with the
   upstream dev keystore password) was removed on 2026-10-25; the encoded
   key itself sits in upstream's first commit 18ae6a5 and in this fork's
   history, so treat that dev key as public (the player's app is signed
   with the Mac's debug key, not with it); `build-android.sh` embeds the ROM by default (output
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
- Test app `com.ylports.mother3recomp.en.m3rtest` (built with a local
  applicationId edit; uninstalled 2026-10-25, rebuild to test again).
  Findings there:
  - micro-stutter and crackles (widescreen cost, item 1; their
    `m3_widescreen.cpp` is the same code, so the fix applies there too and
    is a PR candidate), plus audio less clean than ours: their app has no Enhanced audio (MP2K shadow mixer), so
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

- mstan/gbarecomp #44-#50 merged 2026-10-24 (reviewed). #66 (v2 snapshot
  loading, our patch 0007) opened 2026-10-25; when it lands, drop 0007 on
  the next pin. Not yet proposed: the pacer debt repayment (patch 0006).
  Open from others: #39 (close IRQs that never iret; keep in reserve if a
  hang shows up).
- josiahg2502-glitch/Mother3Recomp, open from savemosca/Mother3Recomp-1:
  #31 (engine bridge iret + tests), #32 (HQ audio toggle), #33 (widescreen:
  stutter, clouds, actors; same code as item 1/1a, tested with their engine
  af47d28), #34 (macOS link), #35 (RG556 preset). Still a candidate: the
  engine move to mstan (strategy C).
- The upstream of this fork (josiahg2502-glitch/Mother3Recomp-Android) is
  archived: its PRs #1-#3 from savemosca can't be merged any more; this
  fork's default branch `rg556-tuning` is the published state.

## 5. Housekeeping

- The player must run (the agent may not delete repositories):
  `gh repo delete savemosca/Mother3Recomp --yes` and
  `gh repo delete savemosca/Mother3Recomp-Android-standalone --yes`;
  optionally rename savemosca/Mother3Recomp-1 to Mother3Recomp afterwards.
- Device backups in `/data/local/tmp`: `m3-pre-wsfix-20261025` (state as
  found before the 19:45 install, including the stray profiling minutes),
  `m3-pre-perf-20261025` (the player's own last state, restored),
  `m3-pre-update-20261024` (last state saved by the old engine; used by the
  bench), `m3-probe-20261024`, `m3-suspend-20261015`.
- Uncommitted in the main checkout (branch `rg556-tuning`):
  `src/m3_widescreen.cpp`, `variants/mother3en/game.toml`, `CMakeLists.txt`,
  `SuspendBackups.java` + its call in `Mother3SetupActivity.java`,
  `tools/rg556-idle-bench.sh` (`WIDE=`), this file.
- Scratch worktrees: `wt-old` can go (`git worktree remove`); `wt-new` holds
  the device build tree until the item 1a commit lands.
- `third_party/mother3` is not checked out; a shallow clone of
  Kurausukun/mother3 sits in the session scratchpad (reference only).
- Never publish the `*-PRIVATE-rom-included.apk` outputs.
