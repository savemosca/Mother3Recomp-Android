#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VARIANT="en"
ROM=""
BIOS=""
JOBS="2"
DEVICE=""
GENERATED_OPT=""
EMBED=1

usage() {
  cat <<'EOF'
Usage:
  tools/build-android.sh --rom /path/to/mother3.gba --bios /path/to/gba_bios.bin [--variant en|jpn] [--jobs N]
                         [--device rg556] [--generated-opt -O2] [--no-embed]

--device rg556      Anbernic RG556 preset: ARMv8.2-A code scheduled for
                    Cortex-A76, lower audio latency, game thread on big cores.
--generated-opt     Optimization level for the generated corpus (default -O1).
--no-embed          Keep the ROM and BIOS out of the APK. The app then opens
                    its setup screen (import ROM/BIOS once, RetroAchievements
                    login, PLAY); an embedded build skips that screen.

This creates a PRIVATE Android build. The ROM/BIOS and generated recompilation
corpus stay local and are never committed.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rom) ROM="$2"; shift 2 ;;
    --bios) BIOS="$2"; shift 2 ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --jobs) JOBS="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --generated-opt) GENERATED_OPT="$2"; shift 2 ;;
    --no-embed) EMBED=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

[[ -n "$ROM" && -f "$ROM" ]] || { echo "--rom is required" >&2; exit 2; }
[[ -n "$BIOS" && -f "$BIOS" ]] || { echo "--bios is required" >&2; exit 2; }

ROM="$(cd "$(dirname "$ROM")" && pwd)/$(basename "$ROM")"
BIOS="$(cd "$(dirname "$BIOS")" && pwd)/$(basename "$BIOS")"

case "$VARIANT" in
  en|english|mother3en)
    VARIANT="en"
    VDIR="mother3en"
    ROM_NAME="mother3_en.gba"
    ROM_SHA="306fb8874533b0fd0796208faa8bed2db4ae77fb"
    ;;
  jp|jpn|ja|mother3)
    VARIANT="jpn"
    VDIR="mother3"
    ROM_NAME="mother3_jpn.gba"
    ROM_SHA="4f0f493e12c2a8c61b2d809af03f7abf87a85776"
    ;;
  *)
    echo "Unknown variant '$VARIANT' (use en or jpn)" >&2
    exit 2
    ;;
esac

sha1_file() {
  if command -v sha1sum >/dev/null 2>&1; then
    sha1sum "$1" | awk '{print tolower($1)}'
  else
    shasum -a 1 "$1" | awk '{print tolower($1)}'
  fi
}

[[ "$(wc -c < "$ROM" | tr -d ' ')" == "33554432" ]] || {
  echo "ROM must be exactly 33554432 bytes" >&2; exit 3;
}
ACTUAL_ROM_SHA="$(sha1_file "$ROM")"
[[ "$ACTUAL_ROM_SHA" == "$ROM_SHA" ]] || {
  echo "ROM SHA-1 mismatch: $ACTUAL_ROM_SHA" >&2
  echo "Expected: $ROM_SHA" >&2
  exit 3
}

[[ "$(wc -c < "$BIOS" | tr -d ' ')" == "16384" ]] || {
  echo "GBA BIOS must be exactly 16384 bytes" >&2; exit 3;
}
ACTUAL_BIOS_SHA="$(sha1_file "$BIOS")"
[[ "$ACTUAL_BIOS_SHA" == "300c20df6731a33952ded8c436f7f186d25d3492" ]] || {
  echo "BIOS SHA-1 mismatch: $ACTUAL_BIOS_SHA" >&2
  exit 3
}

