# Reverse-engineering notes

One file per topic. Agents (`.claude/agents/`) read these before starting and update
them after. Each note records addresses (and whether JP == EN), what the code does,
the evidence, and open questions. Unverified claims are marked as hypotheses.

| Note | Topic | Status |
|---|---|---|
| [combo.md](combo.md) | Battle rhythm combos, hit-window check, latency offset | Hit check located; units unknown |
| [flash-stack-routines.md](flash-stack-routines.md) | FLASH512 routines copied to the stack (IWRAM 0x03006C1C..0x03006D80) | Resolved: RAM dispatch hook, FULLY_STATIC |
| [hq-audio-patch.md](hq-audio-patch.md) | MOTHER 3 HQ Audio patch (code changes, RetroAchievements hash) vs the engine's MP2K shadow mixer | Analysed; shadow mixer not yet tried |
| widescreen.md | Overworld camera, map/tilemap, object culling | Not started |
| [android-thumb-return-crash.md](android-thumb-return-crash.md) | Android crash: return to Thumb code in ARM state (0x08010832); pixel glitches; FLASH512 "unable to save" | Resolved: engine patch 0006 (upstream PR mstan/gbarecomp#44), BIOS config in build-android.sh; flash ID fixed upstream |

## Workflow
re-scout (find, write note) → decomp-matcher (optional: readable source in the fork)
→ hook-writer (implement) → verifier (test, report).
