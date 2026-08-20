#!/usr/bin/env bash
#
# wlc-pulse-test.sh — validate the "pulse to read" WLC detection on real hardware
# BEFORE baking it into the client's classifyWlc().
#
# The matrix showed gpio27 only carries guest state while charging is ON (gpio127=1):
#   STABLE 1 = guest on pad, STABLE 0 = vacant. With charging OFF the line free-runs
#   (toggles) and is meaningless. So when charging is off, to read placement we briefly
#   pulse gpio127=1, take a settled gpio27 read, then restore gpio127.
#
# This script does exactly that and prints the resolved PLACED/VACANT, alongside the
# raw (non-pulsed) burst so you can see the pulse fixing the charging-off case. Use
# --watch and physically place/remove the device (with charging disabled) to confirm.
#
#   Usage:  ./wlc-pulse-test.sh [device-serial] [--watch [secs]]
#
# No root needed if the customer_gpio nodes are shell-accessible. Restores gpio127 to
# whatever it was after each pulse.

set -uo pipefail

GPIO_DIR="${GPIO_DIR:-/sys/devices/platform/soc/soc:customer_gpio}"
G27="$GPIO_DIR/gpio27"
G127="$GPIO_DIR/gpio127"

# client-mirrored tunables
PULSE_SETTLE_MS="${PULSE_SETTLE_MS:-350}"   # wait after enabling charging before reading
SETTLE_READS="${SETTLE_READS:-12}"          # max gpio27 reads to find a stable value
SETTLE_AGREE="${SETTLE_AGREE:-3}"           # consecutive equal reads = settled
BURST="${BURST:-100}"                        # raw comparison burst size

WATCH=0; WATCH_SECS=2; SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --watch) WATCH=1; [[ "${2:-}" =~ ^[0-9]+$ ]] && { WATCH_SECS="$2"; shift; }; shift ;;
        -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
        -*) echo "unknown option: $1" >&2; exit 1 ;;
        *) SERIAL="$1"; shift ;;
    esac
done
ADB="adb"; [ -n "$SERIAL" ] && ADB="adb -s $SERIAL"

$ADB shell true >/dev/null 2>&1 || { echo "error: no device (serial=${SERIAL:-auto})" >&2; exit 1; }
[ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" != "0" ] && { $ADB root >/dev/null 2>&1 && sleep 2; }

read27() { $ADB shell "cat '$G27'" 2>/dev/null | tr -d '\r\n\000 '; }
read127() { $ADB shell "cat '$G127'" 2>/dev/null | tr -d '\r\n\000 '; }
write127() { $ADB shell "echo $1 > '$G127'" >/dev/null 2>&1; }

# on-device sleep in ms (toybox sleep takes fractional seconds)
dsleep_ms() { $ADB shell "sleep 0.$(printf '%03d' "$1")" 2>/dev/null || sleep 0.4; }

# read gpio27 until SETTLE_AGREE consecutive reads agree; echo 0/1, or -1 if never stable
settle_read() {
    local stable="-1" agree=0 v i
    for (( i=0; i<SETTLE_READS; i++ )); do
        v="$(read27)"
        if [ "$v" != "0" ] && [ "$v" != "1" ]; then dsleep_ms 20; continue; fi
        if [ "$v" = "$stable" ]; then
            agree=$((agree+1)); [ "$agree" -ge "$SETTLE_AGREE" ] && { echo "$stable"; return; }
        else
            stable="$v"; agree=1
        fi
        dsleep_ms 20
    done
    echo "$stable"
}

# raw burst edge count (what the OLD client saw)
burst_raw() {
    local raw len n1 edges prev c i
    raw="$($ADB shell "P='$G27'; i=0; o=''; while [ \$i -lt $BURST ]; do o=\"\$o\$(cat \$P 2>/dev/null)\"; i=\$((i+1)); done; echo \$o" 2>/dev/null | tr -d '\r\n ')"
    len=${#raw}
    [ "$len" -eq 0 ] && { echo "?|0|0"; return; }
    n1=$(printf '%s' "$raw" | tr -cd '1' | wc -c); edges=0; prev=""
    for (( i=0; i<len; i++ )); do c="${raw:$i:1}"; [ -n "$prev" ] && [ "$c" != "$prev" ] && edges=$((edges+1)); prev="$c"; done
    echo "$((n1*100/len))|$edges|$len"
}

# the proposed client logic
pulse_classify() {
    local ch v method restored
    ch="$(read127)"
    if [ "$ch" = "1" ]; then
        v="$(settle_read)"; method="direct (charging ON)"
    else
        # pulse: enable charging, settle, read, restore to the prior (off) value
        write127 1
        dsleep_ms "$PULSE_SETTLE_MS"
        v="$(settle_read)"
        write127 "${ch:-0}"
        restored="$(read127)"
        method="pulse (charging OFF -> on ${PULSE_SETTLE_MS}ms -> restored=$restored)"
    fi
    local verdict
    case "$v" in
        1) verdict="PLACED (guest on pad)" ;;
        0) verdict="VACANT (no guest)" ;;
        *) verdict="UNKNOWN" ;;
    esac
    # raw comparison
    local rb rh re rl; rb="$(burst_raw)"; IFS='|' read -r rh re rl <<<"$rb"
    printf 'charging=%-3s  RESOLVED: %-22s  [%s]\n' \
        "$([ "$ch" = "1" ] && echo ON || echo OFF)" "$verdict" "$method"
    printf '              raw gpio27 burst: %s%% high, %s edges  (old code: %s)\n' \
        "$rh" "$re" "$([ "$re" -ge 2 ] && echo 'would say NO PAD' || echo 'stable')"
}

if [ "$WATCH" -eq 1 ]; then
    echo "watching (pulse-to-read) every ${WATCH_SECS}s — place/remove the device; Ctrl-C to stop"
    trap 'exit 130' INT TERM
    while true; do printf '%s  ' "$(date +%H:%M:%S)"; pulse_classify; sleep "$WATCH_SECS"; done
else
    pulse_classify
fi
