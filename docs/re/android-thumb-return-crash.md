# Android crash: return to Thumb code in ARM state (EN, RG556)

Status: **open**. Root cause not found yet; engine-side (gbarecomp), not the
fork's Android/cloud/RA code. Pixel glitches reported at the same time are
noted at the end and are not characterised yet.

## What happened (2026-10-14, 01:29 CEST)

Build: `rg556-tuning` at `0daf5f1` (EN, `--device rg556`, corpus generated
with `tools/build-android.sh`, engine `agray0425/gbarecomp@af47d28` + local
patches 0001-0005). Device: Anbernic RG556, GammaOS Next 1.2 (Android 14).
The user had been playing about 6 minutes (process uptime 390 s), with
integer scaling switched on from the runtime menu. **Per the user, the crash
came right after naming the characters** (new game, no save yet: the naming
sequence is the reproduction path, reachable in a few minutes from boot).

`android-runtime.log` (tail):

```
self_heal: missing static coverage detected; healing in the background and recording a TOML proposal. Set GBARECOMP_SELFHEAL_VERBOSE=1 for per-PC diagnostics.
...
runtime_arm: SELF-HEAL bridge hit interpreter Undefined at pc=0x08010836 while bridging dispatch miss 0x08010832. The reference interpreter cannot execute this op; add its lowering in src/armv4t/interpreter.cpp.
runtime_trace: last 0 event(s)
```

Tombstone (logcat `-b crash`): SIGABRT on the game thread (`Thread-2`),
`abort` <- `runtime_bridge_interpret+2292` <- `runtime_dispatch_miss+496` <-
generated code <- `gbarecomp::run_game`. Register x27 = `0x08010836`.

## Evidence

ROM bytes at 0x08010820 (EN ROM, Thumb):

```
08010820  8800       ldrh r0, [r0]
08010822  f00a fdeb  bl   0x0801B3FC      (computed: 0x08010826 + 0xA000 + 0xBD6)
08010826  00a1       lsls r1, r4, #2
08010828  19c9       adds r1, r1, r7
0801082a  6008       str  r0, [r1]
0801082c  1c29       adds r1, r5, #0
0801082e  f080 fbab  bl   0x08090F88      (computed: 0x08010832 + 0x80000 + 0x756)
08010832  2080       movs r0, #0x80      <- dispatch miss
08010834  0100       lsls r0, r0, #4
08010836  182d       adds r5, r5, r0     <- "Undefined" in the interpreter
08010838  1c60       adds r0, r4, #1
...
08010842  bcf0       pop  {r4-r7}
08010844  bc01       pop  {r0}
08010846  4700       bx   r0
```

- 0x08010832 is the **return address of `bl 0x08090F88`**, inside
  `sub_08010808` (function_boundaries.tsv: 0x08010808-0x0801084B, Thumb).
- It **is** in the static corpus: `dispatch_table.cpp` has
  `{0x08010832u, 1u, 0u, gf_tfunc_08010832}` (Thumb entry).
- `runtime_dispatch` (`gbarecomp/src/armv4t/runtime_arm.cpp` ~L820) looks the
  PC up with the mode taken from `g_cpu.cpsr & CPSR_T_BIT`. A miss on a PC that
  exists as a Thumb entry means **CPSR.T was clear (ARM state) at the return**.
- The bridge (`runtime_arm_default_aborts.cpp` ~L540) then decodes with
  `ArmDecoder` because `cpu.thumb` is false; Thumb halfwords read as ARM words
  give "Undefined" at 0x08010836, hence the abort. The interpreter is not
  missing an op; the CPU state is wrong.

## Hypotheses (unverified)

1. The callee `0x08090F88` (or something it calls) returns with T cleared,
   e.g. a `bx` to an even address, or an ARM-state helper whose return path
   in the generated code does not restore Thumb.
2. An IRQ taken during the callee returns from the (recompiled) BIOS IRQ
   handler with SPSR/CPSR.T restored wrongly.
3. Something specific to the Android build (the `-march=armv8.2-a
   -mtune=cortex-a76` RG556 preset exposing UB in generated code). Least
   likely; testable by rebuilding without `--device rg556`.

Not the fork's code: RetroAchievements hooks are only installed with
credentials (none on the device); the savestate hooks were not involved; the
cloud/launcher code is Java and outside the game thread. The engine pin
`af47d28` is upstream gbarecomp HEAD (no newer commits as of 2026-10-14).

## Next steps

- Disassemble `0x08090F88` (and `0x0801B3FC`) from the EN ROM; check how
  they return and what `gf_tfunc_08090F88` does at its return in
  `variants/mother3en/generated/`.
- Check `function_boundaries.tsv` / the JP symbols for `0x08090F88` (EN may
  have moved code: CLAUDE.md warns of self-heal misses in the EN patch).
- Reproduce with tracing: `GBARECOMP_SELFHEAL_VERBOSE=1`, and a larger
  `runtime_trace` buffer (it dumped 0 events) so the last dispatches before
  the miss are visible. Env vars can be set in `src/main.cpp` for a debug
  build, or the desktop build can be run on the Mac with a save near the
  crash point.
- A/B: rebuild without `--device rg556` and see whether it still happens.
- Reproduce: new game, name the characters, confirm (user's report). No
  save or suspend state exists (the process aborted), so a savestate taken
  just before confirming the names would speed up iterations.
- mGBA is the oracle (CLAUDE.md): compare behaviour at the same point.

## Pixel glitches (same session, not analysed)

The user reports "several misplaced pixels", **visible from the game's
title screen onwards** (so already in the boot/intro sequence, before any
gameplay; a quick reproduction path). Screenshots during the fan
translation splash and the MOTHER 3 logo animation showed scattered pixels
and vertical streaks; static scenes (naming screen) looked clean, and integer
scaling was verified pixel exact (every GBA pixel = one uniform 6x6 block), so
the presentation path is not the cause. Whether these are the game's own
transition effects or PPU/recompilation errors is open: compare with mGBA.
The early `self_heal: missing static coverage detected` line means some PCs
were already being bridged through the interpreter before the crash; with
`GBARECOMP_SELFHEAL_VERBOSE=1` the log lists them, and they are candidates
for both problems.

## How to collect data from the RG556

adb is authorised and `su` works (GammaOS userdebug):

```
adb logcat -d -b crash                                  # tombstone summary
adb shell "su -c 'cat /data/data/com.ylports.mother3recomp.en.dev/files/android-runtime.log'"
adb exec-out screencap -p > shot.png
```
