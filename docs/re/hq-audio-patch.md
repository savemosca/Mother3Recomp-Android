# MOTHER 3 HQ Audio patch vs the engine's MP2K shadow mixer

Status: **shadow mixer adopted** (2026-10-15), EN 1.3 only. It is switched in
the runtime menu (Audio > Enhanced audio, engine patch 0011), and on for the
author's RG556.

## Question

Can the ROM patched with MusicTheorist's
[MOTHER 3 HQ Audio](https://github.com/MusicTheorist/MOTHER-3-HQ-Audio) be
used with this port and keep RetroAchievements?

## RetroAchievements

`dorequest.php?r=gameid&m=<md5>` answers, as of 2026-10-15:

| ROM | MD5 | GameID |
|---|---|---|
| Mother 3 (Japan) | af8b0b175f7ec8914cb87b3161ba1aaa | 677 |
| EN 1.3 (`variants/mother3en`) | 78852690de6ef51af56883c3823ea0e1 | 677 |
| EN 1.3 + HQ Audio | 79827109fa1af4abbe25848657648c55 | 0 (not linked) |

Only RetroAchievements developers can link a hash, usually after a request in
the game's official forum topic with the patch link and the MD5. They have to
check that the achievements still read the right addresses. The patch moves
game buffers (below), so the request is not a formality. The author also says
it changes the RNG.

## What the patch changes (EN 1.3 vs EN 1.3 + HQ Audio)

`cmp` finds 1975 differing bytes:

| Location | Before | After | Reading |
|---|---|---|---|
| 0x0808F9EC (literal pool of the SoundMainRAM copy) | src 0x0808ED81, dst 0x03000000, CpuSet 0x04000100 (256 words) | src 0x09B0C141, dst 0x03001C00, CpuSet 0x0400021F (543 words, 2172 bytes) | new mixer, copied to a different IWRAM address |
| 0x0808ED70 (SoundMain literal) | 0x03000001 | 0x03001C01 | SoundMain calls the moved mixer |
| 0x0808F9F8 | 0x0300201B | 0x03000C50 | another IWRAM pointer moved (hypothesis: mixer work area) |
| 0x08069314, 0x08069434 | 0x02001084 | 0x02005230 | EWRAM pointer moved (purpose unknown) |
| 0x08120E20..0x08120E8F | table of (IWRAM, IWRAM buffer, count) rows | buffers moved within IWRAM and some to EWRAM 0x020010xx-0x020016xx | hypothesis: memory pools relocated to free IWRAM for the mixer |
| 0x09B0C140..0x09B0C9BB | free space | 2172 bytes | the new mixer (16-bit per-channel mix, then 8-bit) |

## Consequences for the port

- `tools/build-android.sh` checks the ROM SHA1 and `variants/mother3en/game.toml`
  pins it, so the build refuses the patched ROM.
- The IWRAM code map in `game.toml` is hand-written: `SoundMainRAM_Buffer`
  is declared at 0x03000000 as a copy of ROM 0x0808ED81 (932 bytes). With the
  patch the mixer runs at 0x03001C00, from 0x09B0C141, 2172 bytes. It does not
  overlap the next declared copy (0x03002EF0).
- Supporting it means a new variant (new SHA1, new code map, recompiled
  mixer) and a full test. That is possible but not a file swap, and it loses
  RetroAchievements until the hash is linked.

## Alternative: the engine's MP2K shadow mixer

gbarecomp already has a "verified-enhancement" shadow mixer for the stock
MP2K driver (`gbarecomp/src/gba/mp2k_shadow.*`, `audio_shadow.*`, ported from
JRickey/gba-recomp):

- The recompiled driver still runs. The shadow re-renders the same voices in
  float at 65536 Hz, without the 8-bit requantization that causes the hiss.
  This is the same problem the HQ patch addresses, and the patch still ends
  at 8 bits.
- A verifier compares it with the hardware mix every sample. The shadow only
  substitutes after a proven window, and logs `[audio] MP2K shadow DEGRADED:
  <reason>` and reverts on divergence.
- It is off by default (`[audio] shadow`, `gbarecomp/src/runtime/runtime.cpp`).
  `GBARECOMP_AUDIO_SHADOW=1` turns it on at launch, `0` forces it off.
- When armed, the log shows `[audio] MP2K shadow mixer ARMED`.
- Its loudness constant (`kShadowOutputScale`, `gba_audio.cpp`) is marked as
  still to be tuned.
- It keeps the RetroAchievements-linked ROM.

Engine patch 0011 adds a runtime-menu row, Audio > **Enhanced audio**:

- It appears only when the ROM links MP2K, and switches the shadow at runtime.
- The choice is saved in `config.ini` `[Audio] enhanced` and wins over
  `[audio] shadow`.
- `GBARECOMP_AUDIO_SHADOW` (for example via `debug-env.txt`) still overrides
  both.

## Result on the RG556 (2026-10-15)

- The player found the audio better, in overworld and in a battle.
- First session, about 10 minutes, 35843 frames, including a battle:
  - Two `DEGRADED` reverts, both within the first ~30 s after resuming a
    suspend state: `correlation 0.07, level 1.21` and `0.19, 0.92`. There
    were none after that.
  - Hypothesis: the shadow starts from the just-loaded state before it is in
    step with the driver.
  - The engine does not log when the shadow goes live, so "live for the rest
    of the session" is inferred: the verifier needed 4 passing ~1 s windows
    after the second revert, and nothing reverted again.
- Cost: the emulation thread used about 58% of one core. Health was
  `late_frames=65 hitches=0 max_gap_ms=33.6 audio_dry_ms=0.0`; a 41939-frame
  session without the shadow had 44 late frames.

## Unrelated finding: mono output

The engine mixes mono and opens the host audio device with one channel
(`gba_audio.cpp` `mix_one_sample`, "mix mono for now";
`host_window.cpp` `want.channels = 1`). MOTHER 3's stereo is lost with or
without either enhancement.

## Open questions

- Log when the shadow goes live (engine), so that live time can be measured
  rather than inferred.
- Does it stay in step with the rhythm-combo timing? No problem was heard in
  one battle.
- What are the pools in the table at 0x08120E20? This only matters for the
  RetroAchievements request.
