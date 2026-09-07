#!/usr/bin/env bash
#
# mic-gain-check.sh — what the client will report as `mic_gain`, read straight off a
# device over adb, so you can cross-check the dashboard field without pulling logs.
#
# The client's MicGain.java can't read the live mixer (AOSP neverallows appdomain on
# /dev/snd), so it reports, in preference order:
#   live   — vendor.mdm.mic_gain property, written by the vendor micgain_probe daemon
#            (only on firmware that ships it)
#   config — the top-level <ctl name="TX_DECn Volume"> defaults in
#            /vendor/etc/mixer_paths_idp.xml; channels with no default = codec default 84
#
# This script prints both, plus (on userdebug/root only) the real tinymix reading, so
# the three can be compared. Expect 102 on units with the mic fix, 84 without.
#
#   Usage:  ./mic-gain-check.sh [device-serial]
#
# Read-only. Works on user builds (the tinymix section just says "n/a").

set -uo pipefail

SERIAL="${1:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

sh() { "${ADB[@]}" shell "$@" 2>/dev/null; }

echo "build:   $(sh getprop ro.build.type | tr -d '\r') $(sh getprop ro.build.id | tr -d '\r')"

# ── live (vendor probe daemon) ─────────────────────────────────────────────────
live=$(sh getprop vendor.mdm.mic_gain | tr -d '\r')
ts=$(sh getprop vendor.mdm.mic_gain_ts | tr -d '\r')
if [ -n "$live" ]; then
  echo "live:    $live${ts:+  (ts=$ts)}"
else
  echo "live:    n/a (vendor.mdm.mic_gain unset — firmware has no micgain_probe)"
fi

# ── config (mixer_paths xml) ───────────────────────────────────────────────────
xml=""
for f in /vendor/etc/mixer_paths_idp.xml /vendor/etc/mixer_paths.xml /vendor/etc/mixer_paths_qrd.xml; do
  if sh "[ -f $f ] && echo ok" | grep -q ok; then xml="$f"; break; fi
done
if [ -z "$xml" ]; then
  echo "config:  n/a (no mixer_paths xml on device — client omits mic_gain)"
else
  # Same rule as MicGain.parse(): only ctls OUTSIDE any <path> block count.
  vals=$(sh cat "$xml" | tr -d '\r' | awk '
    /<path[ >]/ { depth++ }
    depth == 0 && match($0, /<ctl name="TX_DEC[0-7] Volume" value="[-0-9]+"/) {
      s = substr($0, RSTART, RLENGTH)
      ch = substr(s, index(s, "TX_DEC") + 6, 1)
      v  = s; sub(/.*value="/, "", v); sub(/".*/, "", v)
      cfg[ch] = v
    }
    /<\/path>/ { if (depth > 0) depth-- }
    END {
      any = 0; out = ""
      for (i = 0; i < 8; i++) {
        if (i in cfg) { v = cfg[i]; any = 1 } else v = 84
        out = out (i ? "," : "") v
      }
      print out "|" (any ? "true" : "false")
    }')
  echo "config:  ${vals%%|*}  (configured=${vals##*|}, $(basename "$xml"))"
fi

# ── live via tinymix (userdebug/root only) ─────────────────────────────────────
tm=$(sh "for i in 0 1 2 3 4 5 6 7; do tinymix \"TX_DEC\$i Volume\" 2>&1; done" | tr -d '\r')
if echo "$tm" | grep -q "^TX_DEC0 Volume:"; then
  echo "tinymix: $(echo "$tm" | sed -E 's/^TX_DEC[0-7] Volume: *([-0-9]+).*/\1/' | paste -sd, -)"
else
  echo "tinymix: n/a (needs root: $(echo "$tm" | head -1))"
fi
