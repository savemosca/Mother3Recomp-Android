#!/usr/bin/env bash
# Idle frame-pacing benchmark on a connected RG556: resumes the same suspend
# state with the same config, plays N seconds without input, backgrounds the
# game and prints its session_health line (late_frames = present gaps > 25 ms).
# Used to compare builds installed under separate application ids, so the
# player's own app and save are never touched.
#
# Needs adb, root on the device (su) and an unlocked screen. The state comes
# from a device-side backup directory holding mother3_en.sav,
# mother3_en.suspend.state and mother3_en.suspend.pending; the target app's
# files are overwritten.
#
# Usage: [WIDE=1] tools/rg556-idle-bench.sh <package> [seconds] [state-dir] [extra config.ini text]
set -u

PKG="$1"
SECS="${2:-60}"
SRC="${3:-/data/local/tmp/m3-pre-update-20261024}"
EXTRA="${4:-}"
WIDE="${WIDE:-0}"  # m3_widescreen value
D="/data/data/$PKG/files"

adb shell am force-stop "$PKG"
adb shell su -c "'set -e; mkdir -p $D/roms; rm -f $D/roms/mother3_en.suspend.* $D/android-runtime.log $D/debug-env.txt;
  cp $SRC/mother3_en.sav $SRC/mother3_en.suspend.state $SRC/mother3_en.suspend.pending $D/roms/;
  printf \"[Display]\\ninteger_scale = 1\\n\\n[Audio]\\nenhanced = 1\\n\\n[Launcher]\\nm3_widescreen = $WIDE\\n$EXTRA\" > $D/config.ini;
  U=\$(stat -c %u /data/data/$PKG); C=\$(ls -Zd /data/data/$PKG | cut -d\" \" -f1);
  chown -R \$U:\$U $D; chcon -R \$C $D'"
adb shell svc power stayon usb
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 \
  -n "$PKG/com.ylports.mother3recomp.Mother3SetupActivity" >/dev/null
sleep 3
adb shell input tap 959 392  # PLAY on the fork's launcher
until adb shell su -c "'grep -q suspend_resumed $D/android-runtime.log 2>/dev/null'"; do sleep 1; done
sleep "$SECS"
adb shell input keyevent HOME
until adb shell su -c "'grep -q session_health $D/android-runtime.log'"; do sleep 1; done
adb shell su -c "'grep -E \"suspend_resumed|audio=bridge|session_health\" $D/android-runtime.log'"
adb shell am force-stop "$PKG"
adb shell svc power stayon false