cd "$ROOT"
# .gitmodules declares these, but this tree records no submodule commits, so
# `git submodule update` alone fetches nothing. Clone what is missing; the
# engine is pinned to the revision the local patches were written against.
GBARECOMP_REV="af47d28742a808291031d5af56f30f9f646366be"
for sub in gbarecomp recomp-ui; do
  if [[ -z "$(ls -A "$ROOT/$sub" 2>/dev/null)" ]]; then
    git clone "$(git config -f .gitmodules "submodule.$sub.url")" "$ROOT/$sub"
    [[ "$sub" == gbarecomp ]] && git -C "$ROOT/gbarecomp" checkout -q "$GBARECOMP_REV"
  fi
done
# RetroAchievements runtime (third_party/ is ignored; pinned release tag).
RCHEEVOS_TAG="v12.5.0"
if [[ ! -f "$ROOT/third_party/rcheevos/include/rc_client.h" ]]; then
  git clone --depth 1 --branch "$RCHEEVOS_TAG" \
    https://github.com/RetroAchievements/rcheevos.git "$ROOT/third_party/rcheevos"
fi
# Local engine patches (patches/gbarecomp/*.patch), each applied once.
for patch in "$ROOT"/patches/gbarecomp/*.patch; do
  [[ -f "$patch" ]] || continue
  if git -C "$ROOT/gbarecomp" apply --reverse --check "$patch" 2>/dev/null; then
    echo "Engine patch already applied: $(basename "$patch")"
  else
    git -C "$ROOT/gbarecomp" apply "$patch"
    echo "Applied engine patch: $(basename "$patch")"
  fi
done
git -C gbarecomp config submodule.external/arm-recomp-core.url \
  https://github.com/mstan/arm-recomp-core.git
git -C gbarecomp submodule update --init \
  external/arm-recomp-core platform/android/third_party/SDL

HOST_BUILD="$ROOT/build-android-host"
cmake -S "$ROOT" -B "$HOST_BUILD" -G Ninja -DGBAGAME_RECOMP_UI=OFF
cmake --build "$HOST_BUILD" --target gba_recompile -- -j"$JOBS"

RECOMP="$HOST_BUILD/gbarecomp_build/gba_recompile"
[[ -x "$RECOMP" ]] || { echo "gba_recompile was not produced" >&2; exit 4; }

mkdir -p "$ROOT/variants/$VDIR/roms"
cp "$ROM" "$ROOT/variants/$VDIR/roms/$ROM_NAME"
mkdir -p "$ROOT/gbarecomp/bios"
cp "$BIOS" "$ROOT/gbarecomp/bios/gba_bios.bin"

echo "Generating MOTHER 3 recompilation corpus..."
(
  cd "$ROOT/variants/$VDIR"
  "$RECOMP" \
    --rom "roms/$ROM_NAME" \
    --config game.toml \
    --config symbols/mother3_jpn.toml \
    --symbols symbols/imported_symbols.tsv \
    --data-symbols symbols/imported_data_symbols.tsv \
    --out generated \
    --max-functions 65536
)

echo "Generating native BIOS corpus..."
"$RECOMP" \
  --bios "$BIOS" \
  --out "$ROOT/gbarecomp/src/runtime/generated_bios"

command -v gradle >/dev/null 2>&1 || {
  echo "Gradle is required (8.11.x recommended)." >&2
  exit 5
}

EMBED_ARGS=()
if [[ "$EMBED" == 1 ]]; then
  EMBED_ARGS=(-PprivateRom="$ROM" -PprivateBios="$BIOS")
fi

echo "Building private Android APK..."
(
  cd "$ROOT/android"
  gradle :app:assembleRelease \
    -Pmother3Variant="$VARIANT" \
    -PgbaAbis=arm64-v8a \
    -PgbaNativeJobs="$JOBS" \
    ${EMBED_ARGS[@]+"${EMBED_ARGS[@]}"} \
    ${DEVICE:+-Pmother3Device="$DEVICE"} \
    ${GENERATED_OPT:+-Pmother3GeneratedOpt="$GENERATED_OPT"}
)

echo
echo "APK output:"
find "$ROOT/android/app/build/outputs/apk/release" -maxdepth 1 -name '*.apk' -print
