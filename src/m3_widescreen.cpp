// Ported from josiahg2502-glitch/Mother3Recomp (682dcfe, src/m3_widescreen.cpp),
// Copyright (c) 2026 Matthew Stanley, PolyForm Noncommercial License 1.0.0
// (see LICENSE). Unchanged except for this notice.
#include "m3_widescreen.h"

#include <SDL.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <iterator>

#include "gba_bus.h"
#include "gba_ppu.h"
#include "runtime_arm.h"
#include "runtime_bus_bridge.h"

namespace m3widescreen {
namespace {

// Field state (English v1.3; found by matching VRAM against the ROM maps).
constexpr std::uint32_t kCamera = 0x02001004;     // s16 x, s16 y: world px of the screen's top-left
constexpr std::uint32_t kLayerPtrs = 0x02005C5C;  // 3 x u32: decompressed layer 0..2 (u16 per 16px cell)
constexpr std::uint32_t kTileDataPtr = 0x02009C88;  // u32: 0x300 x 8-byte 16px tile definitions
constexpr std::uint32_t kMapId = 0x020281E4;

struct Field {
    bool ok = false;
    bool bg_ok[4] = {};  // which field BGs this model reproduces
    std::uint32_t layer[3] = {};
    std::uint32_t tiles = 0;
    int width = 0, height = 0;  // in 16px cells
    int map = -1;
};
Field g;  // written by tick(), read by the provider (both on the game thread)
bool g_battle = false;
Uint32 g_next = 0;
int g_misses = 0;  // consecutive failed checks of the field still in use
int g_margin_left = 0, g_margin_right = 0;  // margin widths of the widened view, set by tick()

bool in_ewram(std::uint32_t a) { return a >= 0x02000000u && a < 0x02040000u; }

const std::uint8_t* io() {
    gba::GbaBus* bus = gbarecomp::active_bus();
    return bus ? bus->io().raw() : nullptr;
}

std::uint16_t io16(const std::uint8_t* r, int off) { return static_cast<std::uint16_t>(r[off] | (r[off + 1] << 8)); }

// World position of the screen's top-left for BG `bg`: the scroll registers hold
// the camera's low 4 bits (the game rebases its ring every 16px), the camera
// variable the rest. Snapping to the scroll keeps the sub-tile phase exact even
// when the variable lags a frame.
void camera(const std::uint8_t* r, int bg, int* x, int* y) {
    const int lx = io16(r, 0x10 + 4 * bg) & 15, ly = io16(r, 0x12 + 4 * bg) & 15;
    const int cx = static_cast<std::int16_t>(bus_read_u16(kCamera));
    const int cy = static_cast<std::int16_t>(bus_read_u16(kCamera + 2));
    auto snap = [](int var, int low) {
        const int d = var - low;
        return low + 16 * ((d >= 0 ? d + 8 : d - 8) / 16);
    };
    *x = snap(cx, lx);
    *y = snap(cy, ly);
}

// BG text entry for world 8px tile (tx, ty) of `layer`, or -1 outside the map.
int entry_at(const Field& f, int layer, int tx, int ty) {
    if (!f.layer[layer] || tx < 0 || ty < 0 || tx >= 2 * f.width || ty >= 2 * f.height) return -1;
    const std::uint16_t cell = bus_read_u16(f.layer[layer] + 2u * std::uint32_t((ty >> 1) * f.width + (tx >> 1)));
    const int t = cell & 0x3FF;
    if (t >= 0x300) return 0;
    const int sub = (cell >> 10) & 15, hf = (cell >> 14) & 1, vf = (cell >> 15) & 1;
    const std::uint32_t def = f.tiles + 8u * std::uint32_t(t);
    const int vis = bus_read_u16(def + 2);
    const int q = (((ty & 1) ^ vf) << 1) | ((tx & 1) ^ hf);
    if (!(vis & (1 << q))) return 0;
    const int mt = bus_read_u8(def + 4 + q);
    return ((t >> 6) * 64 + (mt & 0x3F)) | ((((mt >> 6) & 1) ^ hf) << 10) | ((((mt >> 7) & 1) ^ vf) << 11) | (sub << 12);
}

// True when no layer draws anything in 16px cell (cx, cy): the void between
// rooms. A cell is blank when each of its 8px parts is hidden or uses VRAM
// tile 0 (the empty tile); map tile 0 itself can be real ground outdoors.
bool void_cell(const Field& f, int cx, int cy) {
    if (cx < 0 || cy < 0 || cx >= f.width || cy >= f.height) return true;
    for (int L = 0; L < 3; ++L)
        for (int q = 0; q < 4; ++q) {
            const int e = entry_at(f, L, 2 * cx + (q & 1), 2 * cy + (q >> 1));
            if (e > 0 && (e & 0x3FF) != 0) return false;
        }
    return true;
}

// Share of BG `bg`'s visible 240x160 that this field model reproduces from VRAM.
double visible_match(const Field& f, const std::uint8_t* r, const std::uint8_t* vram, int bg, int* samples) {
    int ok = 0, total = 0;
    {
        const int cnt = io16(r, 0x08 + 2 * bg);
        const std::uint32_t screen = ((cnt >> 8) & 31) * 0x800u;
        const int hofs = io16(r, 0x10 + 4 * bg), vofs = io16(r, 0x12 + 4 * bg);
        int cx, cy;
        camera(r, bg, &cx, &cy);
        for (int y = 0; y < 160; y += 8)
            for (int x = 0; x < 240; x += 8) {
                const int mine = entry_at(f, bg - 1, (cx + x) >> 3, (cy + y) >> 3);
                if (mine < 0) continue;
                const std::uint32_t at = screen + 2u * std::uint32_t((((y + vofs) >> 3) & 31) * 32 + (((x + hofs) >> 3) & 31));
                const int ring = vram[at] | (vram[at + 1] << 8);
                if (!mine && !ring) continue;
                ++total;
                ok += mine == ring;
            }
    }
    *samples = total;
    return total ? double(ok) / total : 0.0;
}

// The field's BG setup: mode 0, BG2+BG3 on (BG1 optional), 256x256 4bpp text maps.
bool field_layout(const std::uint8_t* r) {
    const int dispcnt = io16(r, 0);
    if ((dispcnt & 7) != 0 || (dispcnt & 0x0C00) != 0x0C00) return false;
    for (int bg = 1; bg <= 3; ++bg) {
        const int cnt = io16(r, 0x08 + 2 * bg);
        if ((cnt >> 14) != 0 || (cnt & 0x80)) return false;
    }
    return true;
}

// Scores the model per BG. The scenery layers (BG2, BG3) must match; BG1 joins
// only when it also matches (or is empty), so a layer we can't reproduce is
// left blank in the margins rather than drawn wrong.
bool score(Field& f, const std::uint8_t* r, const std::uint8_t* vram, double need, int* samples_out) {
    int samples_all = 0;
    bool ok = true;
    for (int bg = 1; bg <= 3; ++bg) {
        int samples = 0;
        const double m = visible_match(f, r, vram, bg, &samples);
        samples_all += samples;
        f.bg_ok[bg] = f.layer[bg - 1] && (samples == 0 || m >= need);
        if (bg >= 2 && samples > 0 && m < need) ok = false;
    }
    *samples_out = samples_all;
    return ok && samples_all > 0;
}

// Interior maps pack several rooms; a margin point belongs to this one only if
// the row from the native screen edge out to it never crosses the void.
bool in_room(int cx, int cy, int hx, int y) {
    const int wy = (cy + y) >> 4, wx = (cx + hx) >> 4;
    const int edge = (cx + (hx < 0 ? 0 : 239)) >> 4;
    for (int c = edge; c != wx; c += hx < 0 ? -1 : 1)
        if (void_cell(g, c, wy)) return false;
    return !void_cell(g, wx, wy);
}

// Pixels of one margin, counted from its outer edge, that fall outside the map or room.
int void_width(int cx, int cy, int y, bool left) {
    const int m = left ? g_margin_left : g_margin_right;
    for (int d = 1; d <= m; ++d)
        if (!in_room(cx, cy, left ? -d : 239 + d, y)) return m - d + 1;
    return 0;
}

// When the camera sits at a map edge (cutscenes often park it there) one margin is
// void. Blacking the same width on the other side keeps the native picture centred
// instead of looking shoved sideways; the black shrinks evenly as the camera moves on.
int black_width(int cx, int cy, int y) {
    static int s_cx = -100000, s_cy = 0, s_map = -1;
    static std::uint32_t s_layer = 0;
    static std::int8_t s_width[160];
    if (y < 0 || y >= 160) return 0;
    if (cx != s_cx || cy != s_cy || g.map != s_map || g.layer[1] != s_layer) {
        s_cx = cx, s_cy = cy, s_map = g.map, s_layer = g.layer[1];
        std::fill(std::begin(s_width), std::end(s_width), static_cast<std::int8_t>(-1));
    }
    if (s_width[y] < 0)
        s_width[y] = static_cast<std::int8_t>(std::max(void_width(cx, cy, y, true), void_width(cx, cy, y, false)));
    return s_width[y];
}

// Window effects (the circle wipe, spotlight reveals) live inside WIN0/WIN1, which
// the game keeps within the native 240 columns, so the margins are outside every
// window and show only what WINOUT lets through there. The plain field never
// enables a window.
bool hidden_outside_windows(const std::uint8_t* r, int layer_bit) {
    return (io16(r, 0) & 0x6000) && !(io16(r, 0x4A) & layer_bit);
}

bool in_black(int cx, int cy, int hx, int y) {
    const int b = black_width(cx, cy, y);
    return b && (hx < -g_margin_left + b || hx >= 240 + g_margin_right - b);
}

// Sprites reaching into a margin from another room (furniture next door, parked
// actors) are moved off the canvas; sprites in this room show as usual.
int place_obj(int, std::uint16_t attr0, std::uint16_t attr1, std::uint16_t, int* out_sx) {
    if (!g.ok) return 0;
    static const int kW[3][4] = {{8, 16, 32, 64}, {16, 32, 32, 64}, {8, 8, 16, 32}};
    static const int kH[3][4] = {{8, 16, 32, 64}, {8, 8, 16, 32}, {16, 32, 32, 64}};
    const int shape = (attr0 >> 14) & 3, size = (attr1 >> 14) & 3;
    if (shape > 2) return 0;
    int sx = attr1 & 0x1FF, sy = attr0 & 0xFF;
    if (sx & 0x100) sx -= 0x200;
    if (sy >= 160) sy -= 256;
    const int w = kW[shape][size], h = kH[shape][size];
    if (sx >= 0 && sx + w <= 240) return 0;  // fully on the native screen
    const std::uint8_t* r = io();
    if (!r) return 0;
    int cx, cy;
    camera(r, 2, &cx, &cy);
    const int px = std::clamp(sx + w / 2, -64, 303), py = std::clamp(sy + h - 1, 0, 159);  // feet
    if ((px >= 0 && px < 240) ||
        (in_room(cx, cy, px, py) && !in_black(cx, cy, px, py) && !hidden_outside_windows(r, 0x10)))
        return 0;
    *out_sx = 100000;  // off every canvas
    return 1;
}

// BG0 carries text boxes and the cinematic letterbox bars. A bar is a row of
// solid one-colour tiles, so a margin continues BG0 only where the tile at the
// native screen edge is solid; text boxes (bordered, transparent edges) stay put.
int extend_solid_edge(const std::uint8_t* r, int hx, int y, std::uint16_t* out) {
    gba::GbaBus* bus = gbarecomp::active_bus();
    if (!bus) return gba::kWsTilemapUnavailable;
    const std::uint8_t* vram = bus->vram_ptr();
    const int cnt = io16(r, 0x08), hofs = io16(r, 0x10) & 0x1FF, vofs = io16(r, 0x12) & 0x1FF;
    if ((cnt >> 14) != 0 || (cnt & 0x80)) return gba::kWsTilemapUnavailable;
    const int ex = hx < 0 ? 0 : 239;
    const std::uint32_t at = ((cnt >> 8) & 31) * 0x800u +
        2u * std::uint32_t((((y + vofs) >> 3) & 31) * 32 + (((ex + hofs) >> 3) & 31));
    const std::uint16_t entry = static_cast<std::uint16_t>(vram[at] | (vram[at + 1] << 8));
    const std::uint8_t* tile = vram + ((cnt >> 2) & 3) * 0x4000u + (entry & 0x3FFu) * 32u;
    const std::uint8_t first = tile[0];
    if ((first & 15) == 0 || (first >> 4) != (first & 15)) return gba::kWsTilemapUnavailable;
    for (int i = 1; i < 32; ++i)
        if (tile[i] != first) return gba::kWsTilemapUnavailable;
    *out = entry;
    return gba::kWsTilemapReplace;
}

// Battles: BG2/BG3 are tiling background patterns (with per-line distortion),
// so the 256px ring's wrap is their true continuation; BG0/BG1 are the text
// box and HUD and never extend. WIN0 hides the background behind the text and
// HUD rows natively, so margins follow the same rows.
bool battle_layout(const std::uint8_t* r) {
    const int dispcnt = io16(r, 0);
    if ((dispcnt & 7) != 0 || (dispcnt & 0x0C00) != 0x0C00) return false;
    return ((io16(r, 0x0C) >> 8) & 31) == 29 && ((io16(r, 0x0E) >> 8) & 31) == 28 &&
           (io16(r, 0x0C) >> 14) == 0 && (io16(r, 0x0E) >> 14) == 0;
}

int provide_battle(const std::uint8_t* r, int bg, int y) {
    if (bg != 2 && bg != 3) return gba::kWsTilemapUnavailable;
    const int dispcnt = io16(r, 0);
    if (dispcnt & 0x2000) {  // WIN0 spans the native width; extend it across the margins
        const int v = io16(r, 0x44), top = v >> 8, bottom = v & 0xFF;
        const bool inside = top <= bottom ? (y >= top && y < bottom) : (y >= top || y < bottom);
        const int mask = inside ? io16(r, 0x48) : io16(r, 0x4A);
        if (!(mask & (1 << bg))) return gba::kWsTilemapUnavailable;
    }
    return gba::kWsTilemapKeepWrapped;
}

int provide(int bg, int hx, int y, std::uint16_t* out) {
    const std::uint8_t* r = io();
    if (!r) return gba::kWsTilemapUnavailable;
    if (g_battle) return provide_battle(r, bg, y);
    if (!g.ok || hidden_outside_windows(r, 1 << bg)) return gba::kWsTilemapUnavailable;
    {
        int cx, cy;
        camera(r, 2, &cx, &cy);
        if (in_black(cx, cy, hx, y)) return gba::kWsTilemapUnavailable;
    }
    if (bg == 0) return extend_solid_edge(r, hx, y, out);
    if (bg > 3 || !g.bg_ok[bg]) return gba::kWsTilemapUnavailable;
    int cx, cy;
    camera(r, bg, &cx, &cy);
    if (!in_room(cx, cy, hx, y)) return gba::kWsTilemapUnavailable;
    const int e = entry_at(g, bg - 1, (cx + hx) >> 3, (cy + y) >> 3);
    if (e <= 0) return gba::kWsTilemapUnavailable;
    *out = static_cast<std::uint16_t>(e);
    return gba::kWsTilemapReplace;
}

}  // namespace

void install() {
    gba::g_ws_tilemap_provider = &provide;
    gba::g_ws_obj_attr_x_provider = &place_obj;
    gba::g_ws_authored_margin_layers = 1;
    gba::g_ws_pillarbox = 1;  // until the field validates
}

void tick() {
    // Only for a widened view; at 240px the PPU path stays untouched. run_game()
    // clears every widescreen hook at startup, so (re)install from here.
    gba::GbaPpu* ppu = gbarecomp::active_ppu();
    if (!ppu || ppu->render_width() <= 240) return;
    if (gba::g_ws_tilemap_provider != &provide) install();
    g_margin_left = static_cast<int>(ppu->view_extra_left());
    g_margin_right = static_cast<int>(ppu->render_width()) - 240 - g_margin_left;
    const Uint32 now = SDL_GetTicks();
    if (now < g_next) return;
    g_next = now + 50;
    gba::GbaBus* bus = gbarecomp::active_bus();
    const std::uint8_t* r = io();
    if (!bus || !r) return;
    const std::uint8_t* vram = bus->vram_ptr();

    Field f;
    f.map = bus_read_u16(kMapId);
    for (int L = 0; L < 3; ++L) f.layer[L] = bus_read_u32(kLayerPtrs + 4u * L);
    f.tiles = bus_read_u32(kTileDataPtr);
    // Layers are consecutive equal-size buffers; a map may leave layer 0 out (null).
    int cells = 0;
    for (int L = 0; L < 2; ++L)
        if (f.layer[L] && f.layer[L + 1] > f.layer[L]) cells = static_cast<int>((f.layer[L + 1] - f.layer[L]) / 2);
    const bool sane = field_layout(r) && in_ewram(f.layer[1]) && in_ewram(f.layer[2]) &&
                      (!f.layer[0] || in_ewram(f.layer[0])) && in_ewram(f.tiles) && cells > 0;
    bool ok = false;
    if (sane) {
        int samples = 0;
        const bool same = g.width && g.map == f.map && g.layer[0] == f.layer[0] && g.layer[1] == f.layer[1];
        if (same) {  // keep the known width while it still reproduces the screen
            f.width = g.width;
            f.height = g.height;
            ok = score(f, r, vram, 0.85, &samples) && samples >= 60;
        }
        if (!ok) {  // new map (or lost it): find the width that reproduces the screen
            for (int w = 8; w <= 256 && !ok; ++w) {
                if (cells % w) continue;
                Field t = f;
                t.width = w;
                t.height = cells / w;
                if (score(t, r, vram, 0.9, &samples) && samples >= 150) { f = t; ok = true; }
            }
        }
    }
    // A validated field can fail one check mid-scroll; keep it for a couple of
    // ticks so the margins don't flash black, but still drop it on a real change.
    const bool same_field = g.ok && g.map == f.map && g.layer[0] == f.layer[0] && g.layer[1] == f.layer[1];
    if (ok) {
        g_misses = 0;
    } else if (sane && same_field && ++g_misses < 3) {
        f = g;
        ok = true;
    }
    f.ok = ok;
    if (!ok) f.width = f.height = 0;
    g_battle = !ok && battle_layout(r);
    if (ok != g.ok || f.map != g.map || f.width != g.width)
        SDL_Log("m3-widescreen: map %d field=%d sane=%d width=%d", f.map, ok, sane, f.width);
    g = f;
    gba::g_ws_pillarbox = ok || g_battle ? 0 : 1;
}

}  // namespace m3widescreen
