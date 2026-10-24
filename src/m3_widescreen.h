// Ported from josiahg2502-glitch/Mother3Recomp (682dcfe, src/m3_widescreen.h),
// Copyright (c) 2026 Matthew Stanley, PolyForm Noncommercial License 1.0.0
// (see LICENSE). Unchanged except for this notice.
// True widescreen for the field: the PPU asks for tilemap entries outside the
// native 240px, and we answer from the game's own decompressed map in EWRAM at
// the live camera position (the BG ring itself is only 256px wide). Screens
// that don't validate as the field (menus, battles, cutscenes with other BG
// setups) are pillarboxed instead.
#pragma once

namespace m3widescreen {

void install();  // registers the PPU margin providers (harmless at 240px); tick() calls it
void tick();     // game thread; re-validates the field a few times a second

}  // namespace m3widescreen
