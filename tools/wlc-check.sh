#!/usr/bin/env bash
#
# wlc-check.sh — quick live read of the wireless-charging (Qi) pad state over adb.
#
# Reports the same thing the client's classifyWlc() decides, so you can eyeball
# what the device is (or should be) reporting without pulling logs:
#
#   gpio27  — guest-detection line   (client reads: 1=guest, 0=idle, toggles=?)
#   gpio127 — charging enable         (1=charging on, 0=off)
#
# A single gpio27 sample can't tell "no pad" (a floating pin toggles) from a
# steady guest/idle, so this bursts-samples the line and classifies by movement,
# mirroring the on-device logic:
#   STABLE 1  + charging on  -> guest on pad
#   STABLE 0                 -> pad idle (no guest)
#   TOGGLING                 -> no pad connected  (but see the charging-off caveat)
#
# Note: with charging disabled (gpio127=0) a *placed* device can also make gpio27
# toggle on some hardware — so a TOGGLING reading while charging is off is
# ambiguous. This script flags that case instead of blindly calling it "no pad".
#
#   Usage:  ./wlc-check.sh [device-serial] [--watch [secs]] [--samples N]
#
# Needs adb + a rooted (adb root) debug build. Read-only: never writes a GPIO.

set -uo pipefail

GPIO_DIR="${GPIO_DIR:-/sys/devices/platform/soc/soc:customer_gpio}"
G27="$GPIO_DIR/gpio27"
G127="$GPIO_DIR/gpio127"
SAMPLES="${SAMPLES:-120}"
WATCH=0
WATCH_SECS=2
SERIAL=""

while [ $# -gt 0 ]; do
    case "$1" in
        --watch) WATCH=1; if [[ "${2:-}" =~ ^[0-9]+$ ]]; then WATCH_SECS="$2"; shift; fi; shift ;;
        --samples) SAMPLES="$2"; shift 2 ;;
        --samples=*) SAMPLES="${1#*=}"; shift ;;
        -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
        -*) echo "unknown option: $1" >&2; exit 1 ;;
        *) SERIAL="$1"; shift ;;
    esac
done

ADB="adb"; [ -n "$SERIAL" ] && ADB="adb -s $SERIAL"

if ! $ADB shell true >/dev/null 2>&1; then
    echo "error: no device over adb (serial=${SERIAL:-auto})" >&2; exit 1
fi
# Root is NOT required — best-effort elevate (helps on userdebug/eng), then gate on
# whether gpio27 is actually readable rather than on the uid.
if [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" != "0" ]; then
    $ADB root >/dev/null 2>&1 && sleep 2
fi
__probe="$($ADB shell "cat '$G27'" 2>/dev/null | tr -d '\r\n\000 ')"
if [ "$__probe" != "0" ] && [ "$__probe" != "1" ]; then
    echo "error: cannot read gpio27 at $G27 (got '${__probe:-<empty>}')." >&2
    echo "       Wrong path? override with GPIO_DIR=..., or the node needs root/sepolicy." >&2
    exit 1
fi

# read gpio127 (charging enable) — single read is enough, it's driven not floating.
read127() { $ADB shell "cat '$G127'" 2>/dev/null | tr -d '\r\n\000 '; }

# burst-sample gpio27 in one adb hop; echo the 0/1 sequence.
sample27() {
    $ADB shell "P='$G27'; i=0; o=''; while [ \$i -lt $SAMPLES ]; do o=\"\$o\$(cat \$P 2>/dev/null)\"; i=\$((i+1)); done; echo \$o" \
        2>/dev/null | tr -d '\r\n '
}

# classify a 0/1 sequence -> "LABEL|edges|highpct"
classify() {
    local seq="$1" len n1 edges prev c i high
    len=${#seq}
    if [ "$len" -eq 0 ] || printf '%s' "$seq" | grep -q '[^01]'; then
        echo "UNREADABLE|0|0"; return
    fi
    n1=$(printf '%s' "$seq" | tr -cd '1' | wc -c)
    edges=0; prev=""
    for (( i=0; i<len; i++ )); do
        c="${seq:$i:1}"
        [ -n "$prev" ] && [ "$c" != "$prev" ] && edges=$((edges+1))
        prev="$c"
    done
    high=$(( n1 * 100 / len ))
    if [ "$edges" -eq 0 ]; then
        [ "$high" -ge 50 ] && echo "STABLE-1|0|$high" || echo "STABLE-0|0|$high"
    else
        echo "TOGGLING|$edges|$high"
    fi
}

one_read() {
    local ch raw sig label edges high verdict
    ch="$(read127)"; [ -z "$ch" ] && ch="?"
    raw="$(sample27)"
    IFS='|' read -r label edges high <<<"$(classify "$raw")"

    case "$label" in
        STABLE-1)  verdict="GUEST ON PAD" ;;
        STABLE-0)  verdict="PAD IDLE (no guest)" ;;
        TOGGLING)
            if [ "$ch" = "0" ]; then
                verdict="AMBIGUOUS — line toggling while charging OFF (could be a placed guest, not necessarily 'no pad')"
            else
                verdict="NO PAD CONNECTED"
            fi ;;
        *) verdict="UNREADABLE (permission? wrong gpio path?)" ;;
    esac

    local chg; [ "$ch" = "1" ] && chg="ON" || { [ "$ch" = "0" ] && chg="OFF" || chg="$ch"; }
    printf 'charging=%-3s  gpio27=%-9s edges=%-3s high=%s%%   => %s\n' \
        "$chg" "$label" "$edges" "$high" "$verdict"
}

if [ "$WATCH" -eq 1 ]; then
    echo "watching WLC (gpio27 burst=$SAMPLES, every ${WATCH_SECS}s) — Ctrl-C to stop"
    while true; do
        printf '%s  ' "$(date +%H:%M:%S)"
        one_read
        sleep "$WATCH_SECS"
    done
else
    one_read
fi
