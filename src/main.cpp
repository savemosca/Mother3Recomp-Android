// MOTHER 3 recomp runner — desktop and Android entry points.

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "runtime.h"
#include "mobile_platform.h"

#ifndef GBARECOMP_BUILTIN_NAME
#define GBARECOMP_BUILTIN_NAME "MOTHER 3"
#endif
#ifndef GBARECOMP_BUILTIN_SHA1
#define GBARECOMP_BUILTIN_SHA1 ""
#endif
#ifndef GBARECOMP_BUILTIN_CRC32
#define GBARECOMP_BUILTIN_CRC32 0
#endif
#ifndef GBARECOMP_BUILTIN_REGION
#define GBARECOMP_BUILTIN_REGION ""
#endif
#ifndef GBARECOMP_WINDOW_TITLE
#define GBARECOMP_WINDOW_TITLE "Mother3Recomp"
#endif
#ifndef GBARECOMP_PROGRAM_NAME
#define GBARECOMP_PROGRAM_NAME "./Mother3Recomp"
#endif
#ifndef GBARECOMP_DEFAULT_GAME_CONFIG
#define GBARECOMP_DEFAULT_GAME_CONFIG "variants/mother3en/game.toml"
#endif
#ifndef GBARECOMP_BOXART
#define GBARECOMP_BOXART ""
#endif

#if defined(GBAGAME_RECOMP_UI)
#include "game_launcher_boot.h"
#endif

#if defined(MOTHER3_RETROACHIEVEMENTS)
#include "retroachievements.h"
#endif

namespace {

void print_usage() {
    std::printf(
        "%s [--bios <path>] [--rom <path>] [game.toml]\n"
        "\n"
        "A matching MOTHER 3 ROM and GBA BIOS are required.\n"
        "Android imports both through the setup screen; desktop may use\n"
        "--rom / --bios or game.toml.\n",
        GBARECOMP_WINDOW_TITLE);
}

int mother3_main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--help") == 0 ||
            std::strcmp(argv[i], "-h") == 0) {
            print_usage();
            return 0;
        }
    }

    std::vector<std::string> args(argv, argv + argc);

    // Android switches the working directory to app-private storage, redirects
    // diagnostics to android-runtime.log and appends the packaged game.toml.
    // This is a no-op on desktop.
    gbarecomp::MobileProcessOptions mobile;
    mobile.game_config = GBARECOMP_DEFAULT_GAME_CONFIG;
    mobile.program_name = GBARECOMP_PROGRAM_NAME;
    const bool on_mobile = gbarecomp::mobile_prepare_process(args, mobile);

#if defined(__ANDROID__)
    // The Android shell in this gbarecomp revision appends --no-launcher, but
    // this runtime revision does not expose that CLI option. Android already
    // bypasses the desktop preboot launcher below, so simply remove the stale
    // compatibility flag before run_game() parses argv.
    args.erase(std::remove(args.begin(), args.end(), std::string("--no-launcher")),
               args.end());
#endif

#if defined(__ANDROID__) && defined(MOTHER3_BOOTSTRAP_INTERP)
    // Public APKs intentionally omit ROM-derived generated C/C++. With no
    // static cart dispatch table, drive the main guest CPU through gbarecomp's
    // reference interpreter instead of entering runtime_dispatch() at PC=0.
    // A private build that regenerated generated/dispatch_table.cpp does not
    // define MOTHER3_BOOTSTRAP_INTERP and therefore uses the native recompiler.
    setenv("GBARECOMP_FORCE_INTERP", "1", 1);
#endif

#if defined(__ANDROID__) && defined(MOTHER3_TUNE_RG556)
    // Anbernic RG556 preset. Battle combos are timed to the music, so trim
    // the audio cushion (engine default 60 ms + 250 ms boot pre-roll).
    // setenv(..., 0) leaves any value supplied by the environment in place.
    setenv("GBARECOMP_AUDIO_SAMPLES", "512", 0);
    setenv("GBARECOMP_AUDIO_TARGET_MS", "40", 0);
    setenv("GBARECOMP_AUDIO_PREROLL_MS", "120", 0);
#endif

    gbarecomp::RunOptions opts;
    opts.builtin_game_name = GBARECOMP_BUILTIN_NAME;
    opts.builtin_rom_sha1 =
        (sizeof(GBARECOMP_BUILTIN_SHA1) > 1) ? GBARECOMP_BUILTIN_SHA1 : nullptr;
    opts.builtin_rom_crc32 = GBARECOMP_BUILTIN_CRC32;
    opts.launcher_region =
        (sizeof(GBARECOMP_BUILTIN_REGION) > 1) ? GBARECOMP_BUILTIN_REGION : nullptr;
    opts.launcher_boxart =
        (sizeof(GBARECOMP_BOXART) > 1) ? GBARECOMP_BOXART : nullptr;
    opts.launcher_game_config = GBARECOMP_DEFAULT_GAME_CONFIG;

    // MOTHER 3 is currently rendered at the native GBA 240x160 logical view.
    // Host scaling is handled by SDL without changing guest state.
    opts.max_view_width = 240;
    opts.max_resize_view_width = 240;
    opts.max_resize_view_height = 160;
    opts.resize_driven_view = false;
    opts.freely_resizable_window = true;
    opts.launcher_expose_widescreen = false;
    opts.launcher_expose_adaptive_view = false;

    if (on_mobile) {
        opts.orientation = gbarecomp::RunOptions::Orientation::Landscape;
        opts.resume_suspend_state_on_launch = true;
        opts.ui_touch_friendly = true;
        // touch_pad_default stays unset: the engine shows the touch pad on
        // phones and hides it when a controller is present (gaming handhelds
        // such as the RG556), until the player toggles it.
    }

#if defined(GBAGAME_RECOMP_UI)
    // Android has its own ROM/BIOS setup Activity, so the desktop pre-boot
    // launcher must not be shown there.
    if (!on_mobile && game_launcher_preboot(args, opts)) return 0;
#endif

#if defined(MOTHER3_RETROACHIEVEMENTS)
    // No-op unless the setup screen stored RetroAchievements credentials.
    mother3::ra_install(opts, MOTHER3_ROM_FILE);
#endif

    std::vector<char*> av;
    av.reserve(args.size());
    for (auto& arg : args) av.push_back(arg.data());
    const int result = gbarecomp::run_game(static_cast<int>(av.size()), av.data(), opts);
#if defined(MOTHER3_RETROACHIEVEMENTS)
    mother3::ra_shutdown();
#endif
    // Read by the Android launcher: without this line (a crash) or with a
    // non-zero code it shows the log tail after the game closes.
    std::fprintf(stderr, "mother3: session ended, exit code %d\n", result);
    return result;
}

}  // namespace

#if defined(__ANDROID__)
extern "C" int SDL_main(int argc, char** argv) {
#if defined(MOTHER3_TUNE_RG556)
    // Read when the game thread below starts: keep the frame loop on the T820's
    // Cortex-A76 cluster instead of letting it settle on a Cortex-A55.
    setenv("GBARECOMP_BIG_CORES", "1", 0);
#endif
    // The recompiled corpus can consume a much deeper host stack than a normal
    // SDLActivity thread. gbarecomp creates a dedicated, large-stack game thread.
    return gbarecomp::mobile_run_with_stack(mother3_main, argc, argv);
}
#else
int main(int argc, char** argv) {
    return mother3_main(argc, argv);
}
#endif
